package dev.khanlabs.lanshare

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.TransferService
import dev.khanlabs.lanshare.ui.HomeScreen
import dev.khanlabs.lanshare.ui.LanShareTheme
import dev.khanlabs.lanshare.ui.Shared

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf(Shared())

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readShare(intent)
        setContent {
            LanShareTheme {
                HomeScreen(shared, onShareDone = { shared = Shared() }, onReceivingChange = ::setReceiving)
            }
        }
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        if (Engine.receiving) TransferService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    private fun setReceiving(on: Boolean) {
        Engine.setReceiving(on)
        if (on) TransferService.start(this) else TransferService.stop(this)
    }

    /** Files or text from another app's Share button. */
    private fun readShare(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toList() ?: emptyList()
            else -> return
        }
        val text = if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT) ?: "" else ""
        shared = Shared(uris, text)
    }
}
