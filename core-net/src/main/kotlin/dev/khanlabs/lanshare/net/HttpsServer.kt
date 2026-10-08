package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.ApiError
import dev.khanlabs.lanshare.protocol.ProtocolJson
import dev.khanlabs.lanshare.protocol.V2
import kotlinx.serialization.KSerializer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * One request and its answer. A connection carries one request, like the Windows app (HTTP/1.0 style), except for the
 * protocol v2 paths, where [keepAlive] lets the connection be used again (PROTOCOL-v2.md section 1.1).
 */
class Exchange internal constructor(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val remoteIp: String,
    /** The body, limited to Content-Length bytes. */
    val body: InputStream,
    private val out: OutputStream,
    /** The connection stays open for the next request after this answer. Only for the v2 API, and only when the client allows it. */
    internal val keepAlive: Boolean = false,
) {
    /** Set once the body has been read, or is being streamed and can't be skipped any more. */
    var bodyRead = false
    var answered = false
        private set

    /** The connection was dropped without an answer ([hangUp]), so it can't be used again. */
    internal var hungUp = false
        private set

    /** Closes the connection under a running answer. Set by the server that made this exchange. */
    internal var onCut: (() -> Unit)? = null

    fun cut() {
        onCut?.invoke()
    }

    /** Closes the connection without an answer, as when the sender went silent in the middle of a body. */
    fun hangUp() {
        answered = true
        bodyRead = true
        hungUp = true
    }

    /**
     * After the answer: is the connection clean for the next request? A body that is only partly read is read to its
     * end when what is left is up to [limit] (PROTOCOL-v2.md section 7: always read the whole body, so the sender gets
     * the reply), else the connection must close, because the next request would start in the middle of it.
     */
    internal fun readyForNext(limit: Long = 64L * 1024 * 1024): Boolean {
        if (!keepAlive || hungUp || !answered) return false
        val left = (body as? LimitedInputStream)?.remaining ?: 0L
        if (left == 0L) return true
        if (left > limit) return false
        return try {
            body.skipNBytesCompat(left)
            true
        } catch (e: IOException) {
            false
        }
    }

    /** Content-Length, or null when it is missing, invalid, or the body is chunked. */
    val contentLength: Long? =
        if (headers.containsKey("transfer-encoding")) null
        else headers["content-length"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    /** With [headOnly] the answer to a HEAD request: the headers (with the real length), no body. */
    fun send(status: Int, contentType: String, body: ByteArray, extra: Map<String, String> = emptyMap(),
             headOnly: Boolean = false) {
        answered = true
        writeHead(status, contentType, body.size.toLong(), extra)
        if (!headOnly) out.write(body)
        out.flush()
    }

    /**
     * Sends the headers with an exact Content-Length, then lets [writeBody] write that many bytes straight to the
     * connection. An exception from [writeBody] (the other side left, a file shrank) ends the connection short.
     */
    fun sendStream(status: Int, contentType: String, length: Long, extra: Map<String, String> = emptyMap(),
                   headOnly: Boolean = false, writeBody: (OutputStream) -> Unit) {
        answered = true
        writeHead(status, contentType, length, extra)
        if (!headOnly) writeBody(out)
        out.flush()
    }

    private fun writeHead(status: Int, contentType: String, length: Long, extra: Map<String, String>) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
        for ((k, v) in extra) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun sendJson(status: Int, json: String) = send(status, "application/json", json.toByteArray(Charsets.UTF_8))

    fun <T> sendJson(status: Int, serializer: KSerializer<T>, value: T) =
        sendJson(status, ProtocolJson.encodeToString(serializer, value))

    /** An error reply: {"error": code, "message": message}, plus `state` and `name` where PROTOCOL.md 3.3 has them. */
    fun fail(status: Int, code: String, message: String, state: String? = null, name: String? = null,
             drainLimit: Long = 1024 * 1024) {
        drain(drainLimit)
        try {
            sendJson(status, ApiError.serializer(), ApiError(code, message, state, name))
        } catch (e: IOException) {
            // the other side has gone; nothing to tell
        }
    }

    /**
     * Reads a small unread body before answering. Closing a TLS connection with bytes still unread resets it,
     * and the sender would lose the error reply.
     */
    fun drain(limit: Long = 1024 * 1024) {
        if (bodyRead) return
        bodyRead = true
        val n = contentLength ?: return
        if (n in 1..limit) {
            try {
                body.skipNBytesCompat(n)
            } catch (e: IOException) {
                // nothing more to read
            }
        }
    }

    companion object {
        fun reason(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            206 -> "Partial Content"
            301 -> "Moved Permanently"
            303 -> "See Other"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            410 -> "Gone"
            411 -> "Length Required"
            413 -> "Content Too Large"
            416 -> "Range Not Satisfiable"
            421 -> "Misdirected Request"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            507 -> "Insufficient Storage"
            else -> "Status"
        }
    }
}

/** Returns the first byte waiting on a connection without taking it, or -1 at end of stream; throws on timeout. */
fun interface Peeker {
    fun peek(s: Socket, timeoutMs: Int): Int
}

/**
 * A small HTTPS server for the device API. One port takes both: TLS is served, plain HTTP gets a 301 to
 * https:// (PROTOCOL.md section 3). Limits match the Windows app: connections per address and in total, and a
 * connection that sends nothing for [idleTimeoutMs] is dropped.
 *
 * To tell the two apart, the server looks at the first byte. With a [peeker] it is left on the socket for TLS to
 * read; without one it is read and handed back with SSLSocketFactory.createSocket(socket, consumed, autoClose),
 * which the JDK has but Android's TLS does not. With [peekPlainHttp] off, every connection is treated as TLS.
 */
class HttpsServer(
    /** The TLS settings, or null for a plain-HTTP server that hangs up on anything that starts like TLS. */
    private val tls: SSLContext?,
    private val handler: (Exchange) -> Unit,
    host: InetAddress? = null,
    firstPort: Int,
    tries: Int = 1,
    private val idleTimeoutMs: Int = 60_000,
    private val maxPerIp: Int = 16,
    private val maxTotal: Int = 64,
    private val peekPlainHttp: Boolean = true,
    private val peeker: Peeker? = null,
) {
    private val server: ServerSocket = bind(host, firstPort, tries)
    val port: Int = server.localPort
    private val counts = HashMap<String, Int>()
    private val open = Collections.synchronizedSet(HashSet<Socket>())
    private val threads = AtomicInteger()
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "klshare-conn-${threads.incrementAndGet()}").apply { isDaemon = true }
    }
    @Volatile private var stopped = false
    private var acceptThread: Thread? = null

    fun start() {
        acceptThread = Thread(::acceptLoop, "klshare-accept").apply { isDaemon = true; start() }
    }

    /** Stops listening and cuts off every connection, including uploads that are still running. */
    fun stop() {
        stopped = true
        closeQuietly(server)
        synchronized(open) { open.toList() }.forEach { closeQuietly(it) }
        pool.shutdownNow()
        turnAways.shutdownNow()
    }

    /** Drops every connection that is open now (downloads in progress) and keeps listening. */
    fun cutConnections() {
        synchronized(open) { open.toList() }.forEach { closeQuietly(it) }
    }

    private fun acceptLoop() {
        while (!stopped) {
            val s = try {
                server.accept()
            } catch (e: IOException) {
                if (stopped) return else continue
            }
            val ip = s.inetAddress.hostAddress ?: ""
            if (!admit(ip)) { // over the limit: answered or closed at once, before a thread starts
                if (tls == null) turnAway(s) else closeQuietly(s)
                continue
            }
            open += s
            try {
                pool.execute {
                    try {
                        serve(s, ip)
                    } catch (e: Exception) {
                        // a broken connection only affects itself
                    } finally {
                        open -= s
                        closeQuietly(s)
                        release(ip)
                    }
                }
            } catch (e: Exception) { // the pool is shut down
                open -= s
                closeQuietly(s)
                release(ip)
            }
        }
    }

    /**
     * A plain-HTTP visitor over the limit gets a short "busy, try again" instead of a silent hang-up, so a browser
     * or download manager waits and retries. One helper thread with a short queue does it, so a flood of
     * connections can't make the server start a thread for each; when the queue is full the connection is just closed.
     */
    private val turnAways = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32),
        { r -> Thread(r, "klshare-busy").apply { isDaemon = true } }) { task, _ -> (task as? BusyTask)?.give() }

    private inner class BusyTask(private val s: Socket) : Runnable {
        override fun run() {
            try {
                s.soTimeout = 300
                s.getOutputStream().write(BUSY)
                s.getOutputStream().flush()
                s.shutdownOutput()
                // read what the visitor sent, so closing doesn't reset the connection and lose the answer
                val sink = ByteArray(4096)
                try {
                    while (s.getInputStream().read(sink) >= 0) { /* discard */ }
                } catch (e: IOException) {
                    // slow or gone
                }
            } catch (e: IOException) {
                // gone already
            } finally {
                give()
            }
        }

        fun give() = closeQuietly(s)
    }

    private fun turnAway(s: Socket) {
        try {
            turnAways.execute(BusyTask(s))
        } catch (e: Exception) {
            closeQuietly(s)
        }
    }

    private fun admit(ip: String): Boolean = synchronized(counts) {
        val n = counts[ip] ?: 0
        if (n >= maxPerIp || counts.values.sum() >= maxTotal) return false
        counts[ip] = n + 1
        true
    }

    private fun release(ip: String) = synchronized(counts) {
        val n = (counts[ip] ?: 0) - 1
        if (n > 0) counts[ip] = n else counts.remove(ip)
    }

    /** Plain HTTP, for a server with no TLS: the same request handling, and a TLS hello gets no answer. */
    private fun servePlain(s: Socket, ip: String) {
        val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
        input.mark(1)
        val first = input.read()
        if (first < 0 || first == TLS_HANDSHAKE) return
        input.reset()
        s.soTimeout = idleTimeoutMs
        val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        serveRequests(s, input, output, ip)
        s.close()
    }

    /**
     * Answers requests on one connection. It takes the next request only after a v2 answer that left the connection
     * clean ([Exchange.readyForNext]); every other connection answers once and ends, as before.
     */
    private fun serveRequests(s: Socket, input: InputStream, output: OutputStream, ip: String) {
        while (!stopped) {
            val exchange = readRequest(input, output, ip) ?: return
            exchange.onCut = { closeQuietly(s) }
            handler(exchange)
            if (!exchange.answered) exchange.fail(500, "failed", "No answer")
            output.flush()
            if (exchange.readyForNext()) continue
            if (!exchange.bodyRead) exchange.drain()
            return
        }
    }

    private fun serve(s: Socket, ip: String) {
        s.tcpNoDelay = true
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        if (tls == null) return servePlain(s, ip)
        val factory = tls.socketFactory
        val secure: SSLSocket = when {
            !peekPlainHttp -> factory.createSocket(s, ip, s.port, true) as SSLSocket
            peeker != null -> {
                val first = peeker.peek(s, HANDSHAKE_TIMEOUT_MS)
                if (first < 0) return
                if (first != TLS_HANDSHAKE) {
                    s.soTimeout = idleTimeoutMs
                    return redirect(s, s.getInputStream().read(), ip)
                }
                factory.createSocket(s, ip, s.port, true) as SSLSocket
            }
            else -> {
                val first = s.getInputStream().read()
                if (first < 0) return
                if (first != TLS_HANDSHAKE) {
                    s.soTimeout = idleTimeoutMs
                    return redirect(s, first, ip)
                }
                factory.createSocket(s, ByteArrayInputStream(byteArrayOf(first.toByte())), true) as SSLSocket
            }
        }
        secure.useClientMode = false
        secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        secure.startHandshake()
        s.soTimeout = idleTimeoutMs
        val input = BufferedInputStream(secure.inputStream, 64 * 1024)
        val output = BufferedOutputStream(secure.outputStream, 64 * 1024)
        serveRequests(s, input, output, ip)
        secure.close()
    }

    /** Parses the request line and headers. Answers and returns null when they are broken. */
    private fun readRequest(input: InputStream, output: OutputStream, ip: String): Exchange? {
        val bare = Exchange("", "", emptyMap(), emptyMap(), ip, emptyStream(), output)
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
            bare.fail(400, "bad_request", "Bad request line")
            return null
        }
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            if (headers.size >= MAX_HEADERS) {
                bare.fail(431, "bad_request", "Too many headers")
                return null
            }
            val colon = h.indexOf(':')
            if (colon <= 0) {
                bare.fail(400, "bad_request", "Bad header")
                return null
            }
            val name = h.substring(0, colon).trim().lowercase()
            val value = h.substring(colon + 1).trim()
            if (name == "content-length" && headers[name]?.let { it != value } == true) {
                bare.fail(400, "bad_request", "Conflicting Content-Length")
                return null
            }
            headers[name] = value
        }
        val target = parts[1]
        val path = target.substringBefore('?').trimEnd('/')
        val query = parseQuery(target.substringAfter('?', ""))
        val length = headers["content-length"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 && !headers.containsKey("transfer-encoding") }
        val body = if (length != null) LimitedInputStream(input, length) else emptyStream()
        // only the v2 API reuses a connection, and only for HTTP/1.1 clients that don't ask to close it
        val keepAlive = path.startsWith(V2.API) && parts[2] == "HTTP/1.1" && headers["connection"]?.lowercase() != "close"
        return Exchange(parts[0], path, query, headers, ip, body, output, keepAlive)
    }

    /** Plain HTTP on the API port: send the client to the same address with https://. */
    private fun redirect(s: Socket, first: Int, ip: String) {
        val input = BufferedInputStream(s.getInputStream())
        val output = BufferedOutputStream(s.getOutputStream())
        val rest = readLine(input) ?: return
        val line = first.toChar() + rest
        var host = ""
        while (true) {
            val h = readLine(input) ?: return
            if (h.isEmpty()) break
            if (h.substringBefore(':').trim().equals("host", ignoreCase = true)) host = h.substringAfter(':').trim()
        }
        val ex = Exchange("", "", emptyMap(), mapOf("host" to host), ip, emptyStream(), output)
        if (!hostAllowed(host)) return ex.fail(421, "wrong_host", "Use the device's IP address")
        val target = line.split(' ').getOrNull(1)?.takeIf { it.startsWith("/") } ?: "/"
        val location = "https://${host.ifEmpty { "${s.localAddress.hostAddress}:$port" }}$target"
        ex.send(301, "text/plain", ByteArray(0), mapOf("Location" to location))
    }

    companion object {
        const val TLS_HANDSHAKE = 0x16 // the first byte of every TLS connection
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        private val BUSY = ("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 2\r\n" +
            "Content-Type: text/plain\r\nContent-Length: 25\r\nConnection: close\r\n\r\n" +
            "Busy. Try again shortly.\n").toByteArray(Charsets.ISO_8859_1)
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 100

        private fun bind(host: InetAddress?, firstPort: Int, tries: Int): ServerSocket {
            var last: IOException? = null
            for (p in firstPort until firstPort + maxOf(1, tries)) {
                val s = ServerSocket()
                try {
                    // On Linux and Android this only lets the port be taken again while closed connections wait
                    // in TIME_WAIT, so a restarted app keeps its port. On Windows it would let two programs share
                    // a port, so there it stays off.
                    s.reuseAddress = !System.getProperty("os.name", "").startsWith("Windows")
                    s.bind(InetSocketAddress(host, p), 50)
                    return s
                } catch (e: IOException) {
                    closeQuietly(s)
                    last = e
                }
            }
            throw last ?: SocketException("No port")
        }

        /** One line without its CRLF, or null at the end of the stream. Lines over 8 KB end the connection. */
        internal fun readLine(input: InputStream): String? {
            val buf = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.isEmpty()) null else buf.toString()
                if (b == '\n'.code) return buf.toString().removeSuffix("\r")
                if (buf.length >= MAX_LINE) throw IOException("Line too long")
                buf.append(b.toChar())
            }
        }

        /** Like Python's parse_qs: the first value of each name; names with an empty value are left out. */
        internal fun parseQuery(q: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (pair in q.split('&', ';')) {
                if (pair.isEmpty()) continue
                val k = decode(pair.substringBefore('='))
                val v = decode(pair.substringAfter('=', ""))
                if (k != null && !v.isNullOrEmpty() && k !in out) out[k] = v
            }
            return out
        }

        private fun decode(s: String): String? = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }

        /**
         * Only IP addresses (or localhost). A web page that points its own domain at this device (DNS rebinding)
         * sends that domain as Host, so it gets turned away. No Host at all is allowed, like the Windows app.
         */
        fun hostAllowed(hostHeader: String?): Boolean {
            var host = hostHeader?.trim()?.lowercase() ?: return true
            if (host.isEmpty()) return true
            host = when {
                host.startsWith("[") -> host.substring(1).substringBefore(']')
                host.count { it == ':' } == 1 -> host.substringBefore(':')
                else -> host
            }
            return host == "localhost" || isIpLiteral(host)
        }

        private val IPV4 = Regex("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}")

        /** True for an IPv4 or IPv6 address written out. Never looks anything up. */
        fun isIpLiteral(host: String): Boolean {
            if (IPV4.matches(host)) return true
            if (':' !in host || host.any { !(it.isLetterOrDigit() || it in ":.%") }) return false
            val addr = host.substringBefore('%')
            if (addr.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return false
            return try {
                InetAddress.getByName(addr) // a literal with ':' is parsed, not looked up
                true
            } catch (e: Exception) {
                false
            }
        }

        internal fun closeQuietly(c: AutoCloseable?) {
            try {
                c?.close()
            } catch (e: Exception) {
                // already closed
            }
        }
    }
}

/** Reads at most [limit] bytes from [input], then reports the end of the stream. */
internal class LimitedInputStream(private val input: InputStream, limit: Long) : InputStream() {
    private var left = limit

    /** How many bytes of the body have not been read yet. */
    val remaining: Long get() = left

    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun available(): Int = minOf(input.available().toLong(), left).toInt()
}

/** InputStream.skipNBytes is Java 12+ (and Android 33+); this works everywhere. Throws at an early end. */
internal fun InputStream.skipNBytesCompat(n: Long) {
    var left = n
    val buf = ByteArray(8192)
    while (left > 0) {
        val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) throw IOException("The stream ended early")
        left -= r
    }
}

/** InputStream.nullInputStream is Java 11+ (and Android 33+). */
internal fun emptyStream(): InputStream = ByteArrayInputStream(ByteArray(0))
