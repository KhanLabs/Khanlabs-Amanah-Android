package dev.khanlabs.lanshare

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.khanlabs.lanshare.transfer.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Puts the devices on this network into the Share menu of other apps (and the app icon's long-press menu),
 * so a photo can go to a PC in two taps. The list stays on this phone; nothing is sent anywhere.
 */
object ShareShortcuts {
    const val CATEGORY = "dev.khanlabs.lanshare.category.SHARE_DEVICE"
    const val ACTION_SEND_TO = "dev.khanlabs.lanshare.action.SEND_TO"
    private const val MAX = 4

    @OptIn(FlowPreview::class)
    fun keepUpToDate(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            Engine.state
                .map { s -> (s.added + s.nearby).distinctBy { it.id }.sortedBy { it.name.lowercase() }.take(MAX).map { it.id to it.name } }
                .distinctUntilChanged()
                .debounce(3_000) // discovery comes and goes; the system limits how often shortcuts may change
                .collect { devices -> publish(app, devices) }
        }
    }

    private fun publish(context: Context, devices: List<Pair<String, String>>) {
        val shortcuts = devices.map { (id, name) ->
            val open = Intent(context, MainActivity::class.java)
                .setAction(ACTION_SEND_TO)
                .putExtra(Intent.EXTRA_SHORTCUT_ID, id)
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(name.ifBlank { "Device" })
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_share_device))
                .setIntent(open)
                .setCategories(setOf(CATEGORY))
                .setLongLived(true)
                .build()
        }
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (_: Exception) {
            // rate limited or not allowed on this phone: the Share menu still works through the app's own device list
        }
    }
}
