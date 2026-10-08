package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.Names
import java.io.File
import kotlin.system.exitProcess

/**
 * Sends files and text with the Kotlin sender, for testing against another device (test code only):
 *     gradlew :core-net:sendTo -Pto=192.168.1.20:47820 -Pid=<device ID or "-"> -Pfiles="a.txt;photos" -Ptext="hi"
 * Folders keep their structure, like the Windows app. Prints the result; exits 1 unless the send is done.
 * It sends with protocol v2 to a device that speaks it; set the environment variable KL_V1 to force v1 for a comparison.
 */
fun main(args: Array<String>) {
    val (to, id) = args[0] to args[1].takeIf { it != "-" }
    val text = args[2]
    val paths = args.drop(3)
    val host = to.substringBeforeLast(':')
    val port = to.substringAfterLast(':').toInt()
    val clients = Clients()
    val device = fetchInfo(clients, host, port, id)
    println("Device ${device.name} (${device.shortId}) ${device.app}")
    val items = ArrayList<SendItem>()
    for (p in paths.map { File(it).absoluteFile }) {
        if (p.isFile) {
            items += SendItem(Names.safePart(p.name), p.length()) { p.inputStream() }
        } else if (p.isDirectory) {
            p.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                val parts = listOf(p.name) + f.relativeTo(p).invariantSeparatorsPath.split('/')
                items += SendItem(Names.safeRelPath(parts), f.length()) { f.inputStream() }
            }
        }
    }
    val identity = Identity.create("cli")
    val me = Me(identity.id, "Kotlin sender", "android", "Amanah Android test")
    val begun = System.nanoTime()
    var shown = ""
    val job = SendJob(clients, me, device, items, text, identity = identity, allowV2 = System.getenv("KL_V1") == null) { j ->
        // every change of state or message, so a break and the new try show up; progress alone prints nothing
        val line = "${j.state} ${j.message}"
        if (line != shown) println(line)
        shown = line
    }
    job.run()
    val seconds = (System.nanoTime() - begun) / 1e9
    println("Saved: ${job.saved.take(10)}${if (job.saved.size > 10) " and ${job.saved.size - 10} more" else ""}")
    println("Sent ${job.sent} of ${job.total} bytes, ${job.verified} files verified, ${job.resumes} resumes")
    println("%.1f s, %.1f MB/s, %.0f files/s, peak %.1f MB/s".format(seconds, job.sent / 1e6 / seconds,
        items.size / seconds, job.peakSpeed / 1e6))
    exitProcess(if (job.state == SendJob.State.DONE) 0 else 1)
}
