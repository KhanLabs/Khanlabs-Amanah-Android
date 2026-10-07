package dev.khanlabs.lanshare.transfer

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryTest {
    private lateinit var dir: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("history-test").toFile()
        file = File(dir, "history.json")
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun entry(id: String, items: List<HistoryFile> = emptyList()) = HistoryEntry(
        id, false, "Laptop", "done", items.size, items.sumOf { it.size }, 1_000L, items.map { it.name }, "", items = items,
    )

    @Test
    fun theFilesOfAnEntryComeBackWithTheirSizeAndState() {
        val items = listOf(HistoryFile("docs/a.txt", 5, "done"), HistoryFile("docs/b.txt", 7, "skipped"), HistoryFile("c.bin", 9, "failed"))
        History(file).add(entry("out:1", items))
        val back = History(file).all.single()
        assertEquals(items, back.items)
    }

    @Test
    fun anEntryWrittenBeforeFilesWereKeptLoadsWithNoItems() {
        file.writeText(
            """[{"id":"in:old","in":true,"peer":"Phone","state":"done","files":1,"bytes":3,"at":5,"names":["a.jpg"],"text":"","note":""}]""",
        )
        val e = History(file).all.single()
        assertEquals("in:old", e.id)
        assertEquals(listOf("a.jpg"), e.names)
        assertTrue(e.items.isEmpty())
    }

    @Test
    fun aVeryLargeSendKeepsTheFirstHundredFilesOnly() {
        val many = (1..History.MAX_ITEMS + 50).map { HistoryFile("f$it.jpg", it.toLong(), "done") }
        val h = History(file)
        h.add(entry("out:big", many))
        assertEquals(History.MAX_ITEMS, h.all.single().items.size)
        assertEquals("f1.jpg", h.all.single().items.first().name)
        assertEquals(History.MAX_ITEMS, History(file).all.single().items.size)
    }

    @Test
    fun anEntryAddedAgainWithTheSameIdReplacesTheOldOne() {
        val h = History(file)
        h.add(entry("out:1", listOf(HistoryFile("a", 1, "failed"))))
        h.add(entry("out:1", listOf(HistoryFile("a", 1, "done"))))
        assertEquals(1, h.all.size)
        assertEquals("done", h.all.single().items.single().state)
    }
}
