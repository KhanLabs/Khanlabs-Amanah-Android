package dev.khanlabs.lanshare.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.net.SendItem
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.Format
import dev.khanlabs.lanshare.transfer.Items
import dev.khanlabs.lanshare.transfer.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Files or text another app shared with this one, waiting for the person to pick a device. */
data class Shared(val uris: List<Uri> = emptyList(), val text: String = "") {
    val isEmpty: Boolean get() = uris.isEmpty() && text.isEmpty()
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Send("Send", Icons.Send), Activity("Activity", Icons.Activity), Settings("Settings", Icons.Settings)
}

/** Something picked, waiting for a device to be chosen. */
private class Pending(
    val what: String,
    val uris: List<Uri> = emptyList(),
    val items: List<SendItem> = emptyList(),
    val text: String = "",
    val fromShare: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(shared: Shared, onShareDone: () -> Unit, onReceivingChange: (Boolean) -> Unit) {
    val s by Engine.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableStateOf(Tab.Send) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var receivingOpen by remember { mutableStateOf(false) }
    var helpOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<Device?>(null) }
    var chooseFor by remember { mutableStateOf<Device?>(null) }   // a device was tapped: what to send it?
    var textFor by remember { mutableStateOf<Device?>(null) }     // compose text for this device
    var textFirst by remember { mutableStateOf(false) }           // compose text, then pick a device
    var dest by remember { mutableStateOf<Device?>(null) }        // the device a running pick will go to, if known
    var pending by remember { mutableStateOf<Pending?>(null) }
    var reading by remember { mutableStateOf(false) }

    /** Content is ready: send it to the device already chosen, or ask which device. */
    fun ready(p: Pending) {
        val d = dest
        dest = null
        if (d != null) sendPending(context, scope, d, p) else pending = p
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) dest = null else ready(Pending(itemsText(uris.size, "file"), uris = uris))
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) dest = null else ready(Pending(itemsText(uris.size, "photo or video"), uris = uris))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) {
            dest = null
        } else scope.launch {
            reading = true
            val t = withContext(Dispatchers.IO) { Items.fromTree(context, tree) }
            reading = false
            when {
                t.tooMany -> toast(context, "That folder has more than 10,000 files. Send it in parts.")
                t.items.isEmpty() -> toast(context, "That folder has no files to send.")
                else -> {
                    if (t.skipped > 0) toast(context, "${t.skipped} item(s) couldn't be read and were left out")
                    ready(Pending("folder ${t.folderName} (${itemsText(t.items.size, "file")}, ${Format.size(t.bytes)})", items = t.items))
                }
            }
        }
    }

    fun startPick(kind: Kind) {
        when (kind) {
            Kind.Files -> pickFiles.launch(arrayOf("*/*"))
            Kind.Media -> pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            Kind.Folder -> pickFolder.launch(null)
            Kind.Text -> if (dest != null) { textFor = dest; dest = null } else textFirst = true
        }
    }

    // something shared from another app: go to the device list at once
    LaunchedEffect(shared) {
        if (!shared.isEmpty) {
            tab = Tab.Send
            aboutOpen = false
            pending = Pending(
                if (shared.uris.isEmpty()) "text" else itemsText(shared.uris.size, "item"),
                uris = shared.uris, text = shared.text, fromShare = true,
            )
        }
    }
    fun closePending() {
        if (pending?.fromShare == true) onShareDone()
        pending = null
    }

    if (aboutOpen) {
        BackHandler { aboutOpen = false }
        AboutScreen(onBack = { aboutOpen = false })
    } else {
        BackHandler(enabled = tab != Tab.Send) { tab = Tab.Send }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("LAN Share", fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = { ReceivingChip(s) { receivingOpen = true }; Spacer(Modifier.width(12.dp)) },
                )
            },
            bottomBar = {
                Column {
                    // the labels stop growing at 130%: the bar is a fixed height, and the words are short
                    val d = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.3f))) {
                        NavigationBar(Modifier.height(64.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
                            for (t in Tab.entries) NavigationBarItem(
                                selected = tab == t, onClick = { tab = t }, alwaysShowLabel = true,
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label, maxLines = 1) },
                            )
                        }
                    }
                    MadeWithBar()
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.Send -> SendTab(
                        s,
                        onKind = { dest = null; startPick(it) },
                        onDevice = { chooseFor = it },
                        onDetails = { detailsFor = it },
                        onAddByIp = { addOpen = true },
                        onHelp = { helpOpen = true },
                    )
                    Tab.Activity -> ActivityTab(s.history)
                    Tab.Settings -> SettingsTab(s, onReceivingChange, { renameOpen = true }, { clearOpen = true }, { aboutOpen = true })
                }
            }
        }
    }

    // --- sheets and dialogs

    if (!s.conformance) s.incoming.lastOrNull { it.state == "waiting" }?.let { OfferSheet(it) }

    pending?.let { p ->
        DevicePickerSheet(p.what, s, onPick = { d ->
            sendPending(context, scope, d, p)
            closePending()
        }, onAddByIp = { addOpen = true }, onHelp = { helpOpen = true }, onDismiss = ::closePending)
    }
    chooseFor?.let { d ->
        WhatToSendSheet(d, onChoose = { kind -> dest = d; chooseFor = null; startPick(kind) }, onDismiss = { chooseFor = null })
    }
    if (receivingOpen) ReceivingSheet(s, onReceivingChange, onRename = { renameOpen = true }, onDismiss = { receivingOpen = false })
    if (helpOpen) HelpSheet(s, onAddByIp = { helpOpen = false; addOpen = true }, onDismiss = { helpOpen = false })
    detailsFor?.let { d ->
        DeviceSheet(d, added = s.added.any { it.id == d.id }, onForget = { Engine.forget(d); detailsFor = null }, onDismiss = { detailsFor = null })
    }

    textFor?.let { d ->
        TextDialog("Text to ${d.name}", onDone = { textFor = null }) { text -> Engine.send(d, emptyList(), text) }
    }
    if (textFirst) TextDialog("Send text", onDone = { textFirst = false }, sendLabel = "Next") { text -> pending = Pending("text", text = text) }
    if (addOpen) AddByIpDialog(onDone = { addOpen = false })
    if (renameOpen) RenameDialog(s.name, onDone = { renameOpen = false })
    if (clearOpen) ConfirmDialog(
        "Clear activity?", "This removes the list of what you sent and received. The files themselves stay where they are.",
        "Clear", onConfirm = { Engine.clearHistory() }, onDone = { clearOpen = false },
    )
    if (reading) AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Reading the folder") },
        text = { Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Text("Counting the files.")
        } },
    )
}

