package dev.khanlabs.lanshare.net

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Share to anyone over a real socket on 127.0.0.1: what the page shows, downloads, adding more, the PIN, limits, stopping. */
class ShareServerTest {
    private lateinit var tmp: File
    private var server: ShareServer? = null
    private var now = 0L
    private val loopback = InetAddress.getLoopbackAddress()
    private val other = InetAddress.getByName("127.0.0.2") // a second visitor

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

    private fun bytesItem(name: String, bytes: ByteArray) = SendItem(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private val photo = ByteArray(10_240) { it.toByte() }

    private fun waitReady(s: ShareServer) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (s.stats().preparing > 0) {
            check(System.nanoTime() < end) { "files did not get ready" }
            Thread.sleep(10)
        }
    }

    private fun make(items: List<SendItem>, wait: Boolean = true, build: (List<SendItem>) -> ShareServer): ShareServer {
        val s = build(items)
        s.start()
        server = s
        if (wait) waitReady(s)
        return s
    }

    /** A file, and a folder "docs" with one file. Something private sits next to them and is never shared. */
    private fun share(pin: String? = null, minutes: Int = 60, downloads: List<ShareDownload> = emptyList(),
                      connectUrl: String = "", stash: File? = null, stashAll: Boolean = false): ShareServer {
        val p = write("photo one.jpg", photo)
        val a = write("docs/a.txt", "hello".toByteArray())
        write("private/secret.txt", "not shared".toByteArray())
        return make(listOf(item("photo one.jpg", p), item("docs/a.txt", a))) {
            ShareServer(it, pin, minutes, downloads, connectUrl, host = loopback, firstPort = 47911, clock = { now },
                stash = stash, stashAll = stashAll)
        }
    }

