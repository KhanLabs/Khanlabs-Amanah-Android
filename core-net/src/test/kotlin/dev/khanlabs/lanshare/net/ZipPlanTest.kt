package dev.khanlabs.lanshare.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The stored ZIP of Share to anyone: exact length, valid for a real unzip, ZIP64 forms, and cut short on change. */
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
    private val data = mapOf(
        "top/a.txt" to "alpha".toByteArray(),
        "top/sub/b.bin" to random.nextBytes(70_000),
        "note ü.txt" to "héllo".toByteArray(),
        "empty.txt" to ByteArray(0),
    )

    private fun parts(): List<ZipPart> = data.map { (name, bytes) -> ZipPart(name, bytes.size.toLong(), 1_700_000_000_000L) { ByteArrayInputStream(bytes) } }

    private fun build(plan: ZipPlan): ByteArray = ByteArrayOutputStream().also { plan.writeTo(it) }.toByteArray()

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
    fun zip64IsUsedWhenASizePassesTheLimit() {
        val bytes = check(ZipPlan(parts(), limit = 100)) // the 70 kB file is now "big", and so is the directory offset
        assertTrue(contains(bytes, byteArrayOf(0x50, 0x4b, 0x06, 0x06)))
    }

    @Test
    fun zip64IsUsedWhenThereAreManyEntries() {
        val bytes = check(ZipPlan(parts(), maxCount = 2))
        assertTrue(contains(bytes, byteArrayOf(0x50, 0x4b, 0x06, 0x07)))
    }

    @Test
    fun aFileThatChangedSizeCutsTheZipShort() {
        val grown = ZipPart("a.txt", 5, 0) { ByteArrayInputStream("alpha and more".toByteArray()) }
        assertFailsWith<ChangedException> { build(ZipPlan(listOf(grown))) }
        val shrunk = ZipPart("a.txt", 5, 0) { ByteArrayInputStream("al".toByteArray()) }
        assertFailsWith<ChangedException> { build(ZipPlan(listOf(shrunk))) }
    }

    @Test
    fun aFileThatCannotBeOpenedCutsTheZipShort() {
        val gone = ZipPart("a.txt", 5, 0) { throw java.io.FileNotFoundException("a.txt") }
        assertFailsWith<ChangedException> { build(ZipPlan(listOf(gone))) }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
