package dr.ukccrags

import android.app.Application
import com.google.android.material.color.DynamicColors
import org.osmdroid.config.Configuration

/**
 * Takes the phone's own palette where there is one.
 *
 * On Android 12 and up this repaints every activity from the wallpaper colours
 * the user already chose. Below that it does nothing and the theme's own
 * Material 3 palette stands in.
 */
class App : Application() {

    private companion object {
        const val TILE_KEEP_MS = 365L * 24 * 60 * 60 * 1000
        const val CACHE_MAX_BYTES = 600L * 1024 * 1024
        const val CACHE_TRIM_BYTES = 500L * 1024 * 1024
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        // OpenStreetMap refuses requests without a real user agent, and the
        // tile cache belongs in the app's own storage rather than shared space.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir.resolve("osm")
            osmdroidTileCache = filesDir.resolve("osm/tiles")

            // OpenStreetMap forbids downloading tiles in bulk but not keeping
            // the ones you were sent, and their terrain does not move. Tiles
            // normally expire in days, which quietly empties the cache between
            // trips; a year of retention is what makes "I looked at this crag
            // at home" still true in a valley with no signal.
            expirationOverrideDuration = TILE_KEEP_MS

            // Room for a season of crags rather than the default handful of
            // megabytes. Trimmed back to under the ceiling, oldest first.
            tileFileSystemCacheMaxBytes = CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = CACHE_TRIM_BYTES
        }
    }
}
