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

    fun showOnce(activity: Activity) {
        if (Settings.guideSeen(activity)) return
        show(activity)
    }

    fun show(activity: Activity) {
        Settings.setGuideSeen(activity)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.guide_title)
            .setMessage(R.string.guide_body)
            .setPositiveButton(R.string.guide_add) { _, _ ->
                activity.startActivity(Intent(activity, BrowseActivity::class.java))
            }
            .setNegativeButton(R.string.guide_later, null)
            .show()
    }
}
