package dev.khanlabs.lanshare.transfer

/** One file of an incoming send, as the "Choose files" list shows it. */
data class ChoiceFile(
    val id: String,
    val name: String,
    val size: Long,
    /** A file with this name and size is already in the receive folder. */
    val alreadyHere: Boolean,
)

/** The arithmetic behind "Choose files": what a pick would take, and what to tell the sender. */
object Choice {
    data class Totals(val count: Int, val bytes: Long)

    /**
     * What accepting would take. [chosen] is the IDs the person ticked (null means all); with [skip] on, files already here
     * are left out of the count as well, like the "skip files already here" box.
     */
    fun totals(files: List<ChoiceFile>, chosen: Set<String>?, skip: Boolean): Totals {
        var count = 0
        var bytes = 0L
        for (f in files) {
            if (chosen != null && f.id !in chosen) continue
            if (skip && f.alreadyHere) continue
            count++
            bytes += f.size
        }
        return Totals(count, bytes)
    }

    /** What to give [dev.khanlabs.lanshare.net.Receiver.answer]: null when every file is ticked, so nothing is limited. */
    fun wantedFor(files: List<ChoiceFile>, chosen: Set<String>?): Set<String>? {
        if (chosen == null) return null
        val known = files.mapTo(HashSet(files.size * 2)) { it.id }
        val ticked = chosen.filterTo(HashSet()) { it in known }
        return if (ticked.size >= known.size) null else ticked
    }
}
