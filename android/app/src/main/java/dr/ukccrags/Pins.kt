package dr.ukccrags

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * How a crag's pin is marked, shared by the map and its legend so they agree.
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
        isWinter(type) -> R.color.type_winter
        else -> R.color.type_other
    },
)

/**
 * The same distinction as a letter in the disc. Trad and sport are a green and
 * a red, the pair colour-blind eyes most often cannot tell apart, so colour
 * alone left the commonest question on the map unanswered for some readers.
 * Grouped exactly as the colours are, so a letter never disagrees with its
 * disc; anything else stays a plain grey disc.
 */
fun pinGlyph(type: String): String = when {
    type.startsWith("Boulder", true) -> "B"
    type.equals("Trad", true) -> "T"
    type.equals("Sport", true) -> "S"
    isWinter(type) -> "W"
    else -> ""
}

private fun isWinter(type: String): Boolean =
    type.equals("Winter", true) || type.equals("Ice", true) || type.equals("Mixed", true)
