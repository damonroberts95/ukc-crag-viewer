package dr.ukccrags

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.color.DynamicColors
import java.lang.ref.WeakReference
import org.osmdroid.config.Configuration

/**
 * Takes the phone's own palette where there is one.
 *
 * On Android 12 and up this repaints every activity from the wallpaper colours
 * the user already chose. Below that it does nothing and the theme's own
 * Material 3 palette stands in.
 *
 * Also the one place that knows which screen is in front, which is what the
 * hidden readers need: see [park].
 */
class App : Application() {

    companion object {
        private const val TILE_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        const val CACHE_MAX_BYTES = 600L * 1024 * 1024
        private const val CACHE_TRIM_BYTES = 500L * 1024 * 1024

        private const val REPO_URL = "https://github.com/damonroberts95/ukc-crag-viewer"

        /**
         * How the app names itself to map tile and Overpass servers. Their
         * usage policies ask for an application name, a version and a way to
         * reach whoever runs it; the bare package name said none of that.
         */
        fun userAgent(@Suppress("UNUSED_PARAMETER") context: Context? = null): String =
            "UKCCragViewer/${BuildConfig.VERSION_NAME} (+$REPO_URL)"

        private lateinit var app: App

        /** Screens started and not yet stopped: zero means the app is in the background. */
        private var started = 0

        private var shown: WeakReference<Activity>? = null

        /** Hidden WebViews carried from screen to screen, with the context each was built on. */
        private val parked = LinkedHashMap<View, MutableContextWrapper?>()

        /** Whether a screen of ours is in front, so a retry is worth making now. */
        fun foreground(): Boolean = started > 0 && shown?.get() != null

        /**
         * Keeps a one-pixel reader in whichever screen is in front.
         *
         * A WebView has to be in a window to load reliably, and one left in the
         * screen that happened to start it is hidden — and its timers throttled
         * — the moment the reader opens another screen, or leaks that screen
         * when it is recreated for a rotation. So the view goes into each screen
         * as it comes forward, and out of all of them when the app goes to the
         * background. [wrapper] is the context the view was built on; it is
         * pointed at the screen it is in, and back at the application after,
         * so no screen outlives itself through it. Main thread only.
         */
        fun park(view: View, wrapper: MutableContextWrapper?, fallback: ViewGroup? = null) {
            parked[view] = wrapper

            val activity = shown?.get()
            if (started > 0 && activity != null && !activity.isFinishing) {
                moveInto(activity, view, wrapper)
            } else if (fallback != null) {
                detach(view)
                fallback.addView(view, ViewGroup.LayoutParams(1, 1))
            }
        }

        /** Takes a parked view out of whatever screen holds it. Main thread only. */
        fun unpark(view: View) {
            val wrapper = parked.remove(view)
            detach(view)
            if (::app.isInitialized) wrapper?.baseContext = app
        }

        private fun moveInto(activity: Activity, view: View, wrapper: MutableContextWrapper?) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (view.parent === content) return

            detach(view)
            wrapper?.baseContext = activity
            content.addView(view, FrameLayout.LayoutParams(1, 1))
        }

        private fun detach(view: View) {
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        DynamicColors.applyToActivitiesIfAvailable(this)

        // Nothing can be running in a process that has only just started, so
        // any progress notification still in the shade is a leftover — from a
        // run that was killed, or from the app being replaced under it.
        ImportProgress.clear(this)

        // OpenStreetMap refuses requests without a real user agent, and the
        // tile cache belongs in the app's own storage rather than shared space.
        Configuration.getInstance().apply {
            userAgentValue = userAgent(this@App)
            osmdroidBasePath = filesDir.resolve("osm")
            osmdroidTileCache = filesDir.resolve("osm/tiles")

            // OpenStreetMap forbids downloading tiles in bulk but not keeping
            // the ones you were sent. Expiry here only decides when a tile is
            // fetched again while there is signal: osmdroid still draws an
            // expired tile when the download fails, and it trims the cache by
            // size alone, oldest first. So a month keeps new paths turning up
            // without costing anything in a valley with no signal.
            expirationOverrideDuration = TILE_KEEP_MS

            // Room for a season of crags rather than the default handful of
            // megabytes. Trimmed back to under the ceiling, oldest first.
            tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
        }

        registerActivityLifecycleCallbacks(Screens())
    }

    /** Follows the front screen for [park], and tells the queue when there is one again. */
    private inner class Screens : ActivityLifecycleCallbacks {

        override fun onActivityStarted(activity: Activity) {
            started++
        }

        override fun onActivityResumed(activity: Activity) {
            shown = WeakReference(activity)
            for ((view, wrapper) in parked.entries.toList()) moveInto(activity, view, wrapper)
            QueueDrain.appResumed(activity)
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started > 0) return

            // Backgrounded: nothing of ours is on screen to hold them.
            shown = null
            for ((view, wrapper) in parked.entries.toList()) {
                detach(view)
                wrapper?.baseContext = this@App
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (shown?.get() === activity) shown = null

            for ((view, wrapper) in parked.entries.toList()) {
                if (wrapper?.baseContext === activity) {
                    detach(view)
                    wrapper.baseContext = this@App
                }
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
