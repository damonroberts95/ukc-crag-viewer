package dr.ukccrags

import android.content.Context

/**
 * Whether UKC knows who we are.
 *
 * There is no way to ask this without loading a page: UKC only writes its
 * `userID` marker into a rendered page for a signed-in session. So the
 * WebView reports what it sees on every UKC page it loads, and the rest of
 * the app reads the last answer from here.
 */
object Session {

    private fun prefs(context: Context) =
        context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun userId(context: Context): Long = prefs(context).getLong(KEY_USER, 0L)

    fun signedIn(context: Context): Boolean = userId(context) > 0L

    /** Records what the WebView just saw on a ukclimbing.com page. */
    fun saw(context: Context, userId: Long) {
        prefs(context).edit().putLong(KEY_USER, userId.coerceAtLeast(0L)).apply()
    }

    /** Labels the sign-in and sync entries in a menu with the current state. */
    fun describeIn(context: Context, menu: android.view.Menu) {
        val signedIn = signedIn(context)

        menu.findItem(R.id.sign_in)?.setTitle(
            if (signedIn) R.string.signed_in else R.string.sign_in
        )

        menu.findItem(R.id.sync_ticks)?.setTitle(
            if (signedIn) R.string.sync_ticks else R.string.sync_ticks_signed_out
        )
    }

    private const val KEY_USER = "user_id"
}
