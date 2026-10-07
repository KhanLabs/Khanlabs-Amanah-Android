package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.UiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTab(
    s: UiState,
    onReceivingChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onClearHistory: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember { versionName(context) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onRename), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Device name", style = MaterialTheme.typography.titleMedium)
                            Muted("Other devices see this name.")
                            Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = onRename) { Text("Rename") }
                    }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Receive files", style = MaterialTheme.typography.titleMedium)
                            Muted("Others on your Wi-Fi can send to this phone. You always choose to accept or decline.")
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = s.receiving, onCheckedChange = onReceivingChange)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Muted("Files are saved in")
                            Text(s.folder, style = MaterialTheme.typography.bodyLarge)
                        }
                        OutlinedButton(onClick = { showFolder(context) }) { Text("Show") }
                    }
                }
            }
        }
        item {
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow VPN addresses", style = MaterialTheme.typography.titleMedium)
                        Muted("Turn this on only if you reach your devices through a VPN such as Tailscale. Amanah then also connects to addresses from 100.64.0.0 to 100.127.255.255. Off, it connects only to this Wi-Fi network.")
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = s.allowVpn, onCheckedChange = { Engine.setAllowVpn(it) })
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Tab shown when you open Amanah", style = MaterialTheme.typography.titleMedium)
                    Muted("Pick the tab you want to see first.")
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in Tab.entries) FilterChip(
                            selected = s.startTab == t.name, onClick = { Engine.setStartTab(t.name) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
        item {
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show devices that are not nearby", style = MaterialTheme.typography.titleMedium)
                        Muted("On: devices you saved that do not answer are listed under Not nearby, and you can remove them. Off: the Send tab lists only devices found right now. Saved devices stay saved and come back when they are nearby.")
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = s.showInactive, onCheckedChange = { Engine.setShowInactive(it) })
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Activity", style = MaterialTheme.typography.titleMedium)
                    Muted("The list of what you sent and received is kept on this phone only.")
                    OutlinedButton(onClick = onClearHistory, Modifier.padding(top = 8.dp), enabled = s.history.isNotEmpty()) {
                        Text("Clear activity")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().clickable(onClick = onAbout)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("About and support", style = MaterialTheme.typography.titleMedium)
                        Muted("Free. No ads. No tracking. No account.")
                    }
                    Glyph(Icons.ChevronRight)
                }
            }
        }
        if (s.conformanceAllowed) item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Debug builds only", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Conformance mode")
                            Muted("Answers offers by the sender's name, for the conformance tests. Never use it on a real network.")
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = s.conformance, onCheckedChange = { Engine.setConformance(it) })
                    }
                    Text("Screen", Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        FilterChip(selected = !s.debugSmallScreen, onClick = { Engine.setDebugSmallScreen(false); activityOf(context)?.recreate() },
                            label = { Text("Normal") })
                        FilterChip(selected = s.debugSmallScreen, onClick = { Engine.setDebugSmallScreen(true); activityOf(context)?.recreate() },
                            label = { Text("Small (320 dp)") })
                    }
                    Text("Text size", Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        for (f in listOf(1f, 1.5f, 2f)) FilterChip(
                            selected = s.debugFontScale == f, onClick = { Engine.setDebugFontScale(f); activityOf(context)?.recreate() },
                            label = { Text("${(f * 100).toInt()}%") },
                        )
                    }
                }
            }
        }
        item { Muted("Version $version", modifier = Modifier.padding(top = 4.dp)) }
    }
}

private fun activityOf(context: android.content.Context): android.app.Activity? {
    var c = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
