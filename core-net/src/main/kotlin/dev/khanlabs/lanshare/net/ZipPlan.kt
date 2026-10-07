package dev.khanlabs.lanshare.net

import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Calendar
import java.util.zip.CRC32

/**
 * One file inside a ZIP. [size] and [crc] are what the file really holds, found by reading it once before the share
 * starts; [modified] is milliseconds since 1970. [open] is called again for every download.
 */
class ZipPart(val name: String, val size: Long, val crc: Long, val modified: Long, val open: () -> InputStream)

/** A file's bytes are not what they were when it was checked, so the length and checksum promised can't be met. */
class ChangedException(val name: String, why: String = "changed while it was being sent") : IOException("$name $why")

/**
 * A stored (not compressed) ZIP whose exact length is known before the first byte is sent, so a browser can show
 * real progress and can ask for the rest of it after a break. Every entry carries its real size and checksum in its
 * own header and has no data descriptor, so readers that stream the file from the front (most Android and phone
 * unzip apps) and readers that use the directory at the end both open it. The bytes are the same on every request,
 * so any range of them can be served. A size, offset or count past the classic limits moves to the ZIP64 form.
 *
 * While a whole file is written, its checksum is worked out again and its last piece is held back until it matches.
 * A file that changed ends the ZIP short, so the browser shows a failed download instead of a ZIP with a bad file in it.
 *
 * [limit] and [maxCount] are the classic limits; tests lower them to exercise ZIP64 without 4 GB of data.
 */
