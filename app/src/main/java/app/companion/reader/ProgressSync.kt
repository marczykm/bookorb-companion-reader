package app.companion.reader

/** Decides when reading progress should be queued for upload: every [pagesPerSync] page turns. */
class ProgressSyncCounter(private val pagesPerSync: Int) {
    private var pagesSinceSync = 0
    private var lastSeen: Double? = null

    /**
     * Call with every position the reader reports. [moved] is whether the reader has turned away from
     * where the book was opened. True when this position completes another batch of pages.
     */
    fun onPosition(progress: Double, moved: Boolean): Boolean {
        val previous = lastSeen
        lastSeen = progress
        if (!moved || previous == null || progress == previous) return false
        if (++pagesSinceSync < pagesPerSync) return false
        pagesSinceSync = 0
        return true
    }
}
