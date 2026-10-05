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
 * It runs while receiving is on; turning receiving off stops it.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        goForeground("Starting")
        Thread { Engine.startReceiving() }.start()
        scope.launch {
            Engine.state.map { s ->
                when {
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** Android 15 limits dataSync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        Engine.stopReceiving()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}

/** Buttons on the notifications: Accept, Decline, Copy text. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val offerId = intent.getStringExtra(EXTRA_OFFER) ?: return
        if (!Engine.isInitialized) return
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
        const val EXTRA_OFFER = "offer"
    }
}
