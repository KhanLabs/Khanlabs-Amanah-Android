package dev.khanlabs.lanshare.net

import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/**
 * Conformance mode, test code only: answers offers by the sender's name, like the Windows repo's
 * conformance/local_target.py ("[decline]" declines, "[wait]" gets no answer, anything else is accepted;
 * "[skip]" also leaves out the files that are already here, like the person keeping that box ticked).
 * A release build must never be able to do this.
 */
class ConformanceAnswers : ReceiverEvents {
    lateinit var receiver: Receiver
    val finished = java.util.concurrent.LinkedBlockingQueue<Offer>()
    /** Chat texts that were delivered without a prompt. */
    val messages = java.util.concurrent.LinkedBlockingQueue<Offer>()
    /** Every offer the person would have been asked about. */
    val asked = java.util.concurrent.LinkedBlockingQueue<Offer>()

    override fun onMessage(offer: Offer) {
        messages.add(offer)
    }

    override fun onOffer(offer: Offer) {
        asked.add(offer)
        val name = offer.sender.name
        if ("[wait]" in name) return
        val accept = "[decline]" !in name
        val skip = "[skip]" in name
        Thread { receiver.answer(offer.id, accept, skip) }.apply { isDaemon = true }.start()
    }

    override fun onFinished(offer: Offer) {
        finished.add(offer)
    }

    /** v2 sessions that came back after a restart. */
    val resumed = java.util.concurrent.LinkedBlockingQueue<Offer>()

    override fun onResumed(offer: Offer) {
        resumed.add(offer)
    }
}

fun startConformanceReceiver(
    inbox: File,
    port: Int,
    host: InetAddress = InetAddress.getLoopbackAddress(),
    identity: Identity = Identity.create("conformance"),
    idleTimeoutMs: Int = 60_000,
    offerTimeoutMs: Long = 120_000,
    messageTrusted: (Offer) -> Boolean = { false },
    /** Turns protocol v2 on; the parts and journals of its sessions go here. */
    v2Dir: File? = null,
    receiving: () -> Boolean = { true },
): Pair<Receiver, ConformanceAnswers> {
    val answers = ConformanceAnswers()
    val rx = Receiver(identity, { ReceiverSettings("Conformance Android", receiving()) }, { FolderStore(inbox) }, answers,
        app = "Amanah Android test", port = port, host = host, idleTimeoutMs = idleTimeoutMs,
        offerTimeoutMs = offerTimeoutMs, messageTrusted = messageTrusted, v2Dir = v2Dir)
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
