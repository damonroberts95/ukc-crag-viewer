package dr.ukccrags

/**
 * Whether a long job is running, for screens that are not the one running it.
 *
 * An import lives in [BrowseActivity]'s WebView, but the reader does not have to
 * sit and watch it: the progress is in the shade, so they can go back to the
 * crag list and carry on reading. The list still has to hold the screen awake
 * while they do, since a sleeping screen throttles the WebView doing the work,
 * and the list has no other way of knowing.
 */
object ImportState {

    private val watchers = mutableListOf<(Boolean) -> Unit>()

    var running: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            watchers.toList().forEach { it(value) }
        }

    fun watch(watcher: (Boolean) -> Unit) {
        watchers.add(watcher)
        watcher(running)
    }

    fun forget(watcher: (Boolean) -> Unit) {
        watchers.remove(watcher)
    }
}