class ZipPlan(
    parts: List<ZipPart>,
    private val limit: Long = 0xFFFFFFFFL,
    private val maxCount: Int = 0xFFFF,
) {
    private val parts = parts.toList()
    private val times = this.parts.map { dosTime(it.modified) }
    private val names = this.parts.map { it.name.toByteArray(Charsets.UTF_8) }
    private val offsets = LongArray(this.parts.size)
    private val locals: List<ByteArray>
    private val centrals: List<ByteArray>
    private val tail: ByteArray
    private val cdOffset: Long
    private val cdSize: Long
    private val zip64End: Boolean
    val length: Long

    /**
     * A strong validator for the bytes of this ZIP: the same files in the same order give the same value, any change
     * gives another. A browser may ask for the rest of an interrupted download only when the answer carries one.
     */
    val etag: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in this.parts) {
            md.update("${p.name}\u0000${p.size}\u0000${p.crc}\u0000${p.modified}\u0000".toByteArray(Charsets.UTF_8))
        }
        "\"z-" + md.digest().joinToString("") { "%02x".format(it) }.take(32) + "\""
    }

    init {
        locals = this.parts.indices.map { local(it) }
        var offset = 0L
        for ((i, p) in this.parts.withIndex()) {
            offsets[i] = offset
            offset += locals[i].size + p.size
        }
        cdOffset = offset
        centrals = this.parts.indices.map { central(it) }
        cdSize = centrals.sumOf { it.size.toLong() }
        zip64End = this.parts.size >= maxCount || cdSize >= limit || cdOffset >= limit
        tail = end()
        length = cdOffset + cdSize + tail.size
    }

    private fun big(p: ZipPart) = p.size >= limit

    private fun buf(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** The extended timestamp field: the modified time in UTC seconds, so a gallery keeps a photo's date. */
    private fun ByteBuffer.putStamp(i: Int): ByteBuffer =
        putShort(0x5455).putShort(5).put(1).putInt((parts[i].modified / 1000).coerceIn(0L, 0xFFFFFFFFL).toInt())

    private fun local(i: Int): ByteArray {
        val p = parts[i]
        val zip64 = if (big(p)) 20 else 0
        val b = buf(30 + names[i].size + zip64 + STAMP)
        val sizeField = if (big(p)) -1 else p.size.toInt()
        b.putInt(0x04034B50).putShort((if (big(p)) 45 else 20).toShort()).putShort(UTF8).putShort(0)
        b.putShort(times[i].first.toShort()).putShort(times[i].second.toShort())
        b.putInt(p.crc.toInt()).putInt(sizeField).putInt(sizeField)
        b.putShort(names[i].size.toShort()).putShort((zip64 + STAMP).toShort())
        b.put(names[i])
        if (big(p)) b.putShort(1).putShort(16).putLong(p.size).putLong(p.size)
        b.putStamp(i)
        return b.array()
    }

    private fun central(i: Int): ByteArray {
        val p = parts[i]
        val offset = offsets[i]
        val bigOffset = offset >= limit
        val fields = (if (big(p)) 2 else 0) + (if (bigOffset) 1 else 0)
        val zip64 = if (fields > 0) 4 + 8 * fields else 0
        val need = if (big(p) || bigOffset) 45 else 20
        val size = if (big(p)) 0xFFFFFFFFL else p.size
        val b = buf(46 + names[i].size + zip64 + STAMP)
        b.putInt(0x02014B50).putShort(((3 shl 8) or need).toShort()).putShort(need.toShort())
        b.putShort(UTF8).putShort(0)
        b.putShort(times[i].first.toShort()).putShort(times[i].second.toShort())
        b.putInt(p.crc.toInt()).putInt(size.toInt()).putInt(size.toInt())
        b.putShort(names[i].size.toShort()).putShort((zip64 + STAMP).toShort()).putShort(0).putShort(0).putShort(0)
        b.putInt(UNIX_FILE).putInt(if (bigOffset) -1 else offset.toInt())
        b.put(names[i])
        if (fields > 0) {
            b.putShort(1).putShort((8 * fields).toShort())
            if (big(p)) b.putLong(p.size).putLong(p.size)
            if (bigOffset) b.putLong(offset)
        }
        b.putStamp(i)
        return b.array()
    }

    private fun end(): ByteArray {
        val n = parts.size
        if (!zip64End) {
            return buf(22).putInt(0x06054B50).putShort(0).putShort(0).putShort(n.toShort()).putShort(n.toShort())
                .putInt(cdSize.toInt()).putInt(cdOffset.toInt()).putShort(0).array()
        }
        val b = buf(56 + 20 + 22)
        b.putInt(0x06064B50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
            .putLong(n.toLong()).putLong(n.toLong()).putLong(cdSize).putLong(cdOffset)
        b.putInt(0x07064B50).putInt(0).putLong(cdOffset + cdSize).putInt(1)
        b.putInt(0x06054B50).putShort(0).putShort(0).putShort(-1).putShort(-1).putInt(-1).putInt(-1).putShort(0)
        return b.array()
    }

    /**
     * Writes bytes [start] to [endInclusive] of the ZIP, all of it by default. Throws [ChangedException] when a file
     * no longer holds what it held when it was checked; an [IOException] from [out] means the other side left.
     * A file is only checked when all of it is written, so a resumed download of a file trusts the first check.
     */
    fun writeTo(out: OutputStream, start: Long = 0, endInclusive: Long = length - 1) {
        require(start in 0..endInclusive && endInclusive < length) { "Range outside the ZIP" }
        fun put(bytes: ByteArray, at: Long) {
            val from = maxOf(start, at)
            val to = minOf(endInclusive, at + bytes.size - 1)
            if (from <= to) out.write(bytes, (from - at).toInt(), (to - from + 1).toInt())
        }
        for ((i, p) in parts.withIndex()) {
            val at = offsets[i]
            val dataAt = at + locals[i].size
            if (at > endInclusive) return
            if (dataAt + p.size <= start) continue
            put(locals[i], at)
            val from = maxOf(start, dataAt)
            val to = minOf(endInclusive, dataAt + p.size - 1)
            if (from <= to) copyChecked(p.name, p.open, p.size, p.crc, from - dataAt, to - from + 1, out)
            else if (p.size == 0L && start <= dataAt && dataAt <= endInclusive) copyChecked(p.name, p.open, 0, p.crc, 0, 0, out)
        }
        var at = cdOffset
        for (c in centrals) {
            put(c, at)
            at += c.size
        }
        put(tail, at)
    }

    private companion object {
        const val UNIX_FILE = (0b1000000110100100 shl 16) // 0100644 regular file, so unzip on Unix makes readable files
        const val STAMP = 9 // extended timestamp field: id, size, flags, modified time
        const val UTF8: Short = 0x0800 // names are UTF-8

        /** MS-DOS (time, date) for a moment, in the local time zone. The DOS clock starts in 1980. */
        fun dosTime(millis: Long): Pair<Int, Int> {
            val c = Calendar.getInstance().apply { timeInMillis = maxOf(millis, 315_532_800_000L) }
            val year = c.get(Calendar.YEAR).coerceIn(1980, 2107)
            val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
            val date = ((year - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
            return time to date
        }
    }
}

private const val CHUNK = 256 * 1024

/**
 * Copies [count] bytes of a file, from [skip], to [out]. When that is the whole file ([size] bytes), its length must
 * still be [size] and, with a [crc], its checksum must still match; the last piece is held back until it does. A
 * file that changed ends the copy with a [ChangedException] before its last piece, so the other side gets a short
 * download and never a complete bad one. Only reads of the file are turned into [ChangedException]; a failed write
 * means the other side left.
 */
internal fun copyChecked(name: String, open: () -> InputStream, size: Long, crc: Long?, skip: Long, count: Long, out: OutputStream) {
    val whole = skip == 0L && count == size
    val input = try {
        open()
    } catch (e: IOException) {
        throw ChangedException(name, "could not be opened (${e.message})")
    }
    input.use { stream ->
        fun read(into: ByteArray, len: Int): Int = try {
            stream.read(into, 0, len)
        } catch (e: IOException) {
            throw ChangedException(name, "could not be read (${e.message})")
        }
        var held = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var heldLen = 0
        val sum = CRC32()
        var toSkip = skip
        if (toSkip > 0 && stream is FileInputStream) { // a file can be jumped into; a pipe can't
            try {
                stream.channel.position(toSkip)
                toSkip = 0
            } catch (e: IOException) {
                // not seekable: read through instead
            }
        }
        while (toSkip > 0) {
            val n = read(next, minOf(next.size.toLong(), toSkip).toInt())
            if (n < 0) throw ChangedException(name, "is shorter than it was")
            toSkip -= n
        }
        var left = count
        while (left > 0) {
            val n = read(next, minOf(next.size.toLong(), left).toInt())
            if (n < 0) throw ChangedException(name, "is shorter than it was")
            left -= n
            if (whole) sum.update(next, 0, n)
            if (heldLen > 0) out.write(held, 0, heldLen)
            val t = held; held = next; next = t
            heldLen = n
        }
        if (whole) {
            if (read(next, 1) >= 0) throw ChangedException(name, "is longer than it was")
            if (crc != null && sum.value != crc) throw ChangedException(name, "no longer matches its checksum")
        }
        if (heldLen > 0) out.write(held, 0, heldLen)
    }
}
