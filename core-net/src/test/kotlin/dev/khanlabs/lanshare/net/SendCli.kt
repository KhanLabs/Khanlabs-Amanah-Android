package dev.khanlabs.lanshare.net

import dev.khanlabs.lanshare.protocol.Names
import java.io.File
import kotlin.system.exitProcess

/**
 * Sends files and text with the Kotlin sender, for testing against another device (test code only):
 *     gradlew :core-net:sendTo -Pto=192.168.1.20:47820 -Pid=<device ID or "-"> -Pfiles="a.txt;photos" -Ptext="hi"
 * Folders keep their structure, like the Windows app. Prints the result; exits 1 unless the send is done.
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
    val me = Me(Identity.create("cli").id, "Kotlin sender", "android", "Amanah Android test")
    var shown = ""
    val job = SendJob(clients, me, device, items, text) { j ->
        // every change of state or message, so a break and the new try show up; progress alone prints nothing
        val line = "${j.state} ${j.message}"
        if (line != shown) println(line)
        shown = line
    }
    job.run()
    println("Saved: ${job.saved}")
    println("Sent ${job.sent} of ${job.total} bytes")
    exitProcess(if (job.state == SendJob.State.DONE) 0 else 1)
}
