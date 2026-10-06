package dev.khanlabs.lanshare.net

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Share to anyone over a real socket on 127.0.0.1: what the page shows, downloads, the PIN, limits, stopping. */
class ShareServerTest {
    private lateinit var tmp: File
    private var server: ShareServer? = null
    private var now = 0L
    private val loopback = InetAddress.getLoopbackAddress()

    private class Resp(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    @BeforeTest
    fun start() {
        tmp = Files.createTempDirectory("klshare-share-").toFile()
    }

    @AfterTest
    fun stop() {
        server?.close()
        tmp.deleteRecursively()
    }

    private fun write(path: String, bytes: ByteArray): File = File(tmp, path).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }

    private fun item(name: String, file: File) = SendItem(name, file.length()) { FileInputStream(file) }

    private val photo = ByteArray(10_240) { it.toByte() }

    /** A file, and a folder "docs" with one file. Something private sits next to them and is never shared. */
    private fun share(pin: String? = null, minutes: Int = 60, downloads: List<ShareDownload> = emptyList(),
                      connectUrl: String = ""): ShareServer {
        val p = write("photo one.jpg", photo)
        val a = write("docs/a.txt", "hello".toByteArray())
        write("private/secret.txt", "not shared".toByteArray())
        val s = ShareServer(listOf(item("photo one.jpg", p), item("docs/a.txt", a)), pin, minutes, downloads, connectUrl,
            host = loopback, firstPort = 47911, clock = { now })
        s.start()
        server = s
        return s
    }

    private fun request(s: ShareServer, method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = ""): Resp =
        Socket(loopback, s.port).use { sock ->
            sock.soTimeout = 10_000
            val req = StringBuilder("$method $path HTTP/1.1\r\n")
            if ("Host" !in headers) req.append("Host: 127.0.0.1\r\n")
            for ((k, v) in headers) req.append("$k: $v\r\n")
            if (body.isNotEmpty()) req.append("Content-Length: ${body.length}\r\n")
            req.append("\r\n").append(body)
            sock.getOutputStream().write(req.toString().toByteArray())
            sock.getOutputStream().flush()
            parse(sock.getInputStream().readBytes())
        }

    private fun parse(all: ByteArray): Resp {
        var split = -1
        for (i in 0..all.size - 4) if (all[i] == '\r'.code.toByte() && all[i + 1] == '\n'.code.toByte() &&
            all[i + 2] == '\r'.code.toByte() && all[i + 3] == '\n'.code.toByte()) { split = i; break }
        val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
        val headers = head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        return Resp(head[0].split(' ')[1].toInt(), headers, all.copyOfRange(split + 4, all.size))
    }

    private fun get(s: ShareServer, path: String, headers: Map<String, String> = emptyMap()) = request(s, "GET", path, headers)
    private fun base(s: ShareServer) = "/s/${s.token}/"

    @Test
    fun thePageListsOnlyWhatWasShared() {
        val s = share()
        val r = get(s, base(s))
        assertEquals(200, r.status)
        assertTrue("photo one.jpg" in r.text)
        assertTrue(">docs<" in r.text)
        assertFalse("secret.txt" in r.text)
        assertFalse("<script" in r.text.lowercase())
        assertTrue("Made with" in r.text)
        assertTrue("default-src 'none'" in r.headers["content-security-policy"]!!)
        assertEquals("nosniff", r.headers["x-content-type-options"])
    }

    @Test
    fun aWrongTokenAndOtherPathsAllGetTheSame404() {
        val s = share()
        val bodies = listOf("/", "/s/" + "A".repeat(22) + "/", "/s/" + s.token.dropLast(1) + "x/", "/private/secret.txt",
            base(s) + "f/9", base(s) + "../private/secret.txt", base(s) + "f/1").map {
            val r = get(s, it)
            assertEquals(404, r.status, it)
            r.text
        }
        assertEquals(1, bodies.toSet().size)
    }

    @Test
    fun aFileDownloadsWithResume() {
        val s = share()
        val r = get(s, base(s) + "f/0")
        assertEquals(200, r.status)
        assertContentEquals(photo, r.body)
        assertTrue("attachment" in r.headers["content-disposition"]!!)
        assertEquals("application/octet-stream", r.headers["content-type"])
        val part = get(s, base(s) + "f/0", mapOf("Range" to "bytes=100-199"))
        assertEquals(206, part.status)
        assertContentEquals(photo.copyOfRange(100, 200), part.body)
        assertEquals("bytes 100-199/${photo.size}", part.headers["content-range"])
        val tail = get(s, base(s) + "f/0", mapOf("Range" to "bytes=-50"))
        assertContentEquals(photo.copyOfRange(photo.size - 50, photo.size), tail.body)
        assertEquals(416, get(s, base(s) + "f/0", mapOf("Range" to "bytes=999999-")).status)
    }

