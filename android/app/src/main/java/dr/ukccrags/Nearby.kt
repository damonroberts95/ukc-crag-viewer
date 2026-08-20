package dr.ukccrags

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
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

    /**
     * Asks the providers for a new fix rather than repeating whatever another
     * app last asked for. [onFix] runs on the main thread with the first fix to
     * arrive, or with the last known one if nothing lands inside [timeoutMs].
     */
    fun refresh(context: Context, timeoutMs: Long = 15_000L, onFix: (Location?) -> Unit) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        // The passive provider only repeats other apps' fixes, which is what
        // this is trying to get away from.
        val providers = manager?.getProviders(true)
            ?.filter { it != LocationManager.PASSIVE_PROVIDER }
            .orEmpty()

        if (manager == null || !granted(context) || providers.isEmpty()) {
            onFix(lastKnown(context))
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var settled = false
        lateinit var listener: LocationListener

        // Whichever provider answers first wins; the rest are dropped so the
        // app is not left holding a GPS lock behind the reader's back.
        fun settle(fix: Location?) {
            if (settled) return
            settled = true

            handler.removeCallbacksAndMessages(null)
            runCatching { manager.removeUpdates(listener) }
            onFix(fix ?: lastKnown(context))
        }

        listener = LocationListener { fix -> settle(fix) }

        for (provider in providers) {
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }

        handler.postDelayed({ settle(null) }, timeoutMs)
    }

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
