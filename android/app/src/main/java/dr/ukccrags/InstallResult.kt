package dr.ukccrags

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Where Android reports on an update committed by [Updates].
 *
 * Declared in the manifest rather than registered by the screen that asked,
 * since the answer can come after that screen has gone — and the one answer
 * that matters most, success, replaces this app and is never heard at all.
 */
class InstallResult : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        when (status) {
            // The first update through a session, or any Android that will not
            // let this app update itself unasked: show the system's own prompt.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { Log.w("UKC", "could not show the install prompt: $it") }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit

            // Dismissing the prompt is a choice, not a failure worth a message.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.add(context, "update: install failed ($status) $message")
                Toast.makeText(
                    context,
                    context.getString(R.string.update_not_installed, message ?: status.toString()),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    companion object {
        /**
         * The callback for one session. Mutable, because Android writes the
         * status and the prompt into it; explicit, so nothing else can be
         * woken by it.
         */
        fun sender(context: Context, sessionId: Int): IntentSender {
            val intent = Intent(context, InstallResult::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
        }
    }
}
