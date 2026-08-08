package dr.ukccrags

import android.content.Context
import java.util.Locale

/**
 * Distances in whatever the reader actually uses.
 *
 * The UK signs its roads in miles despite being metric elsewhere, so the
 * choice follows the locale's country rather than any measurement-system API.
 */
object Units {

    /** Countries that read road distances in miles. */
    private val MILES = setOf("GB", "US", "LR", "MM")

    private fun usesMiles(context: Context): Boolean {
        val locales = context.resources.configuration.locales
        val country = if (locales.isEmpty) Locale.getDefault().country
        else locales[0].country

        return country.uppercase() in MILES
    }

    fun distance(context: Context, metres: Float): String =
        if (usesMiles(context)) {
            context.getString(R.string.miles_away, metres / 1609.344f)
        } else {
            context.getString(R.string.km_away, metres / 1000f)
        }
}
