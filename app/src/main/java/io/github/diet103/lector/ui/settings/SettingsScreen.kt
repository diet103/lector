package io.github.diet103.lector.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.data.SettingsRepository
import io.github.diet103.lector.model.TtsFormat
import io.github.diet103.lector.model.TtsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Settings (PLAN §6 P6).
 *
 * The copy states costs plainly because every one of these choices spends the user's own money:
 * changing model or format re-synthesizes once (they're part of the cache key), while speed is
 * free forever (the player applies it).
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onPickVoice: () -> Unit,
    onOpenAbout: () -> Unit,
    onSignOut: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val settings = container.settings

    val model by settings.model.collectAsState()
    val format by settings.format.collectAsState()
    val speed by settings.speed.collectAsState()
    val maxChars by settings.maxChars.collectAsState()
    val deleteScreenshots by settings.deleteScreenshotAfterReading.collectAsState()
    val historyEnabled by settings.historyEnabled.collectAsState()
    val historyRevision by container.history.revision.collectAsState()
    var historyCount by remember { mutableStateOf(0) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    LaunchedEffect(historyRevision) {
        historyCount = withContext(Dispatchers.IO) { container.history.count() }
    }

    var confirmSignOut by remember { mutableStateOf(false) }
    var cacheNotice by remember { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDone) { Text("Done") }
            }

            OutlinedButton(onClick = onPickVoice, modifier = Modifier.fillMaxWidth()) {
                Text("Change voice")
            }

            SectionTitle("Speed")
            Text(
                "Applied as it plays, so changing this never costs characters.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("${"%.2f".format(speed)}×", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = speed,
                onValueChange = { settings.setSpeed(it) },
                valueRange = SettingsRepository.MIN_SPEED..SettingsRepository.MAX_SPEED,
                modifier = Modifier.fillMaxWidth()
            )

            SectionTitle("Model")
            Text(
                "Changing this re-reads the same text once, then it's cached.",
                style = MaterialTheme.typography.bodySmall
            )
            TtsModel.entries.forEach { option ->
                ChoiceRow(
                    label = option.label,
                    description = "${option.blurb} Up to ${"%,d".format(option.maxChars)} characters.",
                    selected = option == model,
                    onSelect = { settings.setModel(option) }
                )
            }

            SectionTitle("Audio quality")
            TtsFormat.entries.forEach { option ->
                ChoiceRow(
                    label = option.label,
                    description = option.blurb,
                    selected = option == format,
                    onSelect = { settings.setFormat(option) }
                )
            }

            SectionTitle("Length limit")
            Text(
                "Long selections are cut at a sentence end so a runaway page can't spend your " +
                    "whole balance. Lector always tells you when it trims.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("${"%,d".format(maxChars)} characters", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = maxChars.toFloat(),
                onValueChange = { settings.setMaxChars(it.roundToInt()) },
                valueRange = SettingsRepository.MIN_MAX_CHARS.toFloat()..model.maxChars.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SectionTitle("Screenshots")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Delete after reading", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Offer to remove the screenshot from your gallery once Lector has " +
                                "read it. Android asks you to confirm each time — it won't let " +
                                "any app delete your photos silently.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = deleteScreenshots,
                        onCheckedChange = { settings.setDeleteScreenshotAfterReading(it) }
                    )
                }
            }

            SectionTitle("History")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep a reading history", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Saves what you have Lector read so you can find it again. Stored only on " +
                            "this phone and never backed up. Screenshots are never saved — only " +
                            "the text read out of them.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = historyEnabled,
                    onCheckedChange = { settings.setHistoryEnabled(it) }
                )
            }
            OutlinedButton(
                onClick = { confirmClearHistory = true },
                enabled = historyCount > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (historyCount > 0) "Clear history ($historyCount)" else "History is empty"
                )
            }

            SectionTitle("Storage")
            Text(
                "Audio you've already heard is kept on this phone so replaying it is free.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { container.clearAudioCache() }
                        cacheNotice = if (freed > 0) {
                            "Cleared ${freed / 1024} KB of cached audio."
                        } else {
                            "There was nothing cached."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear cached audio") }
            cacheNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Spacer(Modifier.size(16.dp))
            OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                Text("About and licences")
            }

            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = { confirmSignOut = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign out") }
            Spacer(Modifier.size(24.dp))
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear reading history?") },
            text = {
                Text(
                    "Removes all $historyCount saved reads. The audio itself stays cached, so " +
                        "anything you read again soon is still free."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { container.history.clearAll() }
                        confirmClearHistory = false
                    }
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    "Your API key is deleted from this phone, your settings are reset, and your " +
                        "reading history is cleared. Your ElevenLabs account isn't touched — you " +
                        "can paste the same key back in."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.size(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
