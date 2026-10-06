package dev.khanlabs.lanshare.transfer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the receiver running while the app is in the background, with a "Ready to receive" notification.
 * It runs while receiving is on, a share to anyone is live, or a Wi-Fi network is made or joined; when all end it stops.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        Notifications.cancelPaused(this)
        goForeground("Starting")
        if (!Engine.needsService) return stopSelf() // restarted by Android with nothing to do
        Thread { Engine.startReceiving() }.start()
        scope.launch {
            Engine.state.map { s ->
                val share = s.share
                val own = s.ownWifi
                val busy = listOfNotNull(
                    if (share != null && share.live) "Sharing ${share.items} ${if (share.items == 1) "item" else "items"}" else null,
                    when (own.mode) {
                        OwnWifiView.Mode.Hosting -> "Wi-Fi network \"${own.ssid}\" is on"
                        OwnWifiView.Mode.Starting -> "Making a Wi-Fi network"
                        OwnWifiView.Mode.Joining -> "Joining \"${own.ssid}\""
                        OwnWifiView.Mode.Joined -> "Joined \"${own.ssid}\""
                        OwnWifiView.Mode.Off -> null
                    },
                )
                when {
                    busy.isNotEmpty() -> busy.joinToString(". ")
                    s.listenError != null -> s.listenError
                    s.port == null -> "Starting"
                    !s.receiving -> "Receiving is off"
                    s.addresses.isEmpty() -> "${s.name}: connect to Wi-Fi to receive"
                    else -> "${s.name} at ${s.addresses.first()}:${s.port}"
                }
            }.distinctUntilChanged().collect { goForeground(it) }
        }
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifications.SERVICE_ID, Notifications.service(this, text), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // receiving may have been switched on while the service was already running for a share
        Thread { Engine.startReceiving() }.start()
        return START_STICKY
    }

    /**
     * Android 15 limits dataSync services to 6 hours a day. The service must stop within seconds, so it says why and
     * stops; the timer starts again when the person opens the app.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Notifications.paused(this)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        Engine.stopReceiving()
        Engine.shareServiceGone()
        Engine.stopOwnNetwork()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }

        /** Runs the service while receiving is on, a share is live or a Wi-Fi network is up, and stops it otherwise. Call from the screen. */
        fun sync(context: Context) {
            if (Engine.needsService) start(context) else stop(context)
        }
    }
}

/** Buttons on the notifications: Accept, Decline, Copy text, and Reply and Mark as read on messages. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Engine.isInitialized) return
        intent.getStringExtra(EXTRA_CHAT)?.let { peer ->
            when (intent.action) {
                REPLY -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.let { Engine.reply(peer, it.toString()) }
                MARK_READ -> Engine.markRead(peer)
            }
            return
        }
        val offerId = intent.getStringExtra(EXTRA_OFFER) ?: return
        when (intent.action) {
            ACCEPT -> Engine.answer(offerId, true)
            DECLINE -> Engine.answer(offerId, false)
            COPY -> {
                val text = Engine.state.value.incoming.firstOrNull { it.id == offerId }?.text ?: return
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Received text", text))
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val ACCEPT = "dev.khanlabs.lanshare.ACCEPT"
        const val DECLINE = "dev.khanlabs.lanshare.DECLINE"
        const val COPY = "dev.khanlabs.lanshare.COPY"
        const val REPLY = "dev.khanlabs.lanshare.REPLY"
        const val MARK_READ = "dev.khanlabs.lanshare.MARK_READ"
        const val EXTRA_OFFER = "offer"
        const val EXTRA_CHAT = "chat"
        const val KEY_REPLY = "reply"
    }
}
