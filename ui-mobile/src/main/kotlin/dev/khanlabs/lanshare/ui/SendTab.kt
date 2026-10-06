package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.net.SendJob
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.Format
import dev.khanlabs.lanshare.transfer.IncomingView
import dev.khanlabs.lanshare.transfer.OutgoingView
import dev.khanlabs.lanshare.transfer.UiState

/**
 * The first tab: what's moving now, four ways to pick something to send, and the devices nearby. Sending starts
 * from either end: pick content then a device, or tap a device then pick content.
 */
@Composable
internal fun SendTab(
    s: UiState,
    onKind: (Kind) -> Unit,
    onDevice: (Device) -> Unit,
    onDetails: (Device) -> Unit,
    onScan: () -> Unit,
    onAddByIp: () -> Unit,
    onHelp: () -> Unit,
    onShareAnyone: () -> Unit,
    onOwnWifi: () -> Unit,
) {
    val devices = s.nearby + s.added
    val live = s.incoming.filter { it.state != "waiting" }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (live.isNotEmpty() || s.outgoing.isNotEmpty()) {
            item { SectionHeader("Now") }
            items(live, key = { "in:" + it.id }) { IncomingCard(it) }
            items(s.outgoing, key = { "out:" + it.key }) { OutgoingCard(it) }
        }
        item { SectionHeader("Send") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KindButton(Icons.File, "Files", Modifier.weight(1f)) { onKind(Kind.Files) }
                    KindButton(Icons.Image, "Photos and videos", Modifier.weight(1f)) { onKind(Kind.Media) }
                }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KindButton(Icons.Folder, "Folder", Modifier.weight(1f)) { onKind(Kind.Folder) }
                    KindButton(Icons.Chat, "Message", Modifier.weight(1f)) { onKind(Kind.Text) }
                }
            }
        }
        item { ShareAnyoneCard(s.share, onShareAnyone) }
        item { OwnWifiCard(s.ownWifi, onOwnWifi) }
        item {
            SectionHeader("Nearby") {
                IconButton(onClick = { Engine.refreshNearby() }) { Glyph(Icons.Refresh, description = "Refresh") }
            }
        }
        if (devices.isEmpty()) item { NoDevices(s, onScan, onAddByIp, onHelp) }
        items(devices, key = { "dev:" + it.id }) { d -> DeviceRow(d, onClick = { onDevice(d) }, onDetails = { onDetails(d) }) }
        if (devices.isNotEmpty()) item { FindActions(onScan, onAddByIp, onHelp) }
    }
}

@Composable
private fun KindButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier.heightIn(min = 88.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Glyph(icon, MaterialTheme.colorScheme.primary, 28)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun NoDevices(s: UiState, onScan: () -> Unit, onAddByIp: () -> Unit, onHelp: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("No devices found yet")
            Spacer(Modifier.height(4.dp))
            Muted(if (s.receiving) "Open Amanah on the other device, on the same Wi-Fi."
                  else "Turn on receiving to look for other devices, or use Add by IP.")
            Spacer(Modifier.height(8.dp))
            FindActions(onScan, onAddByIp, onHelp)
        }
    }
}

/** Ways to connect when a device is missing. Wraps onto more lines at large text sizes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FindActions(onScan: () -> Unit, onAddByIp: () -> Unit, onHelp: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        OutlinedButton(onClick = onScan) {
            Glyph(Icons.Qr, MaterialTheme.colorScheme.primary, 18)
            Spacer(Modifier.width(8.dp))
            Text("Scan QR code")
        }
        OutlinedButton(onClick = onAddByIp) { Text("Add by IP") }
        TextButton(onClick = onHelp) { Text("Not showing up?") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(d: Device, onClick: () -> Unit, onDetails: () -> Unit) {
    Surface(shape = CardDefaults.shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onDetails).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(40.dp)) {}
                Glyph(deviceIcon(d.model), MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(d.kind())
            }
            IconButton(onClick = onDetails) { Glyph(Icons.Info, description = "Details") }
        }
    }
}

// --- live transfers

@Composable
internal fun IncomingCard(v: IncomingView) {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.ArrowDown, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("From ${v.senderName}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("${countText(v.fileCount, v.total)}  ·  ${stateText(v)}")
                }
            }
            if (v.state == "accepted" && v.fileCount > 0) {
                Progress(v.received, v.total)
                Muted("${Format.size(v.received)} of ${Format.size(v.total)}")
            }
            if (v.state != "accepted") for (name in v.saved.take(3)) {
                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (v.saved.size > 3 && v.state != "accepted") Muted("and ${v.saved.size - 3} more")
            if (v.text.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(v.text, style = MaterialTheme.typography.bodyLarge)
            }
            if (v.state == "done" || v.state == "cancelled") {
                FinishedActions(context, v.saved, v.text, v.failed)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (v.state == "accepted") TextButton(onClick = { Engine.cancelIncoming(v.id) }) { Text("Cancel") }
                else TextButton(onClick = { Engine.dismissIncoming(v.id) }) { Text("Dismiss") }
            }
        }
    }
}

/** Open, Show folder, Share and Copy text for what just arrived. */
@Composable
internal fun FinishedActions(context: android.content.Context, saved: List<String>, text: String, failed: Int = 0) {
    if (failed > 0) Muted("$failed file${if (failed != 1) "s" else ""} could not be saved.")
    if (saved.isEmpty() && text.isEmpty()) return
    // a folder arrives under one top name; "Show folder" opens that one
    val top = saved.firstOrNull()?.takeIf { '/' in it }?.substringBefore('/')
    val inside = if (top != null && saved.all { it.startsWith("$top/") }) top else ""
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved.size == 1) OutlinedButton(onClick = { openSaved(context, saved[0]) }, Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Open", maxLines = 1) }
        if (saved.isNotEmpty()) OutlinedButton(onClick = { showFolder(context, inside) }, Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Show", maxLines = 1) }
        if (saved.isNotEmpty() && saved.size <= 100) OutlinedButton(onClick = { shareSaved(context, saved) }, Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Share", maxLines = 1) }
        if (text.isNotEmpty()) OutlinedButton(onClick = { copyText(context, text) }, Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Copy text", maxLines = 1) }
    }
}

internal fun stateText(v: IncomingView) = when (v.state) {
    "waiting" -> "waiting for your answer"
    "accepted" -> "receiving"
    "done" -> if (v.fileCount == 0) "received" else "saved"
    "declined" -> "declined"
    "expired" -> "not answered in time"
    "cancelled" -> "cancelled" + if (v.saved.isNotEmpty()) " (${v.saved.size} saved)" else ""
    else -> v.state
}

@Composable
internal fun OutgoingCard(v: OutgoingView) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.ArrowUp, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("To ${v.deviceName}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("${countText(v.fileCount, v.total)}  ·  ${v.message.ifEmpty { "Connecting" }}")
                }
            }
            val active = v.state == SendJob.State.CONNECTING || v.state == SendJob.State.WAITING || v.state == SendJob.State.SENDING
            if (v.state == SendJob.State.SENDING) {
                Progress(v.sent, v.total)
                Muted(listOf("${Format.size(v.sent)} of ${Format.size(v.total)}", Format.speed(v.speed), Format.eta(v.etaSeconds))
                    .filter { it.isNotEmpty() }.joinToString("  ·  "))
                if (v.current.isNotEmpty()) Muted(v.current)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (active) TextButton(onClick = { Engine.cancelSend(v.key) }) { Text("Cancel") }
                else TextButton(onClick = { Engine.dismissSend(v.key) }) { Text("Dismiss") }
            }
        }
    }
}
