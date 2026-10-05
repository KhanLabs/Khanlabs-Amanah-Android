package dev.khanlabs.lanshare.net

import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/**
 * Conformance mode, test code only: answers offers by the sender's name, like the Windows repo's
 * conformance/local_target.py ("[decline]" declines, "[wait]" gets no answer, anything else is accepted).
 * A release build must never be able to do this.
 */
class ConformanceAnswers : ReceiverEvents {
    lateinit var receiver: Receiver
    val finished = java.util.concurrent.LinkedBlockingQueue<Offer>()

    override fun onOffer(offer: Offer) {
        val name = offer.sender.name
        if ("[wait]" in name) return
        val accept = "[decline]" !in name
        Thread { receiver.answer(offer.id, accept) }.apply { isDaemon = true }.start()
    }

    override fun onFinished(offer: Offer) {
        finished.add(offer)
    }
}

fun startConformanceReceiver(
    inbox: File,
    port: Int,
    host: InetAddress = InetAddress.getLoopbackAddress(),
    identity: Identity = Identity.create("conformance"),
    idleTimeoutMs: Int = 60_000,
    offerTimeoutMs: Long = 120_000,
): Pair<Receiver, ConformanceAnswers> {
    val answers = ConformanceAnswers()
    val rx = Receiver(identity, { ReceiverSettings("Conformance Android", true) }, { FolderStore(inbox) }, answers,
        app = "KhanLabs LAN Share Android test", port = port, host = host, idleTimeoutMs = idleTimeoutMs,
        offerTimeoutMs = offerTimeoutMs)
    answers.receiver = rx
    rx.start()
    return rx to answers
}

/**
 * Runs the Kotlin receiver in conformance mode until stopped, for the Windows repo's black-box tests:
 *     gradlew :core-net:conformanceTarget
 *     python -m pytest conformance --target 127.0.0.1:47890 --id <printed ID>
 * Pass "lan" as the first argument to listen on every address instead of 127.0.0.1.
 */
fun main(args: Array<String>) {
    val inbox = Files.createTempDirectory("klshare-android-conformance-").toFile()
    val host = if (args.firstOrNull() == "lan") InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress()
    val (rx, _) = startConformanceReceiver(inbox, 47890, host)
    println("Conformance receiver on ${host.hostAddress}:${rx.port}")
    println("ID ${rx.info().id}")
    println("Files go to $inbox")
    Runtime.getRuntime().addShutdownHook(Thread { rx.stop(); inbox.deleteRecursively() })
    Thread.currentThread().join()
}
