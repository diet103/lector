package io.github.diet103.lector.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.history.HistoryEntry
import io.github.diet103.lector.history.ReadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything Lector has read, newest first (v0.2).
 *
 * Each row says whether replaying it is free. That isn't decoration: the audio cache is a 50 MB
 * LRU, so an old read's bytes may be gone, and replaying it would spend characters again. Saying so
 * up front is the difference between a history and a trap.
 */
@Composable
fun HistoryScreen(
    container: AppContainer,
    onOpen: (HistoryEntry) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    val revision by container.history.revision.collectAsState()
    val scope = rememberCoroutineScope()

    // Debounced: every keystroke would otherwise re-query on the main thread's behalf.
    LaunchedEffect(query, revision) {
        if (query.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
        entries = withContext(Dispatchers.IO) { container.history.search(query) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear reading history?") },
            text = {
                Text(
                    "This removes every saved read. Audio already downloaded stays cached, so " +
                        "anything you read again soon is still free."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { container.history.clearAll() }
                        confirmClear = false
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                if (entries.isNotEmpty() || query.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            if (entries.isEmpty()) {
                EmptyState(searching = query.isNotBlank())
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(entries, key = { it.key }) { entry ->
                        HistoryRow(
                            entry = entry,
                            free = container.isFullyCached(entry),
                            onOpen = { onOpen(entry) },
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { container.history.delete(entry.key) }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            if (searching) {
                "Nothing here matches that."
            } else {
                "Nothing yet. Anything you have Lector read will show up here."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    free: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title ?: entry.snippet(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${entry.source.label()} · ${relativeTime(entry.lastReadAt)} · ${costLabel(entry, free)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onDelete) { Text("Delete") }
    }
}

private fun ReadSource.label(): String = when (this) {
    ReadSource.SELECTION -> "Selected"
    ReadSource.SHARED_TEXT -> "Shared"
    ReadSource.LINK -> "Link"
    ReadSource.SCREENSHOT -> "Screenshot"
    ReadSource.TRY_IT -> "Typed"
}

/**
 * The honest bit. Once the LRU has evicted a read's audio, playing it again is a fresh synthesis
 * and costs what it cost the first time.
 */
private fun costLabel(entry: HistoryEntry, free: Boolean): String =
    if (free) "free to replay" else "~${entry.text.length} characters to replay"

private fun relativeTime(epochMillis: Long): String {
    val elapsed = System.currentTimeMillis() - epochMillis
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}

private const val SEARCH_DEBOUNCE_MS = 200L
