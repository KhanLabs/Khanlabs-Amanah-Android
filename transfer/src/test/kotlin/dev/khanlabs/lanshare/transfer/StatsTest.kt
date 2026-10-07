package dev.khanlabs.lanshare.transfer

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis
    private val now = at(2026, 10, 7)

    private fun entry(
        id: String, incoming: Boolean, whenMs: Long, bytes: Long, state: String = "done", peer: String = "Phone",
        peerId: String = "", names: List<String> = listOf("a.jpg"), files: Int = names.size, peak: Long = 0,
        resumes: Int = 0, skipped: Int = 0, skippedBytes: Long = 0, verified: Int = 0, accepted: Long = 0, ended: Long = 0,
    ) = HistoryEntry(
        id, incoming, peer, state, files, bytes, whenMs, names, "", peerId = peerId, peakBps = peak, resumes = resumes,
        filesSkipped = skipped, bytesSkipped = skippedBytes, verified = verified, acceptedAt = accepted, endedAt = ended,
    )

    private fun week(list: List<HistoryEntry>) = Stats.compute(list, StatsRange.Week, now, utc, Locale.ENGLISH)

    @Test
    fun emptyHistoryIsEmpty() {
        val s = week(emptyList())
        assertTrue(s.isEmpty)
        assertEquals(7, s.bars.size)
        assertEquals(0L, s.total)
    }

    @Test
    fun weekHasSevenBarsEndingToday() {
        val s = week(listOf(
            entry("1", false, at(2026, 10, 7, 9), 100),
            entry("2", true, at(2026, 10, 7, 11), 50),
            entry("3", false, at(2026, 10, 6), 10),
            entry("4", false, at(2026, 9, 30), 5), // 7 days ago: before the window
        ))
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 10L, 150L), s.bars)
        // 7 October 2026 is a Wednesday, so the week runs Thursday to Wednesday
        assertEquals(listOf("T", "F", "S", "S", "M", "T", "W"), s.barLabels)
        assertEquals(110L, s.sent)
        assertEquals(50L, s.received)
        assertEquals(3, s.transfers)
    }

    @Test
    fun declinedAndFailedTransfersCountAsAskedForButMoveNothing() {
        val s = week(listOf(
            entry("1", false, at(2026, 10, 7), 100),
            entry("2", false, at(2026, 10, 7), 500, state = "failed"),
            entry("3", false, at(2026, 10, 7), 0, state = "declined"),
        ))
        assertEquals(3, s.transfers)
        assertEquals(1, s.finished)
        assertEquals(100L, s.total)
    }

    @Test
    fun aRenamedDeviceIsOneDevice() {
        val s = week(listOf(
            entry("1", false, at(2026, 10, 7), 100, peer = "New name", peerId = "ID1"),
            entry("2", false, at(2026, 10, 6), 300, peer = "Old name", peerId = "ID1"),
            entry("3", true, at(2026, 10, 6), 50, peer = "Other", peerId = "ID2"),
        ))
        assertEquals(listOf(DeviceStat("New name", 400, 2), DeviceStat("Other", 50, 1)), s.topDevices)
    }

    @Test
    fun entriesWithoutAnIdAreGroupedByName() {
        val s = week(listOf(entry("1", false, at(2026, 10, 7), 10, peer = "A"), entry("2", true, at(2026, 10, 7), 20, peer = "A")))
        assertEquals(listOf(DeviceStat("A", 30, 2)), s.topDevices)
    }

    @Test
    fun fastestUsesThePeakOrTheTimedAverage() {
        val s = week(listOf(
            entry("1", false, at(2026, 10, 7), 100, peak = 5_000_000),
            entry("2", true, at(2026, 10, 7), 80_000_000, accepted = 1_000, ended = 5_000), // 20 MB/s on average
        ))
        assertEquals(20_000_000L, s.fastestBps)
        assertEquals(0L, week(listOf(entry("1", true, at(2026, 10, 7), 10))).fastestBps)
    }

    @Test
    fun fileTypesComeFromTheEnding() {
        val s = week(listOf(entry("1", false, at(2026, 10, 7), 10,
            names = listOf("a.JPG", "b.mp4", "trip/c.pdf", "d.zip", "e.png", "noending"))))
        assertEquals(mapOf("Other" to 2, "Photos" to 2, "Videos" to 1, "Documents" to 1), s.types.associate { it.label to it.files })
    }

    @Test
    fun reliabilityNumbersAddUp() {
        val s = week(listOf(
            entry("1", false, at(2026, 10, 7), 100, resumes = 2, skipped = 3, skippedBytes = 900, verified = 4),
            entry("2", false, at(2026, 10, 6), 100, verified = 1),
        ))
        assertEquals(1, s.resumed)
        assertEquals(3, s.filesSkipped)
        assertEquals(900L, s.bytesSkipped)
        assertEquals(5, s.verified)
    }

    @Test
    fun monthHasThirtyBarsAndAllHasMonths() {
        val list = listOf(entry("1", false, at(2026, 10, 7), 100), entry("2", false, at(2026, 8, 15), 40))
        val month = Stats.compute(list, StatsRange.Month, now, utc, Locale.ENGLISH)
        assertEquals(30, month.bars.size)
        assertEquals(100L, month.bars.last())
        val all = Stats.compute(list, StatsRange.All, now, utc, Locale.ENGLISH)
        assertEquals(listOf(40L, 0L, 100L), all.bars) // August, September, October
        assertEquals(140L, all.total)
    }

    @Test
    fun allShowsAtLeastTwoMonths() {
        val all = Stats.compute(listOf(entry("1", false, at(2026, 10, 7), 100)), StatsRange.All, now, utc, Locale.ENGLISH)
        assertEquals(listOf(0L, 100L), all.bars)
    }
}
