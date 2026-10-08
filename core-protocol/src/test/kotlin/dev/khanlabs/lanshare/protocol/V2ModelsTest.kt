package dev.khanlabs.lanshare.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V2ModelsTest {
    @Test
    fun readsTheSessionCreateExampleFromTheSpec() {
        val json = """
            {"session":"q3V9xY2kL0aB7cD4eF6gH8",
             "sender":{"id":"03705D25472018B381A1C98835FC818751F2397F51DE1A899FC366D204B8DF76","name":"Mueed's OnePlus","model":"android","app":"KhanLabs LAN Share Android 1.0","port":47820},
             "cert":"MIIBbjCCARSgAwIBAgICA+kwCgYIKoZIzj0EAwIw","nonce":"iW2Cyi9ssOUouhrR-B5oZA","proof":"MEUCIQDUcrGeTDPF8iRKKJqzWB3IETsrsCRFy1eq",
             "summary":{"count":3,"bytes":3800000,"names":["Holiday photos"]},
             "files":[{"id":"0","name":"Holiday photos/IMG_001.jpg","size":2000000,"mtime":1791200000000,"mime":"image/jpeg"},
                      {"id":"2","name":"Holiday photos/empty folder","size":0,"kind":"dir"}],
             "more":false,"text":"Gate B12, boarding 14:05","something_new":{"a":1}}
        """.trimIndent()
        val s = ProtocolJson.decodeFromString(SessionCreate.serializer(), json)
        assertEquals("q3V9xY2kL0aB7cD4eF6gH8", s.session)
        assertEquals(47820, s.sender.port)
        assertEquals(3L, s.summary.count)
        assertEquals(listOf("Holiday photos"), s.summary.names)
        assertEquals(2, s.files.size)
        assertEquals(1791200000000L, s.files[0].mtime)
        assertFalse(s.files[0].isDir)
        assertTrue(s.files[1].isDir)
        assertEquals("Gate B12, boarding 14:05", s.text)
    }

    @Test
    fun aSessionLeftWithoutFilesOrTextReadsAsEmpty() {
        val s = ProtocolJson.decodeFromString(SessionCreate.serializer(),
            """{"session":"a","sender":{"id":"x"},"cert":"c","nonce":"n","proof":"p","summary":{"count":0,"bytes":0}}""")
        assertTrue(s.files.isEmpty())
        assertFalse(s.more)
        assertNull(s.text)
    }

    @Test
    fun theStateUsesTheSpecsNamesAndWantedIsAllOrAList() {
        val state = SessionState("sid", "accepted", 4, 8_388_608, inOrder = false, manifestComplete = true,
            wanted = SessionState.wantedAll(),
            files = mapOf("0" to FileState("done", name = "a (1).jpg"), "1" to FileState("partial", chunks = listOf(listOf(0, 12), listOf(14, 15)))))
        val text = ProtocolJson.encodeToString(SessionState.serializer(), state)
        assertTrue("\"chunk_size\":8388608" in text, text)
        assertTrue("\"in_order\":false" in text, text)
        assertTrue("\"manifest_complete\":true" in text, text)
        assertTrue("\"wanted\":\"all\"" in text, text)
        assertTrue("\"chunks\":[[0,12],[14,15]]" in text, text)
        assertFalse("token" in text, "no null fields")
        val back = ProtocolJson.decodeFromString(SessionState.serializer(), text)
        assertNull(back.wantedIds)
        assertEquals("a (1).jpg", back.files!!["0"]!!.name)

        val some = state.copy(wanted = SessionState.wantedList(listOf("1", "5")))
        val again = ProtocolJson.decodeFromString(SessionState.serializer(), ProtocolJson.encodeToString(SessionState.serializer(), some))
        assertEquals(setOf("1", "5"), again.wantedIds)
    }

    @Test
    fun theInfoCarriesItsLimitsAsSnakeCase() {
        val info = InfoV2(id = "A".repeat(64), name = "Phone", model = "android", features = listOf("proof", "resume", "batch"),
            limits = V2Limits(4, 8_388_608, 1_048_576, 33_554_432, 1000, 5000), nonce = "iW2Cyi9ssOUouhrR-B5oZA")
        val text = ProtocolJson.encodeToString(InfoV2.serializer(), info)
        for (key in listOf("max_streams", "chunk_size", "batch_max_file", "batch_max_bytes", "batch_max_files", "manifest_page")) {
            assertTrue("\"$key\"" in text, key)
        }
        assertEquals(info, ProtocolJson.decodeFromString(InfoV2.serializer(), text))
    }

    @Test
    fun anErrorCanListTheMissingChunks() {
        val text = ProtocolJson.encodeToString(ApiError.serializer(), ApiError("missing_chunks", "Some chunks haven't arrived", missing = listOf(listOf(3, 5))))
        assertTrue("\"missing\":[[3,5]]" in text, text)
        assertFalse("\"state\"" in text)
    }

    // --- chunk sets

    @Test
    fun chunkRangesAreHalfOpenSortedAndMerged() {
        val set = ChunkSet()
        for (n in listOf(0L, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 14, 15, 3)) set.add(n)
        assertEquals(listOf(listOf(0L, 12L), listOf(14L, 16L)), set.ranges())
        assertEquals(14, set.count)
        assertTrue(11L in set)
        assertFalse(12L in set)
    }

    @Test
    fun theMissingChunksAreTheGapsBelowTheTotal() {
        val set = ChunkSet.fromRanges(listOf(listOf(0, 12), listOf(14, 15)))
        assertEquals(listOf(listOf(12L, 14L), listOf(15L, 20L)), set.missing(20))
        assertEquals(12L, set.firstMissing())
        assertFalse(set.isComplete(20))
        assertTrue(ChunkSet.fromRanges(listOf(listOf(0, 20))).isComplete(20))
        assertTrue(ChunkSet().isComplete(0), "a 0-byte file has no chunks")
        assertEquals(emptyList(), ChunkSet.fromRanges(listOf(listOf(0, 20))).missing(20))
    }

    @Test
    fun aRangeThatIsNotHalfOpenIsRefused() {
        assertFailsWith<IllegalArgumentException> { ChunkSet.fromRanges(listOf(listOf(5, 3))) }
        assertFailsWith<IllegalArgumentException> { ChunkSet.fromRanges(listOf(listOf(1))) }
        assertFailsWith<IllegalArgumentException> { ChunkSet.fromRanges(listOf(listOf(-1, 3))) }
    }

    @Test
    fun aChunkCanBeRemovedAgain() {
        val set = ChunkSet.fromRanges(listOf(listOf(0, 5)))
        set.remove(2)
        assertEquals(listOf(listOf(0L, 2L), listOf(3L, 5L)), set.ranges())
        set.clear()
        assertEquals(0, set.count)
    }

    // --- v2 names

    @Test
    fun v2RefusesOnlyTheNamesTheSpecSays() {
        assertNull(Names.problemV2("Holiday photos/day 2/IMG_002.jpg"))
        assertNull(Names.problemV2("a:b.txt"), "looser than v1: the receiver makes it safe")
        assertNull(Names.problemV2("CON.txt"))
        assertNull(Names.problemV2("emoji 📷/pic.jpg"))
        for (bad in listOf("", "/etc/passwd", "a\\b", "a//b", "a/./b", "a/../b", "..", "a/", "tab\there", "nul\u0000", "del\u007F")) {
            assertTrue(Names.problemV2(bad) != null, "should refuse '${bad.replace("\u0000", "\\0")}'")
        }
        assertTrue(Names.problemV2("x".repeat(256)) != null, "a part of 256 bytes")
        assertNull(Names.problemV2("x".repeat(255)))
        assertTrue(Names.problemV2("é".repeat(128)) != null, "128 two-byte letters are 256 bytes")
        assertTrue(Names.problemV2((1..60).joinToString("/") { "d".repeat(100) }) != null, "over 4,096 bytes in all")
    }

    @Test
    fun aV2NameIsMadeSafeForWindowsByPart() {
        assertEquals("a_b.txt", Names.safeFromV2("a:b.txt"))
        assertEquals("CON_.txt", Names.safeFromV2("CON.txt"))
        assertEquals("dir/sub_x/a_.txt", Names.safeFromV2("dir/sub:x/a?.txt"))
        assertEquals("notes", Names.safeFromV2("notes. "))
        assertEquals("Holiday photos/IMG_001.jpg", Names.safeFromV2("Holiday photos/IMG_001.jpg"))
    }
}
