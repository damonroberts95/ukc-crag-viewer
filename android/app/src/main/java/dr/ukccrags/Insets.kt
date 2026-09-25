package dr.ukccrags

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Pads a root view clear of the status and navigation bars. Needed because
 * targetSdk 35+ draws every activity edge to edge.
 *
 * Edge to edge also means the window no longer shrinks for the keyboard, so a
 * screen whose bottom is a list being searched passes [ime] to be padded clear
 * of it too; otherwise the results being typed for sit under the keys.
 */
fun applySystemBarInsets(root: View, ime: Boolean = false) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val keys = if (ime) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0

        view.updatePadding(
            left = bars.left,
            top = bars.top,
            right = bars.right,
            bottom = maxOf(bars.bottom, keys),
        )

        insets
    }
}
