package dev.khanlabs.lanshare.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Blanks the GPS location in a JPEG photo while it is read for sending, so the other device gets the picture without
 * where it was taken. The photo on this device is not touched.
 *
 * It works in place: the GPS entries and their values are overwritten with zeros and the GPS block is left as a valid
 * block with no entries, so the file keeps its exact length (the offer already named that length) and the rest of the
 * EXIF, such as the rotation, stays. Only the EXIF location is handled. The location can also sit in an XMP block or in a
 * maker note some cameras write, and other formats (HEIC, PNG, videos) are sent as they are.
 *
 * EXIF that can't be read is not left alone, since that could send the location after all: its segment is turned into
 * an APP15 segment, which every reader skips. The photo then loses its EXIF, rotation included, but not its length.
 */
object StripGps {
    private const val MAX_HEAD = 4 * 1024 * 1024
    private const val GPS_INFO_TAG = 0x8825
    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    /** True for a name that is a JPEG photo by its ending. */
    fun isJpegName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "jpe", "jfif")

    /** The same bytes as [input] with the EXIF location blanked, and the same length. Anything that is not a JPEG passes through. */
    fun wrap(input: InputStream): InputStream {
        val head = ByteArrayOutputStream()
        try {
            readHead(input, head)
        } catch (e: IOException) {
            input.close()
            throw e
        }
        return SequenceInputStream(ByteArrayInputStream(head.toByteArray()), input)
    }

    /** Reads up to the start of the picture data, writing it to [head] with the location blanked. What follows is left unread. */
    private fun readHead(input: InputStream, head: ByteArrayOutputStream) {
        val first = readBytes(input, 2)
        head.write(first)
        if (first.size < 2 || first[0] != 0xFF.toByte() || first[1] != 0xD8.toByte()) return // not a JPEG
        while (head.size() < MAX_HEAD) {
            var b = input.read()
            if (b < 0) return
            head.write(b)
            if (b != 0xFF) return // not a marker: leave the rest as it is
            while (b == 0xFF) { // fill bytes may come before the marker's number
                b = input.read()
                if (b < 0) return
                head.write(b)
            }
            val marker = b
            if (marker == 0xDA || marker == 0xD9) return // the picture data, or the end
            if (marker == 0x01 || marker in 0xD0..0xD7) continue // markers with no length
            val lengthBytes = readBytes(input, 2)
            head.write(lengthBytes)
            if (lengthBytes.size < 2) return
            val length = ((lengthBytes[0].toInt() and 0xFF) shl 8) or (lengthBytes[1].toInt() and 0xFF)
            if (length < 2) return
            val payload = readBytes(input, length - 2)
            if (marker == 0xE1 && payload.size == length - 2 && isExif(payload) && !blankGps(payload)) {
                // EXIF that can't be read: hide it from readers rather than send a location we could not find
                val bytes = head.toByteArray()
                bytes[bytes.size - 3] = 0xEF.toByte() // the marker's number sits before the two length bytes
                head.reset()
                head.write(bytes)
            }
            head.write(payload)
            if (payload.size < length - 2) return // the file ended early
        }
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    private fun isExif(payload: ByteArray): Boolean =
        payload.size >= EXIF_HEADER.size && EXIF_HEADER.indices.all { payload[it] == EXIF_HEADER[it] }

    /**
     * Zeros the GPS entries of an EXIF segment's payload ("Exif" and two zero bytes, then a TIFF block). True when it is
     * done or there was no GPS block; false when the block can't be read.
     */
    internal fun blankGps(p: ByteArray): Boolean {
        val tiff = EXIF_HEADER.size
        if (p.size < tiff + 8) return false
        val little = when {
            p[tiff] == 'I'.code.toByte() && p[tiff + 1] == 'I'.code.toByte() -> true
            p[tiff] == 'M'.code.toByte() && p[tiff + 1] == 'M'.code.toByte() -> false
            else -> return false
        }

        fun u16(at: Int): Int {
            if (at < 0 || at + 2 > p.size) throw IndexOutOfBoundsException()
            val a = p[at].toInt() and 0xFF
            val b = p[at + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }

        fun u32(at: Int): Long {
            if (at < 0 || at + 4 > p.size) throw IndexOutOfBoundsException()
            var v = 0L
            for (i in 0 until 4) {
                val byte = (p[at + i].toInt() and 0xFF).toLong()
                v = if (little) v or (byte shl (8 * i)) else (v shl 8) or byte
            }
            return v
        }

        fun zero(from: Int, count: Int) {
            if (from < 0 || count < 0 || from + count > p.size) throw IndexOutOfBoundsException()
            java.util.Arrays.fill(p, from, from + count, 0)
        }

        return try {
            if (u16(tiff + 2) != 42) return false
            val ifd0 = tiff + u32(tiff + 4).toInt()
            val entries = u16(ifd0)
            var gpsAt = -1
            for (i in 0 until entries) {
                val e = ifd0 + 2 + i * 12
                if (u16(e) == GPS_INFO_TAG) gpsAt = tiff + u32(e + 8).toInt()
            }
            if (gpsAt < 0) return true // no location in this photo
            val count = u16(gpsAt)
            for (j in 0 until count) {
                val e = gpsAt + 2 + j * 12
                val size = typeSize(u16(e + 2)) * u32(e + 4)
                if (size > 4) zero(tiff + u32(e + 8).toInt(), size.toInt()) // a value that is stored away from its entry
            }
            // the count, the entries and the pointer to a next block (if the block has room for it): an empty block
            zero(gpsAt, 2 + count * 12 + if (gpsAt + 2 + count * 12 + 4 <= p.size) 4 else 0)
            true
        } catch (e: IndexOutOfBoundsException) {
            false
        }
    }

    private fun typeSize(type: Int): Long = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 1
    }
}

/** This item with the EXIF location blanked as it is read, when it is a JPEG by its name; else the item itself. Same name and length. */
fun SendItem.withoutLocation(): SendItem =
    if (!StripGps.isJpegName(name)) this
    else SendItem(name, size, modified, preview, onRelease = { release() }) { StripGps.wrap(open()) }
