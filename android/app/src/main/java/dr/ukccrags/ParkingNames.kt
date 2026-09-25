package dr.ukccrags

import android.content.Context

/**
 * Tells one car park from another at a crag that has several.
 *
 * UKC usually names a crag's parking after the crag itself, or not at all,
 * so a chooser offering "Parking" twice — Plymouth Hoe has one at each end —
 * says nothing about which to drive to. A spot keeps its own name when that
 * name tells it apart. Otherwise it is named for the side of the crag it is
 * on, measured from the middle of all its car parks, which is what "each end"
 * means on the ground, and for the nearest buttress with a pin when the crag's
 * buttresses are to hand.
 */
object ParkingNames {

    /** Car parks closer than this to the middle are not on any side of it. */
    private const val SIDE_METRES = 60.0

    /** A buttress further than this is not what a car park is "for". */
    private const val NEAR_METRES = 1500.0

    /** One label per spot, in the order given. */
    fun labels(
        context: Context,
        area: String,
        parking: List<Parking>,
        buttresses: List<Buttress> = emptyList(),
    ): List<String> {
        val own = parking.map { spot ->
            spot.name.trim().takeUnless { it.isBlank() || it.equals(area.trim(), ignoreCase = true) }
        }

        if (parking.size <= 1) {
            return own.map { name ->
                if (name == null) context.getString(R.string.directions_parking)
                else context.getString(R.string.directions_parking_named, name)
            }
        }

        val midLat = parking.sumOf { it.latitude } / parking.size
        val midLon = parking.sumOf { it.longitude } / parking.size
        val located = buttresses.filter { it.hasPin && it.name.isNotBlank() }

        val labels = parking.mapIndexed { i, spot ->
            val name = own[i]
            val unique = name != null && own.count { it.equals(name, ignoreCase = true) } == 1

            val side = side(context, midLat, midLon, spot)
            val near = located
                .map { it to Walk.metresBetween(spot.latitude, spot.longitude, it.latitude!!, it.longitude!!) }
                .filter { it.second <= NEAR_METRES }
                .minByOrNull { it.second }
                ?.first?.name

            val base = when {
                unique -> context.getString(R.string.directions_parking_named, name)
                side != null -> context.getString(R.string.parking_side, side)
                else -> context.getString(R.string.directions_parking)
            }

            if (!unique && near != null) context.getString(R.string.parking_near, base, near) else base
        }

        // Two on the same side with the same nearest buttress: number them.
        return labels.mapIndexed { i, label ->
            if (labels.count { it == label } > 1) {
                context.getString(R.string.parking_numbered, label, labels.take(i + 1).count { it == label })
            } else {
                label
            }
        }
    }

    /** The label for one spot among its crag's others. */
    fun labelFor(
        context: Context,
        area: String,
        spot: Parking,
        all: List<Parking>,
        buttresses: List<Buttress> = emptyList(),
    ): String {
        val index = all.indexOfFirst {
            it.latitude == spot.latitude && it.longitude == spot.longitude
        }
        if (index < 0) return labels(context, area, listOf(spot), buttresses).first()
        return labels(context, area, all, buttresses)[index]
    }

    /** Which of the eight compass sides of the middle a spot is on, if any. */
    private fun side(context: Context, midLat: Double, midLon: Double, spot: Parking): String? {
        if (Walk.metresBetween(midLat, midLon, spot.latitude, spot.longitude) < SIDE_METRES) return null

        // Flat-earth bearing is plenty over the width of one crag.
        val north = spot.latitude - midLat
        val east = (spot.longitude - midLon) * kotlin.math.cos(Math.toRadians(midLat))
        val degrees = (Math.toDegrees(kotlin.math.atan2(east, north)) + 360.0) % 360.0
        val sector = ((degrees + 22.5) / 45.0).toInt() % 8

        return context.resources.getStringArray(R.array.compass_sides)[sector]
    }
}
