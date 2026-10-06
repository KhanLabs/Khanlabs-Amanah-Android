package dev.khanlabs.lanshare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.net.SendJob
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.Format
import dev.khanlabs.lanshare.transfer.IncomingView
import dev.khanlabs.lanshare.transfer.Items
import dev.khanlabs.lanshare.transfer.OutgoingView
import dev.khanlabs.lanshare.transfer.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files or text another app shared with this one, waiting for the person to pick a device. */
data class Shared(val uris: List<Uri> = emptyList(), val text: String = "") {
    val isEmpty: Boolean get() = uris.isEmpty() && text.isEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(shared: Shared, onShareDone: () -> Unit, onReceivingChange: (Boolean) -> Unit) {
    val s by Engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Device?>(null) }
    var textFor by remember { mutableStateOf<Device?>(null) }
    var pickFor by remember { mutableStateOf<Device?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pickFor?.let { if (uris.isNotEmpty()) send(context, scope, it, uris, "") }
        pickFor = null
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        pickFor?.let { if (uris.isNotEmpty()) send(context, scope, it, uris, "") }
        pickFor = null
    }

    if (aboutOpen) {
        BackHandler { aboutOpen = false }
        AboutScreen(onBack = { aboutOpen = false })
    } else Scaffold(
        topBar = { TopAppBar(title = { Text("LAN Share") }, actions = { TextButton(onClick = { aboutOpen = true }) { Text("About") } }) },
        bottomBar = { MadeWithBar() },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!shared.isEmpty) item { ShareBanner(shared, onShareDone) }
            item { ThisDevice(s, onRename = { renameOpen = true }, onReceivingChange) }
            item {
                Header("Send to") {
                    TextButton(onClick = { Engine.refreshNearby() }) { Text("Refresh") }
                    TextButton(onClick = { addOpen = true }) { Text("Add by IP") }
                }
            }
            val devices = s.nearby + s.added
            if (devices.isEmpty()) item { NoDevices(s) }
            items(devices, key = { "dev:" + it.id }) { d ->
                DeviceRow(d, isAdded = s.added.any { it.id == d.id }, onForget = { Engine.forget(d) }) {
                    if (!shared.isEmpty) {
                        send(context, scope, d, shared.uris, shared.text)
                        onShareDone()
                    } else {
                        menuFor = d
                    }
                }
            }
            if (s.incoming.isNotEmpty() || s.outgoing.isNotEmpty()) item { Header("Transfers") {} }
            items(s.incoming, key = { "in:" + it.id }) { IncomingCard(it) }
            items(s.outgoing, key = { "out:" + it.key }) { OutgoingCard(it) }
            if (s.conformanceAllowed) item { DebugCard(s) }
        }
    }

    menuFor?.let { d ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text("Send to ${d.name}") },
            text = {
                Column {
                    MenuItem("Files") { pickFor = d; menuFor = null; pickFiles.launch(arrayOf("*/*")) }
                    MenuItem("Photos and videos") {
                        pickFor = d
                        menuFor = null
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                    MenuItem("Text") { textFor = d; menuFor = null }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuFor = null }) { Text("Cancel") } },
        )
    }
    textFor?.let { d -> TextDialog(d, onDone = { textFor = null }) { text -> Engine.send(d, emptyList(), text) } }
    if (addOpen) AddByIpDialog(onDone = { addOpen = false })
    if (renameOpen) RenameDialog(s.name, onDone = { renameOpen = false })
    if (!s.conformance) s.incoming.lastOrNull { it.state == "waiting" }?.let { OfferDialog(it) }
}

private fun send(context: Context, scope: CoroutineScope, device: Device, uris: List<Uri>, text: String) {
    scope.launch {
        val (items, skipped) = withContext(Dispatchers.IO) { Items.fromUris(context, uris) }
        if (skipped > 0) Toast.makeText(context, "$skipped item(s) couldn't be read and were left out", Toast.LENGTH_LONG).show()
        if (items.isNotEmpty() || text.isNotEmpty()) Engine.send(device, items, text)
    }
}

@Composable
private fun Header(title: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun Muted(text: String, mono: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = if (mono) FontFamily.Monospace else null)
}

@Composable
private fun ShareBanner(shared: Shared, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val what = when {
                shared.uris.isEmpty() -> "this text"
                shared.uris.size == 1 -> "1 item"
                else -> "${shared.uris.size} items"
            }
            Text("Pick a device to send $what", Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun ThisDevice(s: UiState, onRename: () -> Unit, onReceivingChange: (Boolean) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onRename)) {
                    Text(s.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("ID ${s.shortId}  (tap the name to change it)", mono = false)
                }
                Switch(checked = s.receiving, onCheckedChange = onReceivingChange)
            }
            Spacer(Modifier.height(8.dp))
            when {
                s.listenError != null -> Text(s.listenError ?: "", color = MaterialTheme.colorScheme.error)
                !s.receiving -> Muted("Receiving is off. You can still send.")
                s.port == null -> Muted("Starting")
                s.addresses.isEmpty() -> Muted("Connect to Wi-Fi to receive")
                else -> {
                    Muted("Ready to receive at")
                    Text(s.addresses.joinToString("  ") { "$it:${s.port}" }, fontFamily = FontFamily.Monospace)
                    Muted("Files go to ${s.folder}")
                }
            }
        }
    }
}

@Composable
private fun NoDevices(s: UiState) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("No devices found yet")
            Spacer(Modifier.height(4.dp))
            Muted(if (s.receiving) "Open KhanLabs LAN Share on the other device, on the same Wi-Fi. If it still doesn't show up, use Add by IP."
                  else "Turn on receiving to look for other devices, or use Add by IP.")
        }
    }
}

