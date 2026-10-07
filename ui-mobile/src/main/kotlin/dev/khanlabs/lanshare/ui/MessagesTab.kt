package dev.khanlabs.lanshare.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.khanlabs.lanshare.net.ChatMessage
import dev.khanlabs.lanshare.net.ChatStore
import dev.khanlabs.lanshare.net.Conversation
import dev.khanlabs.lanshare.net.Device
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.UiState
import java.util.Date

/** The Messages tab: your conversations, newest first, then the devices nearby that you haven't written to yet. */
@Composable
internal fun MessagesTab(s: UiState, onOpen: (peer: String) -> Unit) {
    val context = LocalContext.current
    val convs = remember(s.messages) { ChatStore.conversations(s.messages) }
    val devices = s.nearby + s.added
    val active = s.active
    val others = active.filter { d -> convs.none { it.peer == d.id } }
    var deleteFor by remember { mutableStateOf<Conversation?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (convs.isEmpty() && others.isEmpty()) item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("No devices found yet")
                    Spacer(Modifier.height(4.dp))
                    Muted("Open Amanah on another device on the same Wi-Fi. It shows up here and you can write to it.")
                }
            }
        }
        if (convs.isNotEmpty()) item { Muted("Press and hold a conversation to delete it.") }
        items(convs, key = { "chat:" + it.peer }) { c ->
            val live = active.any { it.id == c.peer }
            ConversationRow(c, live, deviceModel = devices.firstOrNull { it.id == c.peer }?.model ?: "", context,
                onLongClick = { deleteFor = c }) { onOpen(c.peer) }
        }
        if (others.isNotEmpty()) {
            item { SectionHeader(if (convs.isEmpty()) "Write to" else "Nearby, no messages yet") }
            items(others, key = { "dev:" + it.id }) { d -> NearbyRow(d) { onOpen(d.id) } }
        }
    }
    deleteFor?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Delete this conversation?") },
            text = { Text("The conversation with ${c.name.ifEmpty { "this device" }} is removed from this phone. The other device keeps its copy, and its next message asks you first.") },
            confirmButton = { Button(onClick = { deleteFor = null; Engine.forgetChat(c.peer) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Avatar(model: String, live: Boolean) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(40.dp)) {}
        Glyph(deviceIcon(model), if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    c: Conversation, live: Boolean, deviceModel: String, context: Context, onLongClick: () -> Unit, onClick: () -> Unit,
) {
    Surface(shape = CardDefaults.shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClickLabel = "Delete conversation", onLongClick = onLongClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(deviceModel, live)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name.ifEmpty { "Unknown device" }, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                val prefix = if (c.last.incoming) "" else "You: "
                Text(prefix + c.last.text.replace('\n', ' '), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!live) Muted("Not nearby")
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Muted(whenText(context, c.last.at))
                if (c.unread > 0) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Text(c.unread.toString(), Modifier.padding(horizontal = 7.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(d: Device, onClick: () -> Unit) {
    Surface(shape = CardDefaults.shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(d.model, true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(d.kind())
            }
            Glyph(Icons.ChevronRight)
        }
    }
}

// --- one conversation

private sealed interface Line {
    data class Day(val label: String) : Line
    data class Msg(val m: ChatMessage) : Line
}

private fun dayLabel(context: Context, at: Long): String = when {
    DateUtils.isToday(at) -> "Today"
    DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    else -> DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL)
}

private fun lines(context: Context, thread: List<ChatMessage>): List<Line> {
    val out = ArrayList<Line>()
    var last = ""
    for (m in thread) {
        val day = dayLabel(context, m.at)
        if (day != last) {
            last = day
            out += Line.Day(day)
        }
        out += Line.Msg(m)
    }
    return out
}

/** One conversation, full screen: the texts, and a box to write the next one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadScreen(peer: String, s: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val thread = remember(s.messages, peer) { s.messages.filter { it.peer == peer } }
    val rows = remember(thread) { lines(context, thread) }
    val device = (s.nearby + s.added).firstOrNull { it.id == peer }
    val title = device?.name ?: thread.lastOrNull { it.name.isNotEmpty() }?.name ?: "Messages"
    val known = thread.any { it.state == "done" }
    var draft by rememberSaveable(peer) { mutableStateOf("") }
    var deleteOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // while this screen is in front, a new text for it is read at once and raises no notification
    LifecycleResumeEffect(peer) {
        Engine.setViewing(peer)
        onPauseOrDispose { Engine.setViewing(null) }
    }
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
        Engine.markRead(peer)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(if (s.active.any { it.id == peer }) "Nearby" else "Not nearby right now")
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Back, contentDescription = "Back") } },
                actions = {
                    if (thread.isNotEmpty()) IconButton(onClick = { deleteOpen = true }) {
                        Glyph(Icons.Delete, description = "Delete conversation")
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val hint = when {
                    device == null -> "This device isn't nearby right now. Open Amanah on it and you can write to it again."
                    !known -> "The first message asks them to accept it. After that, messages between you two arrive without asking."
                    else -> ""
                }
                if (hint.isNotEmpty()) Muted(hint, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        draft, { draft = it.take(Engine.MAX_MESSAGE_CHARS) }, Modifier.weight(1f),
                        placeholder = { Text("Message") }, maxLines = 5, shape = RoundedCornerShape(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { device?.let { Engine.sendMessage(it, draft); draft = "" } },
                        enabled = device != null && draft.isNotBlank(),
                    ) { Glyph(Icons.Send, MaterialTheme.colorScheme.onPrimary, description = "Send") }
                }
            }
        },
    ) { pad ->
        if (rows.isEmpty()) Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Muted("No messages yet. Say hello.")
        } else LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(rows, key = { if (it is Line.Msg) it.m.id else (it as Line.Day).label }) { line ->
                when (line) {
                    is Line.Day -> Text(line.label, Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is Line.Msg -> Bubble(line.m, context)
                }
            }
        }
    }

    if (deleteOpen) AlertDialog(
        onDismissRequest = { deleteOpen = false },
        title = { Text("Delete this conversation?") },
        text = { Text("It is removed from this phone. The other device keeps its copy, and its next message asks you first.") },
        confirmButton = { Button(onClick = { deleteOpen = false; Engine.forgetChat(peer) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: ChatMessage, context: Context) {
    val mine = !m.incoming
    val time = DateFormat.getTimeFormat(context).format(Date(m.at))
    val bad = mine && (m.state == "failed" || m.state == "declined")
    val status = when {
        !mine || m.state == "done" -> time
        m.state == "sending" -> "$time  ·  Sending…"
        m.state == "declined" -> "$time  ·  ${m.note.ifEmpty { "Declined" }}"
        else -> "$time  ·  Not delivered" + if (m.note.isNotEmpty()) ": ${m.note}" else ""
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, if (mine) 4.dp else 16.dp, if (mine) 16.dp else 4.dp),
            color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh,
            // press and hold copies the text
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = { copyText(context, m.text) }),
        ) {
            Text(m.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Text(status, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall,
            color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (bad) TextButton(onClick = { Engine.retryMessage(m.id) }) { Text("Try again") }
    }
}
