package dev.khanlabs.lanshare.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StripGpsTest {
    private val latitude = "LATITUDE-24-BYTES-XXXX".toByteArray().copyOf(24) // 3 rationals: a recognisable stand-in
    private val longitude = "LONGITUDE-24-BYTES-YYY".toByteArray().copyOf(24)

    private class Writer(val little: Boolean) {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            if (little) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) } else { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        }
        fun u32(v: Int) {
            if (little) for (i in 0 until 4) out.write((v shr (8 * i)) and 0xFF) else for (i in 3 downTo 0) out.write((v shr (8 * i)) and 0xFF)
        }
        fun bytes(b: ByteArray) = out.write(b)
        fun entry(tag: Int, type: Int, count: Int, value: Int) { u16(tag); u16(type); u32(count); u32(value) }
    }

    /** The Exif segment's payload: a TIFF block with an orientation of 6 and, when [withGps], a GPS block with a latitude and a longitude. */
    private fun exifPayload(little: Boolean, withGps: Boolean = true, gpsOffsetOverride: Int? = null): ByteArray {
        val w = Writer(little)
        w.bytes("Exif".toByteArray()); w.out.write(0); w.out.write(0)
        w.bytes(if (little) "II".toByteArray() else "MM".toByteArray())
        w.u16(42); w.u32(8)
        // IFD0 at 8: orientation, and the pointer to the GPS block at 38 (or none)
        val entries = if (withGps) 2 else 1
        w.u16(entries)
        w.entry(0x0112, 3, 1, if (little) 6 else 6 shl 16) // a SHORT stored in the first 2 bytes of the value field
        if (withGps) w.entry(0x8825, 4, 1, gpsOffsetOverride ?: 38)
        w.u32(0)
        if (withGps) {
            // GPS block at 38: 4 entries, latitude and longitude stored at 92 and 116
            w.u16(4)
            w.entry(0x0001, 2, 2, 'N'.code shl (if (little) 0 else 24))
            w.entry(0x0002, 5, 3, 92)
            w.entry(0x0003, 2, 2, 'E'.code shl (if (little) 0 else 24))
            w.entry(0x0004, 5, 3, 116)
            w.u32(0)
            w.bytes(latitude); w.bytes(longitude)
        }
        return w.out.toByteArray()
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(marker)
        val length = payload.size + 2
        out.write(length shr 8); out.write(length and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private val picture = Random(7).nextBytes(5000).also { for (i in it.indices) if (it[i] == 0xFF.toByte()) it[i] = 0x11 }

    private fun jpeg(exif: ByteArray?, tail: ByteArray = picture): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(0xD8)
        out.write(segment(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1)))
        if (exif != null) out.write(segment(0xE1, exif))
        out.write(segment(0xDB, ByteArray(65) { it.toByte() }))
        out.write(segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)))
        out.write(tail)
        out.write(0xFF); out.write(0xD9)
        return out.toByteArray()
    }

    private fun strip(data: ByteArray): ByteArray = StripGps.wrap(ByteArrayInputStream(data)).use { it.readBytes() }

    private fun contains(data: ByteArray, needle: ByteArray): Boolean =
        (0..data.size - needle.size).any { i -> needle.indices.all { data[i + it] == needle[it] } }

    @Test
    fun theLocationIsGoneAndTheLengthIsTheSame() {
        for (little in listOf(true, false)) {
            val input = jpeg(exifPayload(little))
            assertTrue(contains(input, latitude) && contains(input, longitude), "the test photo has a location")
            val out = strip(input)
            assertEquals(input.size, out.size, "same length, little=$little")
            assertFalse(contains(out, latitude), "latitude, little=$little")
            assertFalse(contains(out, longitude), "longitude, little=$little")
        }
    }

    @Test
    fun everythingElseIsUntouched() {
        for (little in listOf(true, false)) {
            val exif = exifPayload(little)
            val input = jpeg(exif)
            val out = strip(input)
            // the same photo with the GPS block (38 to 92 in the TIFF block, then the two values) zeroed by hand
            val expected = input.copyOf()
            val exifAt = (0 until input.size - 6).first { i -> String(input, i, 4) == "Exif" }
            val tiff = exifAt + 6
            java.util.Arrays.fill(expected, tiff + 38, tiff + 38 + 2 + 4 * 12 + 4, 0)
            java.util.Arrays.fill(expected, tiff + 92, tiff + 92 + 48, 0)
            assertContentEquals(expected, out, "little=$little")
        }
    }

    @Test
    fun theRotationStaysAndTheGpsBlockIsEmpty() {
        val out = strip(jpeg(exifPayload(little = true)))
        val exifAt = (0 until out.size - 6).first { i -> String(out, i, 4) == "Exif" }
        val tiff = exifAt + 6
        // IFD0 still has both entries and the orientation says 6
        assertEquals(2, (out[tiff + 8].toInt() and 0xFF))
        assertEquals(0x12, out[tiff + 10].toInt() and 0xFF)
        assertEquals(1, out[tiff + 11].toInt() and 0xFF)
        assertEquals(6, out[tiff + 18].toInt() and 0xFF)
        // the pointer to the GPS block is kept, and the block it points at has no entries
        assertEquals(38, out[tiff + 8 + 2 + 12 + 8].toInt() and 0xFF)
        assertEquals(0, out[tiff + 38].toInt() and 0xFF)
        assertEquals(0, out[tiff + 39].toInt() and 0xFF)
    }

    @Test
    fun aPhotoWithoutALocationIsReturnedAsItWas() {
        val input = jpeg(exifPayload(little = true, withGps = false))
        assertContentEquals(input, strip(input))
        val noExif = jpeg(null)
        assertContentEquals(noExif, strip(noExif))
    }

    @Test
    fun exifThatCantBeReadIsHiddenInsteadOfSentWithItsLocation() {
        // the pointer to the GPS block points far outside the segment
        val input = jpeg(exifPayload(little = true, gpsOffsetOverride = 100_000))
        val out = strip(input)
        assertEquals(input.size, out.size)
        val at = (0 until input.size - 1).first { i -> input[i] == 0xFF.toByte() && input[i + 1] == 0xE1.toByte() }
        assertEquals(0xEF.toByte(), out[at + 1], "the segment is now APP15")
        // all other bytes are the same
        for (i in input.indices) if (i != at + 1) assertEquals(input[i], out[i], "byte $i")
    }

    @Test
    fun somethingThatIsNotAJpegPassesThroughWhole() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + Random(3).nextBytes(100_000)
        assertContentEquals(png, strip(png))
        assertContentEquals(ByteArray(0), strip(ByteArray(0)))
        assertContentEquals(byteArrayOf(0xFF.toByte()), strip(byteArrayOf(0xFF.toByte())))
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun aBigPhotoStreamsThroughAndTheDataAfterTheHeaderIsNotChanged() {
        val big = Random(11).nextBytes(3_000_000).also { for (i in it.indices) if (it[i] == 0xFF.toByte()) it[i] = 0x22 }
        val input = jpeg(exifPayload(little = false), tail = big)
        val stream = StripGps.wrap(ByteArrayInputStream(input))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1000) // small reads, as the upload does
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        stream.close()
        val result = out.toByteArray()
        assertEquals(input.size, result.size)
        assertContentEquals(input.copyOfRange(input.size - big.size - 2, input.size), result.copyOfRange(result.size - big.size - 2, result.size))
    }

    @Test
    fun aNameDecidesWhichItemsAreChecked() {
        assertTrue(StripGps.isJpegName("IMG_1.JPG"))
        assertTrue(StripGps.isJpegName("trip/day 1/a.jpeg"))
        assertFalse(StripGps.isJpegName("clip.mp4"))
        assertFalse(StripGps.isJpegName("photo.heic"))
        assertFalse(StripGps.isJpegName("jpg"))
    }

    @Test
    fun anItemKeepsItsNameLengthAndReleaseWhenWrapped() {
        val input = jpeg(exifPayload(little = true))
        val released = AtomicInteger()
        val item = SendItem("IMG_1.jpg", input.size.toLong(), 123L, "content://x", onRelease = { released.incrementAndGet() }) { ByteArrayInputStream(input) }
        val wrapped = item.withoutLocation()
        assertEquals("IMG_1.jpg", wrapped.name)
        assertEquals(input.size.toLong(), wrapped.size)
        assertEquals(123L, wrapped.modified)
        assertEquals("content://x", wrapped.preview)
        val first = wrapped.open().use { it.readBytes() }
        val second = wrapped.open().use { it.readBytes() } // a try again reads it again from the start
        assertContentEquals(first, second)
        assertFalse(contains(first, latitude))
        wrapped.release()
        wrapped.release()
        assertEquals(1, released.get(), "the original is released once")
        val video = SendItem("clip.mp4", 3) { ByteArrayInputStream(ByteArray(3)) }
        assertSame(video, video.withoutLocation())
    }

    @Test
    fun theWrappedStreamClosesTheSourceItReadFrom() {
        var closed = false
        val source = object : InputStream() {
            val inner = ByteArrayInputStream(jpeg(exifPayload(little = true)))
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() { closed = true }
        }
        StripGps.wrap(source).close()
        assertTrue(closed)
    }
}
