package io.github.diet103.lector.ui.voices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.model.VoiceSummary
import io.github.diet103.lector.tts.ApiResult

/**
 * Voice picker (PLAN §6 P6).
 *
 * Voices come from the account's own list — premade library IDs are never hardcoded, because a
 * free-tier account genuinely cannot use them and a hardcoded default would fail forever.
 *
 * Cloned voices are shown but not selectable unless the plan allows them
 * ([UserAccount.canUseInstantVoiceCloning][io.github.diet103.lector.model.UserAccount]): they
 * list fine and then refuse at synthesis, which is the single most confusing failure this app
 * can produce.
 *
 * Previews use ElevenLabs' public `preview_url` on a throwaway [ExoPlayer] — no characters are
 * billed, and the real playback session is left completely alone.
 */
@Composable
fun VoicePickerScreen(
    container: AppContainer,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var voices by remember { mutableStateOf<List<VoiceSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cloningAllowed by remember { mutableStateOf(true) }
    var previewing by remember { mutableStateOf<String?>(null) }

    val selectedVoiceId by container.settings.voiceId.collectAsState()

    val previewPlayer = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { previewPlayer.release() }
    }

    LaunchedEffect(Unit) {
        val key = container.apiKeyProvider()
        when (val account = container.elevenLabsApi.getUser(key)) {
            is ApiResult.Ok -> cloningAllowed = account.value.canUseInstantVoiceCloning
            is ApiResult.Failed -> Unit // Not fatal: fall back to showing everything as usable.
        }
        when (val result = container.elevenLabsApi.getVoices(key)) {
            is ApiResult.Ok -> voices = result.value
            is ApiResult.Failed -> error = result.error.message
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Voice", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDone) { Text("Done") }
            }

            when {
                error != null -> Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                voices == null -> Row(
                    modifier = Modifier.padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading your voices…", style = MaterialTheme.typography.bodyMedium)
                }

                else -> {
                    if (!cloningAllowed && voices!!.any { it.isCloned }) {
                        Text(
                            "Cloned voices need a paid ElevenLabs plan, so they're greyed out here.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(voices!!, key = { it.voiceId }) { voice ->
                            val usable = !voice.isCloned || cloningAllowed
                            VoiceRow(
                                voice = voice,
                                usable = usable,
                                selected = voice.voiceId == selectedVoiceId,
                                previewing = previewing == voice.voiceId,
                                onSelect = {
                                    if (usable) container.settings.setVoiceId(voice.voiceId)
                                },
                                onPreview = {
                                    val url = voice.previewUrl ?: return@VoiceRow
                                    if (previewing == voice.voiceId) {
                                        previewPlayer.stop()
                                        previewing = null
                                    } else {
                                        previewPlayer.setMediaItem(MediaItem.fromUri(url))
                                        previewPlayer.prepare()
                                        previewPlayer.play()
                                        previewing = voice.voiceId
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceSummary,
    usable: Boolean,
    selected: Boolean,
    previewing: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit
) {
    val contentColor =
        if (usable) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
                MaterialTheme.shapes.medium
            )
            .clickable(enabled = usable, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                voice.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = contentColor
            )
            Text(
                buildString {
                    append(voice.category)
                    if (selected) append(" · in use")
                    if (!usable) append(" · needs a paid plan")
                },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
        }
        if (voice.previewUrl != null) {
            TextButton(onClick = onPreview) { Text(if (previewing) "Stop" else "Preview") }
        }
    }
}
