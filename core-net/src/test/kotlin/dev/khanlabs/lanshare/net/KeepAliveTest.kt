package dev.khanlabs.lanshare.net

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A connection is used again for the v2 paths (PROTOCOL-v2.md section 1.1) and for nothing else. */
class KeepAliveTest {
    private val identity = Identity.create("test")
    private val connects = AtomicInteger()
    private lateinit var server: HttpsServer
    private lateinit var client: OkHttpClient   // protocol v2: connections are kept
    private lateinit var clientV1: OkHttpClient // protocol v1: one request per connection

    private fun counting(base: OkHttpClient) = base.newBuilder().eventListener(object : EventListener() {
        override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            connects.incrementAndGet()
        }
    }).build()

    @BeforeTest
    fun start() {
        server = HttpsServer(identity.serverContext(), ::handle, InetAddress.getByName("127.0.0.1"), 47960, tries = 20)
        server.start()
        val clients = Clients()
        client = counting(clients.pinnedV2(identity.id))
        clientV1 = counting(clients.pinned(identity.id))
    }

    @AfterTest
    fun stop() {
        server.stop()
        client.connectionPool.evictAll()
    }

    private fun handle(x: Exchange) {
        when {
            x.path.endsWith("/echo") -> {
                x.bodyRead = true
                x.sendJson(200, """{"n":${x.body.readBytes().size}}""")
            }
            x.path.endsWith("/ignore") -> x.sendJson(200, """{"ignored":true}""") // answers without reading the body
            else -> x.fail(404, "not_found", "Unknown endpoint")
        }
    }

    private fun post(path: String, size: Int, from: OkHttpClient = client): String {
        val request = Request.Builder().url("https://127.0.0.1:${server.port}$path").post(ByteArray(size).toRequestBody()).build()
        return from.newCall(request).execute().use { it.body.string() }
    }

    @Test
    fun v2RequestsShareOneConnection() {
        for (i in 1..5) assertEquals("""{"n":100}""", post("/api/kl/v2/echo", 100))
        assertEquals(1, connects.get())
    }

    @Test
    fun v1RequestsStillUseOneConnectionEach() {
        for (i in 1..3) assertEquals("""{"n":100}""", post("/api/kl/v1/echo", 100, clientV1))
        assertEquals(3, connects.get())
    }

    @Test
    fun aV2ClientThatAsksToCloseGetsAClosedConnection() {
        // OkHttp sends "Connection: close" when the request says so; the server must not keep it open
        val request = Request.Builder().url("https://127.0.0.1:${server.port}/api/kl/v2/echo").header("Connection", "close")
            .post(ByteArray(5).toRequestBody()).build()
        client.newCall(request).execute().use { assertEquals("""{"n":5}""", it.body.string()); assertEquals("close", it.header("Connection")) }
        assertEquals("""{"n":5}""", post("/api/kl/v2/echo", 5))
        assertEquals(2, connects.get())
    }

    @Test
    fun aSmallBodyTheHandlerIgnoredIsSkippedAndTheConnectionStaysUsable() {
        assertEquals("""{"ignored":true}""", post("/api/kl/v2/ignore", 50_000))
        assertEquals("""{"n":10}""", post("/api/kl/v2/echo", 10))
        assertEquals(1, connects.get())
    }

    @Test
    fun aBodyTheHandlerIgnoredIsReadToItsEndSoTheSenderGetsTheAnswer() {
        // up to 64 MiB is read and thrown away (the spec says to), so the sender sees the reply and the connection lives on
        assertEquals("""{"ignored":true}""", post("/api/kl/v2/ignore", 3 * 1024 * 1024))
        assertEquals("""{"n":10}""", post("/api/kl/v2/echo", 10))
        assertEquals(1, connects.get())
    }

    @Test
    fun anErrorAnswerKeepsTheConnectionToo() {
        val request = Request.Builder().url("https://127.0.0.1:${server.port}/api/kl/v2/nothing").build()
        client.newCall(request).execute().use { assertEquals(404, it.code) }
        assertEquals("""{"n":1}""", post("/api/kl/v2/echo", 1))
        assertEquals(1, connects.get())
    }
}
