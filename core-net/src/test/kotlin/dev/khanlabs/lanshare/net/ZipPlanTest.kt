package dev.khanlabs.lanshare.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The stored ZIP of Share to anyone: exact length, valid for a real unzip, valid for a streaming unzip that never
 * looks at the directory at the end, ZIP64 forms, any range of it, and cut short when a file is not what it was.
 */
class ZipPlanTest {
    private lateinit var tmp: File

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-zip-").toFile()
    }

    @AfterTest
    fun stop() {
        tmp.deleteRecursively()
    }

    private val random = Random(7)
    private val data = linkedMapOf(
        "top/a.txt" to "alpha".toByteArray(),
        "top/sub/b.bin" to random.nextBytes(70_000),
        "note ü.txt" to "héllo".toByteArray(),
        "empty.txt" to ByteArray(0),
        "video.mp4" to random.nextBytes(300_000),
    )

    private fun crc(bytes: ByteArray) = CRC32().also { it.update(bytes) }.value

    private fun part(name: String, bytes: ByteArray, open: () -> InputStream = { ByteArrayInputStream(bytes) }) =
        ZipPart(name, bytes.size.toLong(), crc(bytes), 1_700_000_000_000L, open)

    private fun parts(): List<ZipPart> = data.map { (name, bytes) -> part(name, bytes) }

    private fun build(plan: ZipPlan): ByteArray = ByteArrayOutputStream().also { plan.writeTo(it) }.toByteArray()

    /** Reads the ZIP with the directory at the end, and again from the front without it. Both must give the files back. */
    private fun check(plan: ZipPlan): ByteArray {
        val bytes = build(plan)
        assertEquals(plan.length, bytes.size.toLong(), "the promised Content-Length is exact")
        val file = File(tmp, "out.zip").also { it.writeBytes(bytes) }
        ZipFile(file).use { zip ->
            assertEquals(data.keys, zip.entries().asSequence().map { it.name }.toSet())
            for ((name, expected) in data) {
                assertContentEquals(expected, zip.getInputStream(zip.getEntry(name)).readBytes(), name)
                assertEquals(expected.size.toLong(), zip.getEntry(name).size)
            }
        }
        val streamed = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                streamed[e.name] = z.readBytes()
            }
        }
        assertEquals(data.keys.toList(), streamed.keys.toList(), "a streaming unzip sees every file, in order")
        for ((name, expected) in data) assertContentEquals(expected, streamed[name], "streamed $name")
        return bytes
    }

    @Test
    fun theLengthIsExactAndTheZipIsValid() {
        check(ZipPlan(parts()))
    }

    @Test
    fun theClassicFormIsUsedForSmallZips() {
        val bytes = check(ZipPlan(parts()))
        assertTrue(!contains(bytes, byteArrayOf(0x50, 0x4b, 0x06, 0x06)), "no ZIP64 end record")
    }

    @Test
    fun noEntryHasADataDescriptor() {
        val bytes = build(ZipPlan(parts()))
        assertTrue(!contains(bytes, byteArrayOf(0x50, 0x4b, 0x07, 0x08)), "no data descriptor signature")
        // every local header says its real size and checksum, and its flags are only "UTF-8 names"
        var at = 0
        var seen = 0
        while (u32(bytes, at) == 0x04034B50L) {
            assertEquals(0x0800, u16(bytes, at + 6), "flags")
            assertEquals(0, u16(bytes, at + 8), "stored")
            val name = String(bytes, at + 30, u16(bytes, at + 26), Charsets.UTF_8)
            val expected = data.getValue(name)
            assertEquals(crc(expected), u32(bytes, at + 14), "crc of $name")
            assertEquals(expected.size.toLong(), u32(bytes, at + 18), "size of $name")
            at += 30 + u16(bytes, at + 26) + u16(bytes, at + 28) + expected.size
            seen++
        }
        assertEquals(data.size, seen)
        assertEquals(0x02014B50L, u32(bytes, at), "the directory follows the last file exactly")
    }

    @Test
    fun zip64IsUsedWhenASizePassesTheLimit() {
        val bytes = check(ZipPlan(parts(), limit = 100)) // the big files are now "big", and so is the directory offset
        assertTrue(contains(bytes, byteArrayOf(0x50, 0x4b, 0x06, 0x06)))
    }

    @Test
    fun zip64IsUsedWhenThereAreManyEntries() {
        val bytes = check(ZipPlan(parts(), maxCount = 2))
        assertTrue(contains(bytes, byteArrayOf(0x50, 0x4b, 0x06, 0x07)))
    }

    @Test
    fun zip64IsUsedAtExactlyTheEntryLimit() {
        val bytes = check(ZipPlan(parts(), maxCount = data.size)) // a count of 0xFFFF would read as "look for ZIP64"
        assertTrue(contains(bytes, byteArrayOf(0x50, 0x4b, 0x06, 0x07)))
    }

    @Test
    fun theModifiedTimeIsKept() {
        val file = File(tmp, "t.zip").also { it.writeBytes(build(ZipPlan(parts()))) }
        ZipFile(file).use { zip ->
            assertEquals(Instant.ofEpochSecond(1_700_000_000), zip.getEntry("top/a.txt").lastModifiedTime.toInstant())
        }
    }

    @Test
    fun anyRangeIsTheSameBytesAsTheWholeZip() {
        for (plan in listOf(ZipPlan(parts()), ZipPlan(parts(), limit = 100))) {
            val whole = build(plan)
            val r = Random(3)
            repeat(60) {
                val a = r.nextLong(plan.length)
                val b = minOf(plan.length - 1, a + r.nextLong(0, 200_000))
                val out = ByteArrayOutputStream().also { plan.writeTo(it, a, b) }.toByteArray()
                assertContentEquals(whole.copyOfRange(a.toInt(), b.toInt() + 1), out, "range $a-$b")
            }
            val last = ByteArrayOutputStream().also { plan.writeTo(it, plan.length - 1, plan.length - 1) }.toByteArray()
            assertContentEquals(whole.copyOfRange(whole.size - 1, whole.size), last)
        }
    }

    @Test
    fun aFileThatChangedSizeCutsTheZipShort() {
        val grown = ZipPart("a.txt", 5, crc("alpha".toByteArray()), 0) { ByteArrayInputStream("alpha and more".toByteArray()) }
        assertFailsWith<ChangedException> { build(ZipPlan(listOf(grown))) }
        val shrunk = ZipPart("a.txt", 5, crc("alpha".toByteArray()), 0) { ByteArrayInputStream("al".toByteArray()) }
        assertFailsWith<ChangedException> { build(ZipPlan(listOf(shrunk))) }
    }

    @Test
    fun aFileWithTheSameSizeButOtherBytesIsNeverSentWhole() {
        val good = random.nextBytes(200_000)
        val bad = good.clone().also { it[199_999] = (it[199_999] + 1).toByte() } // only the very last byte differs
        val plan = ZipPlan(listOf(part("a.bin", good) { ByteArrayInputStream(bad) }))
        val out = ByteArrayOutputStream()
        assertFailsWith<ChangedException> { plan.writeTo(out) }
        assertTrue(out.size() < plan.length - 100_000, "the last piece was held back, so the browser sees a short download")
    }

    @Test
    fun aFileThatCannotBeOpenedOrReadCutsTheZipShort() {
        val gone = part("a.txt", "alpha".toByteArray()) { throw java.io.FileNotFoundException("a.txt") }
        assertFailsWith<ChangedException> { build(ZipPlan(listOf(gone))) }
        val broken = part("a.txt", "alpha".toByteArray()) {
            object : InputStream() {
                override fun read(): Int = throw IOException("grant revoked")
            }
        }
        assertFailsWith<ChangedException> { build(ZipPlan(listOf(broken))) }
    }

    @Test
    fun aFailedWriteIsNotBlamedOnTheFile() {
        val plan = ZipPlan(parts())
        val gone = object : java.io.OutputStream() {
            override fun write(b: Int) = throw java.net.SocketException("Broken pipe")
        }
        assertFailsWith<java.net.SocketException> { plan.writeTo(gone) }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
}
