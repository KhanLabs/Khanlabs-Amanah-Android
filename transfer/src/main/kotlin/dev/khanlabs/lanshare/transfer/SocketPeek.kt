package dev.khanlabs.lanshare.transfer

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.khanlabs.lanshare.net.Peeker
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Looks at the first byte of a connection without taking it, so plain HTTP can get a 301 and TLS still reads its
 * whole handshake. Android's TLS can't be handed bytes that were already read from the socket.
 */
internal object SocketPeek : Peeker {
    override fun peek(s: Socket, timeoutMs: Int): Int {
        val pfd = ParcelFileDescriptor.fromSocket(s) ?: throw IOException("The connection has no file descriptor")
        pfd.use {
            val fd = it.fileDescriptor
            val poll = StructPollfd().apply {
                this.fd = fd
                events = OsConstants.POLLIN.toShort()
            }
            if (Os.poll(arrayOf(poll), timeoutMs) == 0) throw SocketTimeoutException("Nothing was sent")
            val b = ByteArray(1)
            val n = Os.recvfrom(fd, b, 0, 1, OsConstants.MSG_PEEK, null)
            return if (n <= 0) -1 else b[0].toInt() and 0xFF
        }
    }
}
