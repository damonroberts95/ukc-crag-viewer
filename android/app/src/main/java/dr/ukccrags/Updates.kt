package dr.ukccrags

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dr.ukccrags.databinding.DialogProgressBinding
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check and install against the project's own GitHub releases.
 *
 * The app is not on Play and never will be — it scrapes a site for one person's
 * use — so nothing otherwise tells it a newer build exists. GitHub already
 * does: the release workflow attaches a signed APK to every `v*` tag, and
 * `releases/latest` describes it in one unauthenticated request.
 *
 * The APK is fetched and handed to the package installer here rather than
 * dropped on the browser, so the whole thing happens in one flow the user can
 * see. That is what needs REQUEST_INSTALL_PACKAGES and the FileProvider: the
 * installer runs in another process and cannot read this app's private files
 * without a granted content URI.
 */
object Updates {

    /** Where the release workflow publishes. */
    private const val REPO = "damonroberts95/ukc-crag-viewer"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** Matches the `cache-path` in res/xml/update_paths.xml. */
    private const val DOWNLOADS = "updates"

    private data class Release(
        val version: String,
        val apkUrl: String,
        val pageUrl: String,
        val notes: String,
    )

    /**
     * Checks in the background and reports back on the UI thread.
     *
     * Menu-driven, so silence is not an acceptable answer: "you are up to date"
     * is as much of a result as an offer to upgrade, and a network failure has
     * to say so rather than look like a button that did nothing.
     */
    fun check(activity: Activity) {
        Toast.makeText(activity, R.string.checking_updates, Toast.LENGTH_SHORT).show()

        Thread {
            val latest = fetchLatest()

            if (activity.isFinishing || activity.isDestroyed) return@Thread

            activity.runOnUiThread {
                when {
                    latest == null ->
                        Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()

                    !isNewer(latest.version, BuildConfig.VERSION_NAME) ->
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.update_none, BuildConfig.VERSION_NAME),
                            Toast.LENGTH_LONG,
                        ).show()

                    else -> offer(activity, latest)
                }
            }
        }.start()
    }

    /**
     * Reads the latest release. `HttpURLConnection` as everywhere else in the
     * app; one small JSON document does not justify a HTTP client dependency.
     */
    private fun fetchLatest(): Release? = runCatching {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub turns away a caller that will not name itself.
            setRequestProperty("User-Agent", "UkcCragViewer")
        }

        val body = if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.use { it.readBytes().decodeToString() }
        } else {
            null
        }

        connection.disconnect()

        val json = JSONObject(body ?: return@runCatching null)

        // Tags are written `v1.2`; the manifest's versionName is not.
        val version = json.optString("tag_name").removePrefix("v")
        if (version.isBlank()) return@runCatching null

        Release(
            version = version,
            apkUrl = firstApk(json),
            pageUrl = json.optString("html_url"),
            // Release notes are a nudge, not a changelog viewer.
            notes = json.optString("body").trim().take(300),
        )
    }.getOrElse {
        Log.w("UKC", "update check failed: $it")
        null
    }

    /**
     * The APK attached to the release. The workflow attaches exactly one, and
     * matching on the extension rather than an exact filename means renaming it
     * there does not quietly stop updates here.
     */
    private fun firstApk(json: JSONObject): String {
        val assets = json.optJSONArray("assets") ?: return ""

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url")
            }
        }

        return ""
    }

    /**
     * Compares dotted versions numerically, so 1.10 sits above 1.9 where a
     * string comparison would have it below. A part that is not a number cannot
     * be ranked and counts as lower than any release that is numbered.
     */
    fun isNewer(latest: String, installed: String): Boolean {
        val new = latest.split(".")
        val old = installed.split(".")

        for (i in 0 until maxOf(new.size, old.size)) {
            val a = new.getOrNull(i)?.toIntOrNull() ?: 0
            val b = old.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return a > b
        }

        return false
    }

    /**
     * Offers the newer build. A release with no APK on it — a workflow that
     * failed or is still running — is still worth showing, pointed at the
     * release page so the user can see for themselves what happened.
     */
    private fun offer(activity: Activity, release: Release) {
        val message = activity.getString(R.string.update_body, BuildConfig.VERSION_NAME).let {
            if (release.notes.isBlank()) it else it + "\n\n" + release.notes
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available, release.version))
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)

        if (release.apkUrl.isNotBlank()) {
            builder.setPositiveButton(R.string.update_download) { _, _ ->
                install(activity, release)
            }
        } else if (release.pageUrl.isNotBlank()) {
            builder.setPositiveButton(R.string.update_open) { _, _ ->
                openInBrowser(activity, release.pageUrl)
            }
        }

        builder.show()
    }

    /**
     * Downloads the APK, then asks the system to install it.
     *
     * Sideloading is gated per-app since Oreo and the grant cannot be requested
     * inline, so a missing one is worth catching before spending the download
     * rather than after.
     */
    private fun install(activity: Activity, release: Release) {
        if (!canInstall(activity)) {
            askToAllowInstalls(activity)
            return
        }

        val binding = DialogProgressBinding.inflate(activity.layoutInflater)
        binding.message.text = activity.getString(R.string.update_downloading, release.version)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(binding.root)
            .setCancelable(false)
            .show()

        Thread {
            val apk = download(activity, release) { read, total ->
                if (activity.isFinishing) return@download

                activity.runOnUiThread {
                    if (total > 0) {
                        binding.bar.isIndeterminate = false
                        binding.bar.progress = (read * 100 / total).toInt()
                    }
                    binding.detail.text = Formatter.formatShortFileSize(activity, read)
                }
            }

            if (activity.isFinishing || activity.isDestroyed) return@Thread

            activity.runOnUiThread {
                dialog.dismiss()

                if (apk == null) {
                    // The release page is always a way out of a failed download.
                    MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.update_download_failed)
                        .setPositiveButton(R.string.update_open) { _, _ ->
                            openInBrowser(activity, release.pageUrl)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    handToInstaller(activity, apk)
                }
            }
        }.start()
    }

    /**
     * Fetches the APK into the cache directory, reporting bytes as they land.
     *
     * Written to a `.part` file and renamed on success, as TopoCache does, so a
     * download killed halfway cannot leave something that looks installable.
     */
    private fun download(
        activity: Activity,
        release: Release,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File? = runCatching {
        val dir = File(activity.cacheDir, DOWNLOADS).apply { mkdirs() }

        // One name, so repeated checks cannot silt the cache up with old builds.
        val target = File(dir, "update.apk")
        val partial = File(dir, "update.apk.part")

        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "UkcCragViewer")
        }

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return@runCatching null
        }

        val total = connection.contentLength.toLong()
        var read = 0L

        connection.inputStream.use { input ->
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)

                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break

                    output.write(buffer, 0, n)
                    read += n
                    onProgress(read, total)
                }
            }
        }

        connection.disconnect()

        if (read <= 0L) return@runCatching null

        target.delete()
        if (!partial.renameTo(target)) return@runCatching null

        target
    }.getOrElse {
        Log.w("UKC", "update download failed: $it")
        null
    }

    /**
     * Whether this app may install packages. Below Oreo the manifest permission
     * is the whole story; from Oreo on it is a per-app switch in Settings.
     */
    private fun canInstall(activity: Activity): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()

    /** The grant lives in Settings and cannot be asked for with a dialog. */
    private fun askToAllowInstalls(activity: Activity) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.update_needs_install_permission)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )

                if (intent.resolveActivity(activity.packageManager) != null) {
                    activity.startActivity(intent)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Hands the file to the package installer. The installer is another process
     * and cannot read the cache directory, so it gets a FileProvider URI and a
     * read grant that lasts as long as the intent.
     */
    private fun handToInstaller(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${BuildConfig.APPLICATION_ID}.updates",
            apk,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching { activity.startActivity(intent) }.onFailure {
            Log.w("UKC", "installer refused the APK: $it")
            Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openInBrowser(activity: Activity, url: String) {
        if (url.isBlank()) return

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        // No browser at all is a real state on a stripped ROM, and the
        // uncaught exception would take the app down with it.
        if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(intent)
        } else {
            Toast.makeText(activity, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