@Composable
private fun DeviceRow(d: Device, isAdded: Boolean, onForget: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("${d.model.ifEmpty { "device" }}  ·  ID ${d.shortId}  ·  ${d.hosts.firstOrNull() ?: ""}")
            }
            if (isAdded) TextButton(onClick = onForget) { Text("Forget") }
        }
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Progress(done: Long, total: Long) {
    val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}

@Composable
private fun IncomingCard(v: IncomingView) {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("From ${v.senderName}", style = MaterialTheme.typography.titleMedium)
            val what = if (v.fileCount == 0) "Message" else "${v.fileCount} file${if (v.fileCount != 1) "s" else ""}, ${Format.size(v.total)}"
            Muted("$what  ·  ${stateText(v)}")
            if (v.state == "accepted" && v.fileCount > 0) {
                Progress(v.received, v.total)
                Muted("${Format.size(v.received)} of ${Format.size(v.total)}")
            }
            for (name in v.saved.take(5)) Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (v.saved.size > 5) Muted("and ${v.saved.size - 5} more")
            if (v.text.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(v.text, style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (v.text.isNotEmpty() && v.state == "done") TextButton(onClick = { copy(context, v.text) }) { Text("Copy text") }
                when (v.state) {
                    "waiting" -> {
                        TextButton(onClick = { Engine.answer(v.id, false) }) { Text("Decline") }
                        Button(onClick = { Engine.answer(v.id, true) }) { Text("Accept") }
                    }
                    "accepted" -> TextButton(onClick = { Engine.cancelIncoming(v.id) }) { Text("Cancel") }
                    else -> TextButton(onClick = { Engine.dismissIncoming(v.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun stateText(v: IncomingView) = when (v.state) {
    "waiting" -> "waiting for your answer"
    "accepted" -> "receiving"
    "done" -> if (v.fileCount == 0) "received" else "saved"
    "declined" -> "declined"
    "expired" -> "not answered in time"
    "cancelled" -> "cancelled" + if (v.saved.isNotEmpty()) " (${v.saved.size} saved)" else ""
    else -> v.state
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Received text", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

@Composable
private fun OutgoingCard(v: OutgoingView) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("To ${v.deviceName}", style = MaterialTheme.typography.titleMedium)
            val what = if (v.fileCount == 0) "Message" else "${v.fileCount} file${if (v.fileCount != 1) "s" else ""}, ${Format.size(v.total)}"
            Muted("$what  ·  ${v.message.ifEmpty { "Connecting" }}")
            val active = v.state == SendJob.State.CONNECTING || v.state == SendJob.State.WAITING || v.state == SendJob.State.SENDING
            if (v.state == SendJob.State.SENDING) {
                Progress(v.sent, v.total)
                Muted(listOf("${Format.size(v.sent)} of ${Format.size(v.total)}", Format.speed(v.speed), Format.eta(v.etaSeconds))
                    .filter { it.isNotEmpty() }.joinToString("  ·  "))
                if (v.current.isNotEmpty()) Muted(v.current)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (active) TextButton(onClick = { Engine.cancelSend(v.key) }) { Text("Cancel") }
                else TextButton(onClick = { Engine.dismissSend(v.key) }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun DebugCard(s: UiState) {
    Card {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Conformance mode")
                Muted("Debug builds only. Answers offers by the sender's name, for the conformance tests. Never use it on a real network.")
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = s.conformance, onCheckedChange = { Engine.setConformance(it) })
        }
    }
}

@Composable
private fun OfferDialog(v: IncomingView) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${v.senderName} wants to send") },
        text = {
            Column {
                if (v.fileCount > 0) {
                    Text("${v.fileCount} file${if (v.fileCount != 1) "s" else ""}, ${Format.size(v.total)}")
                    for (n in v.firstNames) Muted(n)
                    if (v.fileCount > v.firstNames.size) Muted("and ${v.fileCount - v.firstNames.size} more")
                }
                if (v.text.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(v.text.take(1000))
                }
                Spacer(Modifier.height(8.dp))
                Muted(buildString {
                    append("From ${v.ip}")
                    if (v.senderShortId.isNotEmpty()) append(", ID ${v.senderShortId} (not verified)")
                })
            }
        },
        confirmButton = { Button(onClick = { Engine.answer(v.id, true) }) { Text("Accept") } },
        dismissButton = { OutlinedButton(onClick = { Engine.answer(v.id, false) }) { Text("Decline") } },
    )
}

@Composable
private fun TextDialog(d: Device, onDone: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Text to ${d.name}") },
        text = { OutlinedTextField(text, { text = it.take(20_000) }, Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = {
            Button(onClick = { onSend(text); onDone() }, enabled = text.isNotBlank()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun AddByIpDialog(onDone: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add by IP address") },
        text = {
            Column {
                OutlinedTextField(address, { address = it.trim(); error = null }, Modifier.fillMaxWidth(),
                    placeholder = { Text("192.168.1.20") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(8.dp))
                Muted("The address is shown on the other device. Add :port only if it isn't 47820.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(enabled = address.isNotEmpty() && !busy, onClick = {
                busy = true
                Engine.addByIp(address) { e -> busy = false; if (e == null) onDone() else error = e }
            }) { Text(if (busy) "Looking" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Device name") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(63) }, Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Muted("Other devices see this name.")
            }
        },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { Engine.setName(name); onDone() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
