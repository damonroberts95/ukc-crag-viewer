package dr.ukccrags

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * How a crag's pin is coloured, shared by both maps so they agree.
 *
 * A pin says what kind of climbing is there before it is tapped, which is the
 * difference between a map of the library and a list of dots.
 */

/** The type most of a crag's climbs are. Empty when it has none worth counting. */
fun Crag.dominantType(): String = buttresses
    .flatMap { it.climbs }
    .map { it.type }
    .filter { it.isNotBlank() }
    .groupingBy { it }
    .eachCount()
    .maxByOrNull { it.value }
    ?.key
    .orEmpty()

/** Distinct in hue, not just lightness: these are read at a glance in sunlight. */
fun Context.pinColour(type: String): Int = ContextCompat.getColor(
    this,
    when {
        type.startsWith("Boulder", true) -> R.color.type_boulder
        type.equals("Trad", true) -> R.color.type_trad
        type.equals("Sport", true) -> R.color.type_sport
        type.equals("Winter", true) || type.equals("Ice", true) ||
            type.equals("Mixed", true) -> R.color.type_winter
        else -> R.color.type_other
    },
)

/** MapLibre styles want CSS, not an Android colour int. */
fun Int.asCssColour(): String = String.format("#%06X", 0xFFFFFF and this)
