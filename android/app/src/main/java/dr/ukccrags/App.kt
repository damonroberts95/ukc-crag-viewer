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

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        // OpenStreetMap refuses requests without a real user agent, and the
        // tile cache belongs in the app's own storage rather than shared space.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir.resolve("osm")
            osmdroidTileCache = filesDir.resolve("osm/tiles")
        }
    }
}
