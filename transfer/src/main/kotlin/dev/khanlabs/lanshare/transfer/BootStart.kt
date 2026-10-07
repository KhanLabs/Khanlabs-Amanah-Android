package dev.khanlabs.lanshare.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starting to receive when the phone starts. A setting the person turns on in Settings; off until then, so a
 * phone never listens after a restart without having been asked to.
 */
object BootStart {
    /** The setting's value until the person changes it. */
    const val DEFAULT = false

    /** The service starts after a restart only when the person asked for it and receiving is on. */
    fun shouldStart(optedIn: Boolean, receiving: Boolean): Boolean = optedIn && receiving
}

/** The phone finished starting: bring the receiver up if Settings says to. The service shows its ongoing notification. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Engine.isInitialized || !BootStart.shouldStart(Engine.bootStart, Engine.receiving)) return
        try {
            TransferService.start(context)
        } catch (e: Exception) {
            Engine.noteProblem("couldn't start receiving after the phone started: ${e.message}")
        }
    }
}