private fun itemsText(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

private fun sendPending(context: Context, scope: CoroutineScope, device: Device, p: Pending) {
    scope.launch {
        val (fromUris, skipped) = if (p.uris.isEmpty()) emptyList<SendItem>() to 0
                                  else withContext(Dispatchers.IO) { Items.fromUris(context, p.uris) }
        if (skipped > 0) toast(context, "$skipped item(s) couldn't be read and were left out")
        val all = fromUris + p.items
        if (all.isNotEmpty() || p.text.isNotEmpty()) Engine.send(device, all, p.text)
    }
}

// --- the top bar's status chip

@Composable
private fun ReceivingChip(s: UiState, onClick: () -> Unit) {
    val (label, dot) = when {
        s.listenError != null -> "Problem" to MaterialTheme.colorScheme.error
        !s.receiving -> "Not receiving" to MaterialTheme.colorScheme.outline
        s.addresses.isEmpty() -> "No Wi-Fi" to MaterialTheme.colorScheme.outline
        s.port == null -> "Starting" to MaterialTheme.colorScheme.outline
        else -> "Receiving" to MaterialTheme.colorScheme.primary
    }
    // a status light, not body text: it stops growing at 130% so the title keeps its room
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, 1.3f))) {
        AssistChip(
            onClick = onClick,
            label = { Text(label, maxLines = 1) },
            leadingIcon = { Surface(shape = CircleShape, color = dot, modifier = Modifier.size(10.dp)) {} },
        )
    }
}

// --- dialogs

@Composable
private fun TextDialog(title: String, onDone: () -> Unit, sendLabel: String = "Send", onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(20_000) }, Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = {
            Button(onClick = { onSend(text); onDone() }, enabled = text.isNotBlank()) { Text(sendLabel) }
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

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = { onConfirm(); onDone() }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
