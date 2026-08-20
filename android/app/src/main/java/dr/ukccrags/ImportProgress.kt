package dr.ukccrags

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The import, shown outside the app.
 *
 * A region-wide import is minutes of work, and watching a progress dialog for
 * minutes is nobody's idea of a good time. The screen is kept awake while it
 * runs — see [BrowseActivity] — and this puts the same count in the shade so the
 * phone can be put down and glanced at.
 *
 * Not a foreground service: the import lives in a WebView on a screen, and
 * Android may still freeze the process once the app has been in the background a
 * while. Screen on and app open is the reliable way to run a big one; this is
 * for glancing, not for guaranteeing.
 */
object ImportProgress {

    private const val CHANNEL = "import"
    private const val ID = 4201

    fun show(context: Context, title: String, detail: String, done: Int, total: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        channel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, BrowseActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true)
            }
            .build()

        runCatching { manager.notify(ID, note) }
    }

    /**
     * The result, once. Replaces the running counter with something that can be
     * swiped away, for a reader who walked off and left it to it.
     */
    fun done(context: Context, title: String, detail: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        channel(context)

        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, CragListActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

        runCatching { manager.notify(ID, note) }
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID) }
    }

    private fun channel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        // Silent by design: it is a counter, not news.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_import),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }
}
