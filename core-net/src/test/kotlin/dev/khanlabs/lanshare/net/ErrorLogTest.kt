package dev.khanlabs.lanshare.net

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorLogTest {
    private val noon = 1_790_000_000_000L

    private fun tmp(): File = Files.createTempFile("errlog", ".txt").toFile().also { it.delete(); it.deleteOnExit() }

    @Test
    fun linesAreStampedAndKeptOnOneLine() {
        val log = ErrorLog(clock = { noon })
        log.add("send to Khan failed:\nconnection reset\r\n(SocketException)")
        val line = log.tail(5).single()
        assertTrue(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d send to Khan failed: connection reset \(SocketException\)""").matches(line), line)
    }

    @Test
    fun onlyTheLastLinesAreKept() {
        val log = ErrorLog(keep = 3, clock = { noon })
        for (i in 1..10) log.add("event $i")
        assertEquals(listOf("event 8", "event 9", "event 10"), log.tail(10).map { it.substringAfter(' ').substringAfter(' ') })
        assertEquals(2, log.tail(2).size)
    }

    @Test
    fun aLongLineIsCut() {
        val log = ErrorLog(clock = { noon })
        log.add("x".repeat(5000))
        assertTrue(log.tail(1).single().length < 440)
    }

    @Test
    fun theLogSurvivesARestartAndStaysShort() {
        val f = tmp()
        val a = ErrorLog(f, keep = 4, clock = { noon })
        for (i in 1..30) a.add("event $i")
        val lines = f.readLines()
        assertTrue(lines.size <= 8, "the file is rewritten before it grows past twice the limit, was ${lines.size}")
        val b = ErrorLog(f, keep = 4, clock = { noon })
        assertEquals(listOf("event 27", "event 28", "event 29", "event 30"), b.tail(10).map { it.substringAfter(' ').substringAfter(' ') })
    }

    @Test
    fun clearEmptiesTheLogAndTheFile() {
        val f = tmp()
        val log = ErrorLog(f, clock = { noon })
        log.add("one")
        log.clear()
        assertTrue(log.tail(5).isEmpty())
        assertFalse(f.exists())
    }

    @Test
    fun theReportHasTheFactsAndTheLatestLines() {
        val log = (1..150).map { "line $it" }
        val text = ErrorReport.build("Amanah Android 1.0", "Android 14 (API 34), Test Phone", listOf("Receiving" to "on", "Port" to "47820"), log)
        val rows = text.lines()
        assertEquals("Amanah Android 1.0", rows[0])
        assertEquals("Android 14 (API 34), Test Phone", rows[1])
        assertEquals("Receiving: on", rows[2])
        assertEquals("Port: 47820", rows[3])
        assertTrue("--- Errors (last 100 lines) ---" in rows)
        assertEquals("line 150", rows.last())
        assertFalse("line 50" in rows)
    }

    @Test
    fun anEmptyLogSaysSo() {
        val text = ErrorReport.build("Amanah", "Android", emptyList(), emptyList())
        assertTrue(text.endsWith("(none)"))
    }
}
