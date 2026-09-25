package dr.ukccrags

import android.content.Context
import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray

/**
 * `extract.js` as each WebView gets it, and the checks that go with handing a
 * web page a way into the app.
 *
 * A JavaScript interface is visible to every frame in the WebView, whatever its
 * origin — an advert's iframe on a UKC page could call `Android.saveCrag` as
 * readily as our own script. So each WebView gets a random token, written into
 * its copy of the script where only the script's own closure can see it, and
 * every bridge call that stores or fetches something has to present it. A page
 * script can call our `window.__ukc…` functions, but cannot read the token out
 * of them.
 */
object PageScript {

    private const val TOKEN_MARK = "__UKC_BRIDGE_TOKEN__"
    private const val WATCH_MARK = "__UKC_WATCH__"

    private val random = SecureRandom()

    @Volatile
    private var source: String? = null

    /** A fresh token, one per WebView: hex, so it is safe inside a JS string. */
    fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The script with [token] written in. [watchKind] is for the browser screen
     * only: the page-kind watcher labels its button, and in a WebView nobody
     * sees it is a MutationObserver re-reading the page for no one.
     */
    fun load(context: Context, token: String, watchKind: Boolean): String? {
        val text = source ?: runCatching {
            context.applicationContext.assets.open("extract.js")
                .bufferedReader().use { it.readText() }
        }.getOrNull()?.also { source = it } ?: return null

        return text
            .replace(TOKEN_MARK, token)
            .replace(WATCH_MARK, if (watchKind) "yes" else "no")
    }

    /** Constant-time, so a page cannot learn the token a character at a time. */
    fun matches(expected: String, given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())

    /**
     * evaluateJavascript hands back a JSON value; a string comes back as a JSON
     * string *literal*. Parsed as JSON rather than by hand, so escapes such as
     * `\n` or `é` in a crag name come out right.
     */
    fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        if (!raw.startsWith("\"")) return raw
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrDefault("{}")
    }

    /** Asked of a hidden WebView's page before the script is put into it. */
    const val CHALLENGE_CHECK =
        "/just a moment|checking your browser|verify you are human|attention required/i" +
            ".test(document.title || '')"

    /** Claims the page for one run, so a second onPageFinished does not start another. */
    const val CLAIM =
        "(function(){ if (window.__ukcClaimed) return 'no'; window.__ukcClaimed = true; return 'yes'; })()"

    private fun within(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /** UKC itself: the only site the browser screen should be navigating. */
    fun isUkc(uri: Uri?): Boolean {
        val host = uri?.host?.lowercase() ?: return false
        return within(host, "ukclimbing.com")
    }

    /** Cloudflare's challenge pages, which UKC's own clearance passes through. */
    fun isChallenge(uri: Uri?): Boolean {
        val host = uri?.host?.lowercase() ?: return false
        return within(host, "challenges.cloudflare.com")
    }

    /**
     * Whether a topo or photo link may be fetched at all: https only. The
     * links come from page script, but only script holding this session's
     * token can hand one over, which is the real guard. The host is not
     * pinned: UKC's image host is not documented, and guessing it wrong would
     * silently refuse every topo. Nor can a foreign host be used to leak the
     * session, since CookieManager only ever returns a URL's own cookies.
     */
    fun isImageUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
    }

    /** UKC's own hosts, so an image served from anywhere else can be noted once. */
    fun isUkcImageHost(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return within(host, "ukc2.com") || within(host, "ukclimbing.com")
    }
}
