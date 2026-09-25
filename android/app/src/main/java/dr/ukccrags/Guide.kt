package dr.ukccrags

import android.app.Activity
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * How to get crags in, said once on first opening and kept in settings.
 *
 * The one thing that is not obvious: an import is a UKC search by place and
 * distance, not a hunt through crags one at a time. The app takes every crag
 * the search found.
 */
object Guide {

    /**
     * Shows the guide if it has never been seen, returning whether it did.
     * [onDone] runs once it is out of the way — with true if the reader went
     * straight off to add crags — so the first run's other questions wait for
     * it instead of stacking on top of it.
     */
    fun showOnce(activity: Activity, onDone: (addingNow: Boolean) -> Unit = {}): Boolean {
        if (Settings.guideSeen(activity)) return false
        show(activity, onDone)
        return true
    }

    fun show(activity: Activity, onDone: (addingNow: Boolean) -> Unit = {}) {
        Settings.setGuideSeen(activity)

        var adding = false

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_body)
            .setPositiveButton(R.string.guide_add) { _, _ ->
                adding = true
                activity.startActivity(Intent(activity, BrowseActivity::class.java))
            }
            .setNegativeButton(R.string.guide_later, null)
            .setOnDismissListener { onDone(adding) }
            .show()
    }
}
