package dr.ukccrags

import android.content.Context

/**
 * The reader's choices, in one place so the settings screen and the code that
 * obeys them agree on names and defaults.
 */
object Settings {

    private fun prefs(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * On by default: the car park is where a satnav is actually wanted, and a
     * crag pin often sits in the middle of a moor with no road to it.
     */
    fun directionsToParking(context: Context): Boolean =
        prefs(context).getBoolean("directions_to_parking", true)

    fun setDirectionsToParking(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("directions_to_parking", on).apply()

    fun showParking(context: Context): Boolean =
        prefs(context).getBoolean("show_parking", true)

    fun setShowParking(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("show_parking", on).apply()

    fun weeklySync(context: Context): Boolean =
        prefs(context).getBoolean("weekly_sync", true)

    fun setWeeklySync(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("weekly_sync", on).apply()

    const val UNITS_AUTO = "auto"
    const val UNITS_MILES = "miles"
    const val UNITS_KM = "km"

    fun units(context: Context): String =
        prefs(context).getString("units", UNITS_AUTO) ?: UNITS_AUTO

    fun setUnits(context: Context, units: String) =
        prefs(context).edit().putString("units", units).apply()

    /** The getting-started guide shows itself once; after that it lives in settings. */
    fun guideSeen(context: Context): Boolean = prefs(context).getBoolean("guide_seen", false)

    fun setGuideSeen(context: Context) =
        prefs(context).edit().putBoolean("guide_seen", true).apply()
}
