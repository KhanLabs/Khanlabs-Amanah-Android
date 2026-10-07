package dev.khanlabs.lanshare.transfer

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The time span the Stats screen looks at. */
enum class StatsRange(val label: String) { Week("Week"), Month("Month"), All("All") }

/** One device in the "top devices" list: what moved between this phone and it. */
data class DeviceStat(val name: String, val bytes: Long, val transfers: Int)

/** One kind of file in the "what you send" bar. */
data class TypeStat(val label: String, val files: Int)

/** Everything the Stats screen shows, worked out from the Activity list. Nothing here leaves the phone. */
data class StatsData(
    val range: StatsRange,
    val sent: Long,
    val received: Long,
    /** Files that arrived or went out. */
    val files: Int,
    /** Transfers asked for, whatever came of them, and how many of those finished whole. */
    val transfers: Int,
    val finished: Int,
    /** Bytes moved in each bar, oldest first, with a short label for each (empty when a label would crowd). */
    val bars: List<Long>,
    val barLabels: List<String>,
    /** The best speed of any transfer, bytes per second, or 0 when none was timed. */
    val fastestBps: Long,
    val topDevices: List<DeviceStat>,
    val types: List<TypeStat>,
    /** Transfers that lost the connection and went on. */
    val resumed: Int,
    /** Files left out because they were already there, and their size. */
    val filesSkipped: Int,
    val bytesSkipped: Long,
    /** Files the receiver confirmed with the same SHA-256. */
    val verified: Int,
) {
    val total: Long get() = sent + received
    val isEmpty: Boolean get() = transfers == 0
}

object Stats {
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif", "dng", "raw")
    private val VIDEO = setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v", "mpg", "mpeg")
    private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")
    private val DOCUMENT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "csv", "md", "epub")

    fun compute(
        all: List<HistoryEntry>,
        range: StatsRange,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): StatsData {
        val edges = edges(range, now, zone, all)
        val inRange = all.filter { it.at >= edges.first() }
        // bytes count only for transfers that moved something and were not turned away
        val moved = inRange.filter { it.state == "done" || it.state == "partial" }

        val bars = LongArray(edges.size - 1)
        for (e in moved) {
            val i = edges.indexOfLast { it <= e.at }.coerceIn(0, bars.size - 1)
            bars[i] += e.bytes
        }

        val devices = LinkedHashMap<String, DeviceStat>()
        for (e in moved) {
            val key = e.peerId.ifEmpty { e.peer }
            val old = devices[key]
            // the list is newest first, so the first name seen is the latest one
            devices[key] = DeviceStat(old?.name ?: e.peer, (old?.bytes ?: 0) + e.bytes, (old?.transfers ?: 0) + 1)
        }

        val kinds = LinkedHashMap<String, Int>()
        for (e in moved) for (n in e.names) kinds.merge(kindOf(n), 1, Int::plus)

        val fastest = moved.maxOfOrNull { e ->
            val timed = if (e.acceptedAt > 0 && e.endedAt > e.acceptedAt) e.bytes * 1000 / (e.endedAt - e.acceptedAt) else 0L
            maxOf(e.peakBps, timed)
        } ?: 0L

        return StatsData(
            range = range,
            sent = moved.filter { !it.incoming }.sumOf { it.bytes },
            received = moved.filter { it.incoming }.sumOf { it.bytes },
            files = moved.sumOf { it.files },
            transfers = inRange.size,
            finished = inRange.count { it.state == "done" },
            bars = bars.toList(),
            barLabels = labels(range, edges, zone, locale),
            fastestBps = fastest,
            topDevices = devices.values.sortedByDescending { it.bytes }.take(5),
            types = kinds.entries.sortedByDescending { it.value }.map { TypeStat(it.key, it.value) },
            resumed = moved.count { it.resumes > 0 },
            filesSkipped = inRange.sumOf { it.filesSkipped },
            bytesSkipped = inRange.sumOf { it.bytesSkipped },
            verified = inRange.sumOf { it.verified },
        )
    }

    /** "Photos", "Videos", "Audio", "Documents" or "Other", from the file's ending. */
    fun kindOf(name: String): String {
        val ext = name.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            in IMAGE -> "Photos"
            in VIDEO -> "Videos"
            in AUDIO -> "Audio"
            in DOCUMENT -> "Documents"
            else -> "Other"
        }
    }

    private fun calendar(ms: Long, zone: TimeZone) = Calendar.getInstance(zone).apply { timeInMillis = ms }

    private fun startOfDay(ms: Long, zone: TimeZone) = calendar(ms, zone).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun startOfMonth(ms: Long, zone: TimeZone) = startOfDay(ms, zone).apply { set(Calendar.DAY_OF_MONTH, 1) }

    /** Where each bar starts, plus the end of the last one: 7 days, 30 days, or up to 12 months. */
    private fun edges(range: StatsRange, now: Long, zone: TimeZone, all: List<HistoryEntry>): List<Long> {
        val days = when (range) { StatsRange.Week -> 7; StatsRange.Month -> 30; StatsRange.All -> 0 }
        if (days > 0) {
            val cal = startOfDay(now, zone).apply { add(Calendar.DAY_OF_YEAR, -(days - 1)) }
            return List(days + 1) { i -> cal.timeInMillis.also { if (i < days) cal.add(Calendar.DAY_OF_YEAR, 1) } }
        }
        // as many months as there is history for, at most 12, and at least 2 so the chart is not one block
        val first = startOfMonth(all.minOfOrNull { it.at } ?: now, zone).timeInMillis
        val cal = startOfMonth(now, zone)
        var months = 1
        while (months < 12 && cal.timeInMillis > first) {
            cal.add(Calendar.MONTH, -1)
            months++
        }
        if (months < 2) {
            cal.add(Calendar.MONTH, -1)
            months = 2
        }
        return List(months + 1) { i -> cal.timeInMillis.also { if (i < months) cal.add(Calendar.MONTH, 1) } }
    }

    private fun labels(range: StatsRange, edges: List<Long>, zone: TimeZone, locale: Locale): List<String> {
        val n = edges.size - 1
        fun format(pattern: String) = SimpleDateFormat(pattern, locale).apply { timeZone = zone }
        // a first letter is enough ("M", "T"), unless the language's names start alike (then the short name is shown)
        fun initials(pattern: String): List<String> {
            val f = format(pattern)
            val names = List(n) { f.format(edges[it]) }
            val first = names.map { it.take(1) }
            return if (first.toSet().size >= minOf(n, 3)) first else names
        }
        return when (range) {
            StatsRange.Week -> initials("EEE")
            StatsRange.Month -> format("d").let { f ->
                List(n) { if (it == 0 || it == n - 1 || (n - 1 - it) % 10 == 0) f.format(edges[it]) else "" }
            }
            StatsRange.All -> initials("MMM")
        }
    }
}
