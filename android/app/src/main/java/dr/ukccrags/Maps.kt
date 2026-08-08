package dr.ukccrags

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object Maps {

    /**
     * Opens a pin in whichever maps app is installed, falling back to the
     * browser when no app handles a geo: URI.
     */
    fun open(context: Context, latitude: Double, longitude: Double, label: String) {
        val coords = "$latitude,$longitude"
        val geo = Uri.parse("geo:$coords?q=$coords(${Uri.encode(label)})")
        val geoIntent = Intent(Intent.ACTION_VIEW, geo)

        if (geoIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(geoIntent)
            return
        }

        val web = Uri.parse("https://maps.google.com/?q=$coords")
        val webIntent = Intent(Intent.ACTION_VIEW, web)

        if (webIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(webIntent)
            return
        }

        Toast.makeText(context, R.string.no_maps_app, Toast.LENGTH_SHORT).show()
    }

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
