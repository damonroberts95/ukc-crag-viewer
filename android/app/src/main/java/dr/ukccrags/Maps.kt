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

    /**
     * Directions for a crag: to its parking when UKC gives one and the reader
     * has not turned that off, to the crag's own pin otherwise. A crag with
     * several car parks asks which, since they usually serve different ends.
     * [choose] always asks, for when the default is not what is wanted today.
     */
    fun directionsTo(
        context: Context,
        area: String,
        latitude: Double?,
        longitude: Double?,
        parking: List<Parking>,
        choose: Boolean = false,
    ) {
        val options = mutableListOf<Pair<String, () -> Unit>>()

        for (spot in parking) {
            val name = spot.name.takeUnless { it.isBlank() || it.equals(area, true) }
            val label = if (name == null) context.getString(R.string.directions_parking)
            else context.getString(R.string.directions_parking_named, name)

            options += label to {
                open(context, spot.latitude, spot.longitude, context.getString(R.string.parking_for, area))
            }
        }

        if (latitude != null && longitude != null) {
            options += context.getString(R.string.directions_crag_itself) to {
                open(context, latitude, longitude, area)
            }
        }

        if (options.isEmpty()) return

        val wantParking = Settings.directionsToParking(context) && parking.isNotEmpty()

        when {
            options.size == 1 -> options.first().second()
            choose || (wantParking && parking.size > 1) ->
                com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.directions_to)
                    .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                        options[which].second()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            wantParking -> options.first().second()
            else -> options.last().second()
        }
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
