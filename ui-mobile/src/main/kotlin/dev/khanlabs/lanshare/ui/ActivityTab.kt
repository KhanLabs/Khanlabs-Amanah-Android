package dev.khanlabs.lanshare.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.khanlabs.lanshare.transfer.Engine
import dev.khanlabs.lanshare.transfer.Format
import dev.khanlabs.lanshare.transfer.History
import dev.khanlabs.lanshare.transfer.HistoryEntry

private enum class Filter(val label: String) { All("All"), In("Received"), Out("Sent") }

/** Everything this phone sent or received lately, newest first. Kept on the phone only. */
@Composable
internal fun ActivityTab(history: List<HistoryEntry>, onStats: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(Filter.All) }
    val shown = history.filter { filter == Filter.All || (filter == Filter.In) == it.incoming }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (history.isNotEmpty()) item { StatsSummaryCard(history, onStats) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in Filter.entries) FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        if (shown.isEmpty()) item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(if (history.isEmpty()) "Nothing yet" else "Nothing here")
                    Spacer(Modifier.height(4.dp))
                    Muted(if (history.isEmpty()) "Files and text you send or receive show up here. This list stays on your phone."
                          else "No ${filter.label.lowercase()} items.")
                }
            }
        }
        items(shown, key = { it.id }) { HistoryCard(it) }
    }
}

@Composable
private fun HistoryCard(e: HistoryEntry) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (e.incoming) Icons.ArrowDown else Icons.ArrowUp, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (e.incoming) "From ${e.peer}" else "To ${e.peer}", style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(listOf(countText(e.files, e.bytes), stateWord(e)).filter { it.isNotEmpty() }.joinToString("  ·  "))
                    Muted(whenText(context, e.at))
                }
            }
            if (e.note.isNotEmpty()) Muted(e.note, modifier = Modifier.padding(top = 4.dp))
            if (open) {
                Spacer(Modifier.height(8.dp))
                if (e.items.isNotEmpty()) {
                    FileList(e)
                } else { // an entry from before the files were kept one by one: the names are all there is
                    for (n in e.names.take(8)) Text(n, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.files > 8) Muted("and ${e.files - 8} more")
                }
                if (e.incoming && e.names.size == e.files) Thumbnails(e.names)
                if (e.text.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(e.text, style = MaterialTheme.typography.bodyLarge, maxLines = 12, overflow = TextOverflow.Ellipsis)
                }
                if (e.incoming) FinishedActions(context, if (e.names.size == e.files) e.names else emptyList(), e.text)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { Engine.removeHistory(e.id) }) { Glyph(Icons.Delete, description = "Remove from activity") }
                }
            }
        }
    }
}

/** Every file of one entry with its size and what became of it. Eight show first; the rest open with one tap. */
@Composable
private fun FileList(e: HistoryEntry) {
    var all by remember { mutableStateOf(false) }
    val shown = if (all) e.items else e.items.take(FILES_SHOWN)
    for (f in shown) {
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Muted(listOf(Format.size(f.size), fileStateWord(f.state, e.incoming)).filter { it.isNotEmpty() }.joinToString("  ·  "))
        }
    }
    if (e.items.size > shown.size) TextButton(onClick = { all = true }) { Text("Show all ${e.items.size}") }
    val total = e.files + e.filesSkipped
    // the phone keeps the first hundred files of a very large send
    if (all && e.items.size >= History.MAX_ITEMS && total > e.items.size) Muted("and ${total - e.items.size} more")
}

private const val FILES_SHOWN = 8

/** The word after a file's size; nothing for a file that went across. */
private fun fileStateWord(state: String, incoming: Boolean) = when (state) {
    "done" -> ""
    "skipped" -> "already there"
    "failed" -> "failed"
    else -> if (incoming) "not received" else "not sent"
}

private fun stateWord(e: HistoryEntry) = when (e.state) {
    "done" -> ""
    "partial" -> "stopped early"
    "declined" -> "declined"
    "failed" -> "failed"
    "cancelled" -> "cancelled"
    else -> e.state
}