    @Test
    fun headGivesTheLengthAndNoBody() {
        val s = share()
        val r = request(s, "HEAD", base(s) + "f/0")
        assertEquals(200, r.status)
        assertEquals(photo.size.toString(), r.headers["content-length"])
        assertEquals(0, r.body.size)
    }

    @Test
    fun aFolderZipAndTheZipOfEverything() {
        val s = share()
        val z = get(s, base(s) + "z/1")
        assertEquals(200, z.status)
        assertEquals(z.body.size.toString(), z.headers["content-length"])
        val file = File(tmp, "docs.zip").also { it.writeBytes(z.body) }
        ZipFile(file).use { assertEquals("hello", it.getInputStream(it.getEntry("docs/a.txt")).readBytes().decodeToString()) }
        val all = get(s, base(s) + "all.zip")
        assertEquals(all.body.size.toString(), all.headers["content-length"])
        val allFile = File(tmp, "all.zip").also { it.writeBytes(all.body) }
        ZipFile(allFile).use { zip ->
            assertEquals(setOf("docs/a.txt", "photo one.jpg"), zip.entries().asSequence().map { it.name }.toSet())
        }
    }

    @Test
    fun aFileRouteDoesNotServeAFolderOrTheOtherWayRound() {
        val s = share()
        assertEquals(404, get(s, base(s) + "f/1").status)
        assertEquals(404, get(s, base(s) + "z/0").status)
    }

    @Test
    fun theHostMustBeAnIpAddress() {
        val s = share()
        assertEquals(421, get(s, base(s), mapOf("Host" to "evil.example.com")).status)
        assertEquals(200, get(s, base(s), mapOf("Host" to "192.168.1.5:47890")).status)
    }

