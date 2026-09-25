package dr.ukccrags

import androidx.recyclerview.widget.ListAdapter

/**
 * Submits [rows], diffing only when the order they share with what is shown
 * has not changed.
 *
 * A diff keeps the reader's place when a batch lands or a tick arrives. But
 * when thousands of rows swap places — nearest-first on, a new fix moving
 * every distance, a re-sort by grade — DiffUtil answers with thousands of
 * moves, and RecyclerView reorders those with a quadratic pass on the main
 * thread: 28 seconds of frozen list on a 3000-crag library. A new order is a
 * new list anyway, so it replaces the old one outright.
 */
fun <T : Any> ListAdapter<T, *>.submitKeepingPlace(
    rows: List<T>,
    key: (T) -> Any?,
    then: () -> Unit = {},
) {
    if (sameOrder(currentList, rows, key)) {
        submitList(rows) { then() }
    } else {
        // Clearing first means the new list goes in as one insert, no diff.
        submitList(null)
        submitList(rows) { then() }
    }
}

/** Whether the rows both lists hold appear in the same order in each. */
private fun <T> sameOrder(old: List<T>, new: List<T>, key: (T) -> Any?): Boolean {
    if (old.isEmpty() || new.isEmpty()) return true

    val newKeys = HashSet<Any?>(new.size * 2)
    new.forEach { newKeys.add(key(it)) }
    val oldKeys = HashSet<Any?>(old.size * 2)
    old.forEach { oldKeys.add(key(it)) }

    val kept = old.asSequence().map(key).filter { it in newKeys }.iterator()
    for (row in new) {
        val k = key(row)
        if (k !in oldKeys) continue
        if (!kept.hasNext() || kept.next() != k) return false
    }
    return true
}
