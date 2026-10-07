package dev.khanlabs.lanshare

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
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
import dev.khanlabs.lanshare.transfer.Notifications
import dev.khanlabs.lanshare.transfer.TransferService
import dev.khanlabs.lanshare.ui.HomeScreen
import dev.khanlabs.lanshare.ui.LanShareTheme
import dev.khanlabs.lanshare.ui.Shared
import dev.khanlabs.lanshare.ui.WelcomeScreen

class MainActivity : ComponentActivity() {
    private var shared by mutableStateOf(Shared())
    private var link by mutableStateOf("")  // a klshare:// connect link opened from a camera app
    private var openChat by mutableStateOf("")  // a message notification was tapped: the other device's ID

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    /** Debug builds only: a text size and screen width picked in Settings, so layouts can be tested on any phone. */
    override fun attachBaseContext(newBase: Context) {
        val debuggable = newBase.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val scale = if (debuggable) prefs.getFloat("debug_font_scale", 1f) else 1f
        val small = debuggable && prefs.getBoolean("debug_small_screen", false)
        if (scale == 1f && !small) return super.attachBaseContext(newBase)
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = scale
        if (small) {
            val widthPx = newBase.resources.displayMetrics.widthPixels
            config.densityDpi = widthPx * 160 / 320 // 320 dp wide
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readShare(intent)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        var welcomed by mutableStateOf(prefs.getBoolean("welcomed", false))
        setContent {
            LanShareTheme {
                if (welcomed) {
                    HomeScreen(shared, link, onLinkHandled = { link = "" }, openChat = openChat, onChatHandled = { openChat = "" },
                        onShareDone = { shared = Shared() }, onReceivingChange = ::setReceiving)
                } else {
                    WelcomeScreen(permissionNote = permissionNote(), onContinue = {
                        prefs.edit().putBoolean("welcomed", true).apply()
                        welcomed = true
                        askForPermissions()
                    })
                }
            }
        }
        // Asked once, on "Get started". After that the Receiving sheet explains and links to Settings, so a "no" is respected.
    }

    /** Runtime permissions this phone needs, in plain words for the welcome screen. */
    private fun permissionNote(): String = when {
        Build.VERSION.SDK_INT >= ANDROID_17 ->
            "Next, Android will ask to show notifications and to find devices on your Wi-Fi. " +
                "Notifications are how you accept a file when the app is closed."
        Build.VERSION.SDK_INT >= 33 ->
            "Next, Android will ask to show notifications. That is how you accept a file when the app is closed."
        else -> ""
    }

    private fun askForPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= ANDROID_17) add(ACCESS_LOCAL_NETWORK)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askPermissions.launch(wanted.toTypedArray())
    }

    override fun onStart() {
        super.onStart()
        Engine.recheckSaveFolder() // Android may have taken back access to the folder chosen in Settings
        if (Engine.receiving) TransferService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    private fun setReceiving(on: Boolean) {
        Engine.setReceiving(on)
        TransferService.sync(this)
    }

    /** Files or text from another app's Share button, or a connect link from a camera app. */
    private fun readShare(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifications.EXTRA_CHAT)?.let {
            openChat = it
            return
        }
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { link = it.toString() }
            return
        }
        // a device tapped in the app icon's menu: ask what to send it
        if (intent.action == ShareShortcuts.ACTION_SEND_TO) {
            shared = Shared(deviceId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID).orEmpty())
            return
        }
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.toList() ?: emptyList()
            else -> return
        }
        val text = if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT) ?: "" else ""
        // the files are opened now, while the other app still lets this one read them, not when a device is picked
        shared.held?.release() // an earlier share nobody sent is replaced by this one
        val held = if (uris.isEmpty()) null else Engine.holdFiles(uris)
        // sent from a device in the Share menu: the system adds the shortcut's id
        shared = Shared(uris, text, deviceId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID).orEmpty(), held = held)
    }

    private companion object {
        /** Android 17 (API 37) makes apps ask before they talk to devices on the local network. Not tested on a real device yet. */
        const val ANDROID_17 = 37
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