    @Test
    fun aTlsHelloIsHungUpOn() {
        val s = share()
        Socket(loopback, s.port).use { sock ->
            sock.soTimeout = 5000
            sock.getOutputStream().write(byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x05, 'h'.code.toByte()))
            assertEquals(-1, sock.getInputStream().read())
        }
    }

    private val form = mapOf("Content-Type" to "application/x-www-form-urlencoded")

    @Test
    fun thePinGate() {
        val s = share(pin = "123456")
        val first = get(s, base(s))
        assertEquals(401, first.status)
        assertTrue("Enter the PIN" in first.text)
        assertEquals(401, get(s, base(s) + "f/0").status) // no download without the PIN
        val wrong = request(s, "POST", base(s), form, "pin=000000")
        assertEquals(401, wrong.status)
        assertTrue("not right" in wrong.text)
        val ok = request(s, "POST", base(s), form, "pin=123456")
        assertEquals(303, ok.status)
        val cookie = ok.headers["set-cookie"]!!
        assertTrue("HttpOnly" in cookie)
        assertEquals(200, get(s, base(s) + "f/0", mapOf("Cookie" to cookie.substringBefore(';'))).status)
        assertEquals(401, get(s, base(s) + "f/0", mapOf("Cookie" to "kl_share=forged")).status)
    }

    @Test
    fun fiveWrongPinsLockTheAddress() {
        val s = share(pin = "123456")
        repeat(5) { request(s, "POST", base(s), form, "pin=000000") }
        val locked = request(s, "POST", base(s), form, "pin=123456")
        assertEquals(429, locked.status)
        assertNotNull(locked.headers["retry-after"])
    }

    @Test
    fun manyAddressesTogetherHitTheCap() {
        var t = 0L
        val gate = PinGate { t }
        repeat(20) { assertEquals(PinGate.Result.WRONG, gate.check("10.0.0.${it + 1}", "000000", "123456")) }
        assertEquals(PinGate.Result.LOCKED, gate.check("10.0.1.1", "123456", "123456"))
        t += 15 * 60_000L + 1
        assertEquals(PinGate.Result.OK, gate.check("10.0.1.1", "123456", "123456"))
    }

    @Test
    fun stoppingEndsTheShareWithAPageAndCutsDownloads() {
        val big = write("big.bin", ByteArray(40 * 1024 * 1024))
        val s = ShareServer(listOf(item("big.bin", big)), null, 60, host = loopback, firstPort = 47911, clock = { now })
        s.start()
        server = s
        Socket(loopback, s.port).use { sock ->
            sock.soTimeout = 10_000
            sock.getOutputStream().write("GET ${base(s)}f/0 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            val input = sock.getInputStream()
            val first = ByteArray(2000)
            var got = 0
            while (got < first.size) got += input.read(first, got, first.size - got)
            s.end()
            var rest = 0L
            val buf = ByteArray(64 * 1024)
            try {
                while (true) { val n = input.read(buf); if (n < 0) break; rest += n }
            } catch (e: java.io.IOException) {
                // the connection was cut: that is the point
            }
            assertTrue(got + rest < big.length(), "the download was cut short")
        }
        val r = get(s, base(s))
        assertEquals(410, r.status)
        assertTrue("This share has ended" in r.text)
        assertEquals(404, get(s, "/s/" + "A".repeat(22) + "/").status) // a stranger still learns nothing
    }

    @Test
    fun theTimeLimitEndsTheShare() {
        val s = share(minutes = 1)
        assertEquals(200, get(s, base(s)).status)
        now += 61_000
        val r = get(s, base(s))
        assertEquals(410, r.status)
        assertTrue("This share has ended" in r.text)
        assertFalse(s.live())
    }

    @Test
    fun theCountsGoUp() {
        val s = share()
        get(s, base(s))
        get(s, base(s) + "f/0")
        val stats = s.stats()
        assertEquals(1, stats.visitors)
        assertEquals(1, stats.downloads)
        assertEquals(photo.size.toLong(), stats.bytes)
    }

    @Test
    fun anInstallerAndTheOpenInAmanahLinkAreOffered() {
        val apk = "PK-apk".toByteArray()
        val s = share(downloads = listOf(ShareDownload("Amanah for Android", "Amanah.apk", apk.size.toLong()) { ByteArrayInputStream(apk) }),
            connectUrl = "klshare://connect?v=1&id=AB&name=Phone&ip=192.168.1.5&port=47820")
        val page = get(s, base(s)).text
        assertTrue("Amanah for Android" in page)
        assertTrue("Open in Amanah" in page)
        assertTrue("klshare://connect?v=1&amp;id=AB" in page) // escaped inside the attribute
        val r = get(s, base(s) + "get/0")
        assertEquals(200, r.status)
        assertContentEquals(apk, r.body)
        assertEquals(404, get(s, base(s) + "get/5").status)
    }

    @Test
    fun noInstallerAndNoLinkMeansNoCard() {
        val s = share()
        assertFalse("Share files like this yourself" in get(s, base(s)).text)
    }

    @Test
    fun ifThePortIsTakenTheNextOneIsUsed() {
        val s = share()
        val other = ShareServer(listOf(item("x.bin", write("x.bin", ByteArray(3)))), null, 60, host = loopback, firstPort = s.port)
        try {
            assertNotEquals(s.port, other.port)
        } finally {
            other.close()
        }
    }

    @Test
    fun filesAreGroupedIntoItemsAndNameClashesGetANumber() {
        val items = ShareServer.group(listOf(
            SendItem("Trip/a.jpg", 1) { ByteArrayInputStream(ByteArray(1)) },
            SendItem("Trip/day 1/b.jpg", 2) { ByteArrayInputStream(ByteArray(2)) },
            SendItem("trip", 3) { ByteArrayInputStream(ByteArray(3)) },
            SendItem("note.txt", 4) { ByteArrayInputStream(ByteArray(4)) },
            SendItem("note.txt", 5) { ByteArrayInputStream(ByteArray(5)) },
        ))
        assertEquals(listOf("Trip", "trip (2)", "note.txt", "note (2).txt"), items.map { it.name })
        assertEquals(listOf(true, false, false, false), items.map { it.folder })
        assertEquals(3L, items[0].size)
    }

    @Test
    fun rangesAreParsedLikeTheWindowsApp() {
        fun part(h: String?, size: Long) = ShareServer.parseRange(h, size).let { if (it is ShareServer.RangeSpec.Part) it.start to it.end else it }
        assertEquals(0L to 9L, part("bytes=0-9", 100))
        assertEquals(90L to 99L, part("bytes=90-", 100))
        assertEquals(90L to 99L, part("bytes=-10", 100))
        assertEquals(0L to 99L, part("bytes=0-500", 100))
        assertEquals(ShareServer.RangeSpec.Bad, part("bytes=100-", 100))
        assertEquals(ShareServer.RangeSpec.Bad, part("bytes=-0", 100))
        assertEquals(ShareServer.RangeSpec.Whole, part("bytes=0-1,5-6", 100))
        assertEquals(ShareServer.RangeSpec.Whole, part(null, 100))
    }
}
