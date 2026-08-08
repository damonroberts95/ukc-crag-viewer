package dr.ukccrags

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Last-known coarse location, read straight from LocationManager. Enough to
 * sort crags by distance without pulling in Play Services.
 */
object Nearby {

    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION

    /** Both are requested together; the OS shows one dialog with a precision choice. */
    val PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    fun granted(context: Context): Boolean =
        PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * True when the fix is the real one rather than the roughly kilometre blur
     * Android hands out for an Approximate grant. A map pin needs the real one;
     * a "how far away is this crag" figure does not.
     */
    fun precise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Best recent fix across providers, or null if none is available yet. */
    fun lastKnown(context: Context): Location? {
        if (!granted(context)) return null

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        var best: Location? = null

        for (provider in manager.getProviders(true)) {
            val fix = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                ?: continue

            if (best == null || fix.time > best!!.time) best = fix
        }

        return best
    }
}