    private fun request(s: ShareServer, method: String, path: String, headers: Map<String, String> = emptyMap(), body: String = "",
                        from: InetAddress? = null): Resp =
        Socket(loopback, s.port, from, 0).use { sock ->
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

    private fun get(s: ShareServer, path: String, headers: Map<String, String> = emptyMap(), from: InetAddress? = null) =
        request(s, "GET", path, headers, from = from)

    private fun base(s: ShareServer) = "/s/${s.token}/"

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The id the page uses for a file, found by its name. */
    private fun id(s: ShareServer, name: String) = s.sharedFiles.first { it.name == name }.id

    @Test
    fun thePageListsOnlyWhatWasShared() {
        val s = share()
        val r = get(s, base(s))
        assertEquals(200, r.status)
        assertTrue("photo one.jpg" in r.text)
        assertTrue(">docs<" in r.text)
        assertFalse("secret.txt" in r.text)
        assertTrue("Made with" in r.text)
        assertTrue("default-src 'none'" in r.headers["content-security-policy"]!!)
        assertEquals("nosniff", r.headers["x-content-type-options"])
    }

    @Test
    fun theListPageHasOneScriptAndThePolicyAllowsOnlyThatScript() {
        val s = share()
        val page = get(s, base(s))
        assertEquals(1, page.text.lowercase().split("<script").size - 1)
        val script = Regex("<script>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(page.text)!!.groupValues[1]
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        val csp = page.headers["content-security-policy"]!!
        assertTrue("script-src 'sha256-$hash'" in csp, csp)
        assertTrue("connect-src 'self'" in csp)
        assertTrue("<noscript><meta http-equiv=\"refresh\"" in page.text, "a reload for readers without scripts")
        // the other pages have no script at all
        assertFalse("<script" in get(s, "/s/" + "A".repeat(22) + "/").text.lowercase())
    }

    @Test
    fun aWrongTokenAndOtherPathsAllGetTheSame404() {
        val s = share()
        val bodies = listOf("/", "/s/" + "A".repeat(22) + "/", "/s/" + s.token.dropLast(1) + "x/", "/private/secret.txt",
            base(s) + "f/99", base(s) + "../private/secret.txt", base(s) + "zip/99-0", base(s) + "f/x").map {
            val r = get(s, it)
            assertEquals(404, r.status, it)
            r.text
        }
        assertEquals(1, bodies.toSet().size)
    }

    @Test
    fun aFileDownloadsWithResumeAndAValidator() {
        val s = share()
        val f = base(s) + "f/${id(s, "photo one.jpg")}"
        val r = get(s, f)
        assertEquals(200, r.status)
        assertContentEquals(photo, r.body)
        assertTrue("attachment" in r.headers["content-disposition"]!!)
        assertEquals("application/octet-stream", r.headers["content-type"])
        assertEquals("bytes", r.headers["accept-ranges"])
        val etag = r.headers["etag"]!!
        assertTrue(etag.startsWith("\"f-") && etag.endsWith("\""))
        val part = get(s, f, mapOf("Range" to "bytes=100-199"))
        assertEquals(206, part.status)
        assertContentEquals(photo.copyOfRange(100, 200), part.body)
        assertEquals("bytes 100-199/${photo.size}", part.headers["content-range"])
        assertEquals(etag, part.headers["etag"])
        val tail = get(s, f, mapOf("Range" to "bytes=-50"))
        assertContentEquals(photo.copyOfRange(photo.size - 50, photo.size), tail.body)
        assertEquals(416, get(s, f, mapOf("Range" to "bytes=999999-")).status)
        // a resume only continues the same bytes: with the right validator it is a range, with another it starts over
        assertEquals(206, get(s, f, mapOf("Range" to "bytes=100-199", "If-Range" to etag)).status)
        val changed = get(s, f, mapOf("Range" to "bytes=100-199", "If-Range" to "\"f-other\""))
        assertEquals(200, changed.status)
        assertContentEquals(photo, changed.body)
    }

    @Test
    fun headGivesTheLengthAndNoBody() {
        val s = share()
        val r = request(s, "HEAD", base(s) + "f/${id(s, "photo one.jpg")}")
        assertEquals(200, r.status)
        assertEquals(photo.size.toString(), r.headers["content-length"])
        assertEquals(0, r.body.size)
    }

    @Test
    fun theZipOfEverythingIsValidForAStreamingUnzipToo() {
        val s = share()
        val redirect = get(s, base(s) + "all.zip")
        assertEquals(303, redirect.status)
        val zip = get(s, redirect.headers["location"]!!)
        assertEquals(200, zip.status)
        assertEquals(zip.body.size.toString(), zip.headers["content-length"])
        assertEquals("bytes", zip.headers["accept-ranges"])
        assertTrue(zip.headers["etag"]!!.startsWith("\"z-"))
        assertTrue("Shared%20files.zip" in zip.headers["content-disposition"]!!)
        val file = File(tmp, "all.zip").also { it.writeBytes(zip.body) }
        ZipFile(file).use { z ->
            assertEquals(setOf("docs/a.txt", "photo one.jpg"), z.entries().asSequence().map { it.name }.toSet())
            assertContentEquals(photo, z.getInputStream(z.getEntry("photo one.jpg")).readBytes())
        }
        val streamed = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip.body)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                streamed[e.name] = z.readBytes()
            }
        }
        assertContentEquals(photo, streamed["photo one.jpg"])
        assertEquals("hello", streamed["docs/a.txt"]!!.decodeToString())
        // a resumed ZIP is the same bytes
        val tail = get(s, redirect.headers["location"]!!, mapOf("Range" to "bytes=${zip.body.size - 100}-"))
        assertEquals(206, tail.status)
        assertContentEquals(zip.body.copyOfRange(zip.body.size - 100, zip.body.size), tail.body)
    }

    @Test
    fun aFolderWithOneFileOffersThatFileAndAFolderWithManyOffersAZip() {
        val one = share()
        assertTrue("f/${id(one, "docs/a.txt")}" in get(one, base(one)).text)
        one.close()
        val s = make(listOf(bytesItem("pics/1.jpg", ByteArray(100)), bytesItem("pics/2.jpg", ByteArray(200)), bytesItem("solo.txt", ByteArray(5)))) {
            ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now })
        }
        val page = get(s, base(s)).text
        val zipHref = Regex("href=\"(/s/[a-f0-9]+/zip/\\d+-0)\"").findAll(page).map { it.groupValues[1] }.toList()
        assertTrue(zipHref.size >= 2, "a ZIP for the folder and one for everything: $zipHref")
        for (href in zipHref) {
            val z = get(s, href)
            assertEquals(200, z.status)
            val file = File(tmp, "x.zip").also { it.writeBytes(z.body) }
            ZipFile(file).use { assertTrue(it.size() >= 2) }
        }
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
        val f = base(s) + "f/0"
        assertEquals(401, get(s, f).status) // no download without the PIN
        assertEquals(401, get(s, base(s) + "v").status)
        assertEquals(401, get(s, base(s) + "checksums.sha256").status)
        val wrong = request(s, "POST", base(s), form, "pin=000000")
        assertEquals(401, wrong.status)
        assertTrue("not right" in wrong.text)
        val ok = request(s, "POST", base(s), form, "pin=123456")
        assertEquals(303, ok.status)
        val cookie = ok.headers["set-cookie"]!!
        assertTrue("HttpOnly" in cookie)
        assertEquals(200, get(s, f, mapOf("Cookie" to cookie.substringBefore(';'))).status)
        assertEquals(401, get(s, f, mapOf("Cookie" to "kl_share=forged")).status)
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
        val s = make(listOf(item("big.bin", big))) { ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now }) }
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
            } catch (e: IOException) {
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
        get(s, base(s) + "f/${id(s, "photo one.jpg")}")
        val stats = s.stats()
        assertEquals(1, stats.visitors)
        assertEquals(1, stats.downloads)
        assertEquals(photo.size.toLong(), stats.bytes)
        assertEquals(2, stats.files)
        assertEquals(2, stats.ready)
    }

    @Test
    fun anInstallerAndTheOpenInAmanahLinkAreOfferedWithoutTheWifiPassword() {
        val apk = "PK-apk".toByteArray()
        val s = share(downloads = listOf(ShareDownload("Amanah for Android", "Amanah.apk", apk.size.toLong()) { ByteArrayInputStream(apk) }),
            connectUrl = "klshare://connect?v=1&id=AB&name=Phone&ip=192.168.1.5&port=47820&ssid=Khan+2.4G&psk=hunter2hunter2")
        val page = get(s, base(s)).text
        assertTrue("Amanah for Android" in page)
        assertTrue("Open in Amanah" in page)
        assertTrue("klshare://connect?v=1&amp;id=AB" in page) // escaped inside the attribute
        assertFalse("hunter2" in page, "the Wi-Fi password is never on the page")
        assertFalse("psk=" in page)
        assertFalse("ssid=" in page)
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
        val second = ShareServer(listOf(item("x.bin", write("x.bin", ByteArray(3)))), null, 60, host = loopback, firstPort = s.port)
        try {
            assertNotEquals(s.port, second.port)
        } finally {
            second.close()
        }
    }

    // --- add more ------------------------------------------------------------------------------------------------

    @Test
    fun addingFilesKeepsEverythingElseAndShowsTheNewOnesAtTheTopForThoseWhoSawTheFirst() {
        val s = share()
        val photoId = id(s, "photo one.jpg")
        assertEquals(200, get(s, base(s)).status) // the first visitor sees the first two files
        assertFalse("Just added" in get(s, base(s)).text)

        val extra = ByteArray(5_000) { (it * 7).toByte() }
        assertEquals(2, s.addFiles(listOf(bytesItem("extra.bin", extra), bytesItem("photo one.jpg", ByteArray(3)))))
        waitReady(s)
        val names = s.sharedFiles.map { it.name }
        assertEquals(listOf("photo one.jpg", "docs/a.txt", "extra.bin", "photo one (2).jpg"), names, "a clashing name gets a number")
        assertEquals(listOf(0, 1, 2, 3), s.sharedFiles.map { it.id })
        assertContentEquals(photo, get(s, base(s) + "f/$photoId").body) // the old link still means the old file

        val page = get(s, base(s)).text
        assertTrue("Just added" in page)
        assertTrue("2 new files" in page)
        assertTrue(page.indexOf("extra.bin") < page.indexOf("docs"), "the new files come first")
        assertTrue("Shared before" in page)

        // someone who opens the page only now sees it all as one list, nothing "new"
        val late = get(s, base(s), from = other).text
        assertFalse("Just added" in late)
        assertTrue("extra.bin" in late && "photo one (2).jpg" in late)

        // downloading what is new takes it out of "Just added" and marks it
        assertContentEquals(extra, get(s, base(s) + "f/${id(s, "extra.bin")}").body)
        val after = get(s, base(s)).text
        assertTrue("1 new file" in after, after.substringAfter("Just added").take(300))
        assertContentEquals(ByteArray(3), get(s, base(s) + "f/${id(s, "photo one (2).jpg")}").body)
        val done = get(s, base(s)).text
        assertFalse("Just added" in done)
        assertTrue("Downloaded" in done)
    }

    @Test
    fun theJustAddedFilesHaveTheirOwnZip() {
        val s = share()
        get(s, base(s))
        s.addFiles(listOf(bytesItem("n1.txt", "one".toByteArray()), bytesItem("n2.txt", "two".toByteArray())))
        waitReady(s)
        val page = get(s, base(s)).text
        val href = Regex("href=\"([^\"]+)\"[^>]*>(?:<svg.*?</svg>)Download the new files as one ZIP").find(page)!!.groupValues[1]
        val z = get(s, href)
        assertTrue("New%20files.zip" in z.headers["content-disposition"]!!)
        ZipFile(File(tmp, "n.zip").also { it.writeBytes(z.body) }).use { zip ->
            assertEquals(setOf("n1.txt", "n2.txt"), zip.entries().asSequence().map { it.name }.toSet())
        }
        // the ZIP is frozen: files added later don't change what this address gives
        s.addFiles(listOf(bytesItem("n3.txt", "three".toByteArray())))
        waitReady(s)
        assertContentEquals(z.body, get(s, href).body)
    }

    @Test
    fun theVersionChangesWhenTheListDoes() {
        val s = share()
        val v1 = get(s, base(s) + "v").text
        s.addFiles(listOf(bytesItem("more.txt", "x".toByteArray())))
        waitReady(s)
        assertNotEquals(v1, get(s, base(s) + "v").text)
        assertTrue("data-v=\"${get(s, base(s) + "v").text}\"" in get(s, base(s)).text)
    }

    @Test
    fun aShareThatEndedTakesNoMoreFiles() {
        val s = share()
        s.end()
        assertFailsWithIllegalState { s.addFiles(listOf(bytesItem("late.txt", ByteArray(1)))) }
    }

    private fun assertFailsWithIllegalState(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalStateException) {
            return
        }
        throw AssertionError("expected an IllegalStateException")
    }

    // --- files that are not ready, or not right ------------------------------------------------------------------

    @Test
    fun aFileIsNotOfferedUntilItWasCheckedAndTheAnswerSaysToTryAgain() {
        val gate = CountDownLatch(1)
        val data = ByteArray(2000) { it.toByte() }
        val slow = SendItem("slow.bin", data.size.toLong()) { gate.await(); ByteArrayInputStream(data) }
        val s = make(listOf(slow), wait = false) { ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now }) }
        val page = get(s, base(s)).text
        assertTrue("Getting" in page && "ready" in page)
        assertFalse("f/0" in page, "no download button yet")
        val busy = get(s, base(s) + "f/0")
        assertEquals(503, busy.status)
        assertNotNull(busy.headers["retry-after"])
        assertEquals(503, get(s, base(s) + "all.zip").status)
        val before = get(s, base(s) + "v").text
        gate.countDown()
        waitReady(s)
        assertNotEquals(before, get(s, base(s) + "v").text)
        assertContentEquals(data, get(s, base(s) + "f/0").body)
    }

    @Test
    fun aFileThatCannotBeReadIsReportedAndTheRestIsFine() {
        val good = write("good.txt", "fine".toByteArray())
        val s = make(listOf(item("good.txt", good), SendItem("bad.mp4", 1000) { throw IOException("permission denied") })) {
            ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now })
        }
        assertEquals(1, s.stats().failed)
        assertEquals(1, s.stats().ready)
        val page = get(s, base(s)).text
        assertTrue("1 file could not be read" in page)
        assertTrue("bad.mp4" in page)
        assertEquals(410, get(s, base(s) + "f/${id(s, "bad.mp4")}").status)
        assertEquals("fine", get(s, base(s) + "f/${id(s, "good.txt")}").text)
        assertTrue("permission denied" in s.sharedFiles.first { it.name == "bad.mp4" }.error)
    }

    @Test
    fun aFileShorterThanThePickerSaidIsNotOfferedAsIfItWereWhole() {
        val s = make(listOf(SendItem("cut.mp4", 5000) { ByteArrayInputStream(ByteArray(3000)) })) {
            ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now })
        }
        assertEquals(1, s.stats().failed)
        assertEquals(410, get(s, base(s) + "f/0").status)
    }

    @Test
    fun aFileThatChangedAfterTheCheckEndsTheDownloadShortAndIsNeverSentWhole() {
        val good = ByteArray(300_000) { (it * 31).toByte() }
        var served = good
        val s = make(listOf(SendItem("clip.bin", good.size.toLong()) { ByteArrayInputStream(served) })) {
            ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now })
        }
        assertContentEquals(good, get(s, base(s) + "f/0").body)
        served = good.clone().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() } // same size, last byte different
        Socket(loopback, s.port).use { sock ->
            sock.soTimeout = 10_000
            sock.getOutputStream().write("GET ${base(s)}f/0 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            val all = try { sock.getInputStream().readBytes() } catch (e: IOException) { ByteArray(0) }
            val r = parse(all)
            assertTrue(r.body.size < good.size, "the body is short: ${r.body.size} of ${good.size}")
            assertEquals(good.size.toString(), r.headers["content-length"], "the promised length does not match, so the browser fails the download")
        }
        assertEquals(1, s.stats().problems)
    }

    @Test
    fun theSeventeenthConnectionFromOneAddressGetsBusyNotSilence() {
        val s = share()
        val held = (1..16).map { Socket(loopback, s.port) } // each one waits for a request
        try {
            Thread.sleep(300) // let the server count them
            Socket(loopback, s.port).use { sock ->
                sock.soTimeout = 5000
                sock.getOutputStream().write("GET ${base(s)} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                val r = parse(sock.getInputStream().readBytes())
                assertEquals(503, r.status)
                assertNotNull(r.headers["retry-after"])
            }
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test
    fun theChecksumListMatchesTheFiles() {
        val s = share()
        val r = get(s, base(s) + "checksums.sha256")
        assertEquals(200, r.status)
        assertTrue("attachment" in r.headers["content-disposition"]!!)
        val lines = r.text.trim().lines().associate { it.substringAfter("  ") to it.substringBefore("  ") }
        assertEquals(sha256(photo), lines["photo one.jpg"])
        assertEquals(sha256("hello".toByteArray()), lines["docs/a.txt"])
    }

    // --- ZIP parts ------------------------------------------------------------------------------------------------

    @Test
    fun manyFilesAreSplitIntoZipsUnderTheLimitAndABigFileDownloadsOnItsOwn() {
        val a = ByteArray(3000) { 1 }
        val b = ByteArray(3000) { 2 }
        val c = ByteArray(3000) { 3 }
        val big = ByteArray(20_000) { 4 }
        val s = make(listOf(bytesItem("a.bin", a), bytesItem("b.bin", b), bytesItem("c.bin", c), bytesItem("big.bin", big))) {
            ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now }, zipPartMax = 8_000)
        }
        val page = get(s, base(s)).text
        assertTrue("part 1 of 2" in page && "part 2 of 2" in page, page.substringAfter("class=\"all\"").take(600))
        assertTrue("over 1.9 GB" in page, "the big file is called out")
        val entries = HashSet<String>()
        for (n in 0..1) {
            val href = Regex("href=\"(/s/[a-f0-9]+/zip/\\d+-$n)\"").find(page)!!.groupValues[1]
            val z = get(s, href)
            assertEquals(200, z.status)
            assertTrue(z.body.size < 8_000 + 1000)
            ZipFile(File(tmp, "p$n.zip").also { it.writeBytes(z.body) }).use { zip -> zip.entries().asSequence().forEach { entries += it.name } }
        }
        assertEquals(setOf("a.bin", "b.bin", "c.bin"), entries, "the big file is not in a ZIP")
        assertContentEquals(big, get(s, base(s) + "f/${id(s, "big.bin")}").body)
    }

    // --- a private copy -------------------------------------------------------------------------------------------

    @Test
    fun withACopyTheDownloadsDontDependOnTheOriginalAndTheCopyIsRemovedAtTheEnd() {
        val stash = File(tmp, "stash")
        val p = write("photo one.jpg", photo)
        val s = make(listOf(item("photo one.jpg", p))) {
            ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now }, stash = stash, stashAll = true)
        }
        assertTrue(stash.walkTopDown().any { it.isFile && it.name.endsWith(".bin") }, "a copy was made")
        assertTrue(p.delete())
        assertContentEquals(photo, get(s, base(s) + "f/0").body) // served from the copy
        s.end()
        assertFalse(stash.walkTopDown().any { it.isFile }, "no copy is left behind")
    }

    @Test
    fun withoutAskingNoCopyIsMadeForAFileOfKnownSize() {
        val stash = File(tmp, "stash")
        val p = write("photo one.jpg", photo)
        make(listOf(item("photo one.jpg", p))) {
            ShareServer(it, null, 60, host = loopback, firstPort = 47911, clock = { now }, stash = stash)
        }
        assertFalse(stash.walkTopDown().any { it.isFile && it.name.endsWith(".bin") })
    }

    // --- names, groups, ranges ------------------------------------------------------------------------------------

    @Test
    fun folderNamesWinAndNameClashesGetANumber() {
        val book = NameBook()
        val names = book.assign(listOf("Trip/a.jpg", "Trip/day 1/b.jpg", "trip", "note.txt", "note.txt", "Trip/a.jpg"))
        assertEquals(listOf("Trip/a.jpg", "Trip/day 1/b.jpg", "trip (2)", "note.txt", "note (2).txt", "Trip/a (2).jpg"), names)
        // a later "add more" keeps the same rules
        assertEquals(listOf("note (3).txt", "trip (3)", "Trip/c.jpg"), book.assign(listOf("note.txt", "trip", "TRIP/c.jpg")))
    }

    @Test
    fun filesAreGroupedIntoItemsNewestBatchFirst() {
        fun file(id: Int, batch: Int, name: String) = ShareFile(id, batch, 0, name, bytesItem(name, ByteArray(id + 1)))
        val items = ShareItem.group(listOf(
            file(0, 1, "Trip/a.jpg"), file(1, 1, "Trip/day 1/b.jpg"), file(2, 1, "trip (2)"), file(3, 1, "note.txt"),
            file(4, 2, "later.txt"), file(5, 2, "Trip/c.jpg"),
        ))
        assertEquals(listOf("Trip", "later.txt", "trip (2)", "note.txt"), items.map { it.name }) // Trip has a batch 2 file
        assertEquals(listOf(true, false, false, false), items.map { it.folder })
        assertEquals(3, items[0].files.size)
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
