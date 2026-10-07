package dev.khanlabs.lanshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.UiState

/**
 * Settings, in groups. Every row says what it is set to underneath, so the screen reads without opening anything.
 * Things that are rarely changed are folded under Advanced.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTab(
    s: UiState,
    onReceivingChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onShowQr: () -> Unit,
    onClearHistory: () -> Unit,
    onAbout: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember { versionName(context) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Group("This device") {
                SettingRow("Device name", s.name, onClick = onRename) { Glyph(Icons.ChevronRight) }
                Rule()
                SettingRow("My QR code", "Another device scans it to connect", onClick = onShowQr) { Glyph(Icons.ChevronRight) }
            }
        }
        item {
            Group("Receiving") {
                SettingRow(
                    "Receiving",
                    if (s.receiving) "On. You always choose to accept or decline." else "Off. Others can't send to you.",
                ) { Switch(checked = s.receiving, onCheckedChange = onReceivingChange) }
                Rule()
                SettingRow("Save files to", s.folder, onClick = { showFolder(context) }) { TextButton(onClick = { showFolder(context) }) { Text("Show") } }
            }
        }
        item {
            Group("Appearance") {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    Muted(THEMES.firstOrNull { it.first == s.theme }?.second ?: "Same as the phone")
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, label) in THEMES) FilterChip(selected = s.theme == key, onClick = { Engine.setTheme(key) }, label = { Text(label) })
                    }
                }
                Rule()
                Column(Modifier.padding(16.dp)) {
                    Text("Accent color", style = MaterialTheme.typography.bodyLarge)
                    Muted(accentFor(s.accent).label)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (a in AccentChoices) Swatch(a, selected = a.key == s.accent) { Engine.setAccent(a.key) }
                    }
                }
                Rule()
                Column(Modifier.padding(16.dp)) {
                    Text("Open on", style = MaterialTheme.typography.bodyLarge)
                    Muted("The tab you see first when you open Amanah")
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (t in Tab.entries) FilterChip(selected = s.startTab == t.name, onClick = { Engine.setStartTab(t.name) }, label = { Text(t.label) })
                    }
                }
            }
        }
        item {
            Group("Devices") {
                SettingRow(
                    "Show devices not nearby",
                    if (s.showInactive) "On. Saved devices that do not answer are listed, so you can remove them."
                    else "Off. Only devices found right now are listed. Saved ones come back when they are nearby.",
                ) { Switch(checked = s.showInactive, onCheckedChange = { Engine.setShowInactive(it) }) }
            }
        }
        item {
            Group("Privacy and data") {
                SettingRow(
                    "Clear activity",
                    if (s.history.isEmpty()) "Nothing kept" else "${s.history.size} items, kept on this phone only",
                    onClick = if (s.history.isEmpty()) null else onClearHistory,
                ) { if (s.history.isNotEmpty()) Glyph(Icons.Delete) }
            }
            Muted("No account, no ads, no tracking.", modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
        item {
            Group("Advanced") {
                SettingRow("Show advanced settings", if (advanced) "Shown" else "Hidden", onClick = { advanced = !advanced }) {
                    Glyph(if (advanced) Icons.ExpandLess else Icons.ExpandMore)
                }
                if (advanced) {
                    Rule()
                    SettingRow(
                        "Allow VPN addresses",
                        if (s.allowVpn) "On. Also connects to addresses from 100.64.0.0 to 100.127.255.255, for example Tailscale."
                        else "Off. Connects only to this Wi-Fi network. Turn on only if you reach your devices through a VPN.",
                    ) { Switch(checked = s.allowVpn, onCheckedChange = { Engine.setAllowVpn(it) }) }
                }
            }
        }
        item {
            Group("About") {
                SettingRow("About and support", "Version $version. Free, with no ads and no account.", onClick = onAbout) { Glyph(Icons.ChevronRight) }
            }
        }
        if (s.conformanceAllowed) item {
            Group("Debug builds only") {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Conformance mode")
                            Muted("Answers offers by the sender's name, for the conformance tests. Never use it on a real network.")
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = s.conformance, onCheckedChange = { Engine.setConformance(it) })
                    }
                    Text("Screen", Modifier.padding(top = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        FilterChip(selected = !s.debugSmallScreen, onClick = { Engine.setDebugSmallScreen(false); activityOf(context)?.recreate() },
                            label = { Text("Normal") })
                        FilterChip(selected = s.debugSmallScreen, onClick = { Engine.setDebugSmallScreen(true); activityOf(context)?.recreate() },
                            label = { Text("Small (320 dp)") })
                    }
                    Text("Text size", Modifier.padding(top = 12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        for (f in listOf(1f, 1.5f, 2f)) FilterChip(
                            selected = s.debugFontScale == f, onClick = { Engine.setDebugFontScale(f); activityOf(context)?.recreate() },
                            label = { Text("${(f * 100).toInt()}%") },
                        )
                    }
                }
            }
        }
    }
}

private val THEMES = listOf("system" to "System", "light" to "Light", "dark" to "Dark", "black" to "Black")

/** A heading and one card holding the group's rows. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionHeader(title)
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) { Column(content = content) }
    }
}

/** One setting: its name, what it is set to, and a switch, a chevron or a button at the right. The row opens when given [onClick]. */
@Composable
private fun SettingRow(title: String, summary: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Muted(summary)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun Rule() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A round color to tap. The chosen one has a ring around it, and says so for a screen reader. */
@Composable
private fun Swatch(a: AccentChoice, selected: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(a.swatch())
            .then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = a.label + if (selected) ", chosen" else "" },
    )
}

private fun activityOf(context: android.content.Context): android.app.Activity? {
    var c = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
