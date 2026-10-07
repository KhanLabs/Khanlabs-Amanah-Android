package dev.khanlabs.lanshare.net

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceivedIndexTest {
    private lateinit var dir: File
    private lateinit var file: File

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("klshare-index-").toFile()
        file = File(dir, "received.tsv")
    }

    @AfterTest
    fun stop() {
        dir.deleteRecursively()
    }

    @Test
    fun keepsRecordsAcrossRestarts() {
        ReceivedIndex(file).apply {
            add("a.txt", "a.txt", 10, 1_700_000_000)
            add("pics/b.jpg", "pics/b (1).jpg", 20, null)
        }
        val again = ReceivedIndex(file)
        assertEquals(1_700_000_000L, again.bySaved("a.txt")?.mtime)
        val b = again.bySaved("pics/b (1).jpg")!!
        assertEquals("pics/b.jpg", b.offered)
        assertEquals(20L, b.size)
        assertNull(b.mtime)
    }

    @Test
    fun findsTheNewestRecordByTheSendersName() {
        val s = ReceivedIndex(file)
        s.add("a.txt", "a.txt", 10, 100)
        s.add("a.txt", "a (1).txt", 11, 200)
        assertEquals("a (1).txt", s.byOffered("a.txt")?.saved)
        assertNull(s.byOffered("other.txt"))
    }

    @Test
    fun savingTheSameNameAgainReplacesTheRecord() {
        val s = ReceivedIndex(file)
        s.add("a.txt", "a.txt", 10, 100)
        s.add("a.txt", "a.txt", 12, 300)
        val again = ReceivedIndex(file).bySaved("a.txt")!!
        assertEquals(12L, again.size)
        assertEquals(300L, again.mtime)
    }

    @Test
    fun dropsTheOldestWhenFull() {
        val s = ReceivedIndex(file, limit = 10)
        for (i in 1..11) s.add("f$i", "f$i", i.toLong(), null)
        assertNull(s.bySaved("f1"))
        assertEquals(11L, s.bySaved("f11")?.size)
        val again = ReceivedIndex(file, limit = 10)
        assertNull(again.bySaved("f1"))
        assertEquals(11L, again.bySaved("f11")?.size)
        assertTrue(file.readLines().size <= 10)
    }

    @Test
    fun ignoresBrokenLines() {
        file.writeText("junk\nok\tok\t5\t7\nbad\tbad\tx\t1\n\tno\t1\t1\nhalf\thalf\t3\t", Charsets.UTF_8)
        val s = ReceivedIndex(file)
        assertEquals(5L, s.bySaved("ok")?.size)
        assertNull(s.bySaved("junk"))
        assertNull(s.bySaved("bad"))
        assertEquals(3L, s.bySaved("half")?.size)
    }
}
