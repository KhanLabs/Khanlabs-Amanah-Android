package dev.khanlabs.lanshare.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.zip.CRC32

/** One file inside a ZIP. [size] is read once, when the share is made; [modified] is milliseconds since 1970. */
class ZipPart(val name: String, val size: Long, val modified: Long, val open: () -> InputStream)

/** A file's size is not what it was when the share was made, so the length promised to the browser can't be met. */
class ChangedException(val name: String) : IOException("$name changed while it was being sent")

/**
 * A stored (not compressed) ZIP whose exact length is known before the first byte is sent, so a browser can show
 * real progress. Every file is followed by a data descriptor, so its checksum is worked out while it streams.
 * A size, offset or count past the classic limits moves to the ZIP64 form. Matches `ZipPlan` in the Windows app.
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
    private val cdOffset: Long
    private val cdSize: Long
    private val zip64End: Boolean
    val length: Long

    init {
        var offset = 0L
        for ((i, p) in this.parts.withIndex()) {
            offsets[i] = offset
            offset += local(i).size + p.size + descriptorSize(p)
        }
        cdOffset = offset
        cdSize = this.parts.indices.sumOf { central(it, 0).size.toLong() }
        zip64End = this.parts.size > maxCount || cdSize >= limit || cdOffset >= limit
        length = cdOffset + cdSize + end().size
    }

    private fun big(p: ZipPart) = p.size >= limit
    private fun descriptorSize(p: ZipPart) = if (big(p)) 24L else 16L

    private fun buf(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun local(i: Int): ByteArray {
        val p = parts[i]
        val extra = if (big(p)) 20 else 0
        val b = buf(30 + names[i].size + extra)
        val sizeField = if (big(p)) 0xFFFFFFFFL else 0L
        b.putInt(0x04034B50).putShort((if (big(p)) 45 else 20).toShort()).putShort(0x0808.toShort()).putShort(0)
        b.putShort(times[i].first.toShort()).putShort(times[i].second.toShort())
        b.putInt(0).putInt(sizeField.toInt()).putInt(sizeField.toInt())
        b.putShort(names[i].size.toShort()).putShort(extra.toShort())
        b.put(names[i])
        if (extra > 0) b.putShort(1).putShort(16).putLong(0).putLong(0)
        return b.array()
    }

    private fun central(i: Int, crc: Long): ByteArray {
        val p = parts[i]
        val offset = offsets[i]
        val bigOffset = offset >= limit
        val fields = (if (big(p)) 2 else 0) + (if (bigOffset) 1 else 0)
        val extra = if (fields > 0) 4 + 8 * fields else 0
        val need = if (big(p) || bigOffset) 45 else 20
        val size = if (big(p)) 0xFFFFFFFFL else p.size
        val b = buf(46 + names[i].size + extra)
        b.putInt(0x02014B50).putShort(((3 shl 8) or need).toShort()).putShort(need.toShort())
        b.putShort(0x0808.toShort()).putShort(0)
        b.putShort(times[i].first.toShort()).putShort(times[i].second.toShort())
        b.putInt(crc.toInt()).putInt(size.toInt()).putInt(size.toInt())
        b.putShort(names[i].size.toShort()).putShort(extra.toShort()).putShort(0).putShort(0).putShort(0)
        b.putInt(UNIX_FILE).putInt(if (bigOffset) -1 else offset.toInt())
        b.put(names[i])
        if (fields > 0) {
            b.putShort(1).putShort((8 * fields).toShort())
            if (big(p)) b.putLong(p.size).putLong(p.size)
            if (bigOffset) b.putLong(offset)
        }
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
     * Writes the whole ZIP. Throws [ChangedException] when a file no longer has the size it had when the share
     * was made; an [IOException] from [out] means the other side left.
     */
    fun writeTo(out: OutputStream) {
        val crcs = LongArray(parts.size)
        val chunk = ByteArray(64 * 1024)
        for ((i, p) in parts.withIndex()) {
            val input = try {
                p.open()
            } catch (e: IOException) {
                throw ChangedException(p.name)
            }
            input.use { stream ->
                out.write(local(i))
                val crc = CRC32()
                var sent = 0L
                while (true) {
                    val n = try {
                        stream.read(chunk)
                    } catch (e: IOException) {
                        throw ChangedException(p.name)
                    }
                    if (n < 0) break
                    sent += n
                    if (sent > p.size) throw ChangedException(p.name)
                    crc.update(chunk, 0, n)
                    out.write(chunk, 0, n)
                }
                if (sent != p.size) throw ChangedException(p.name)
                crcs[i] = crc.value
                val d = buf(descriptorSize(p).toInt()).putInt(0x08074B50).putInt(crc.value.toInt())
                if (big(p)) d.putLong(p.size).putLong(p.size) else d.putInt(p.size.toInt()).putInt(p.size.toInt())
                out.write(d.array())
            }
        }
        for (i in parts.indices) out.write(central(i, crcs[i]))
        out.write(end())
    }

    private companion object {
        const val UNIX_FILE = (0b1000000110100100 shl 16) // 0100644 regular file, so unzip on Unix makes readable files

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
