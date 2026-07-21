package io.github.diet103.lector.ui.dev

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.data.SpeakRequestRegistry
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.playback.TtsCache
import io.github.diet103.lector.playback.TtsPlayerFactory
import okhttp3.OkHttpClient

/**
 * P1 spike surface — replaced by the real Home screen in P2+. Exercises the full streaming
 * chain and displays the two numbers that matter: first-audio latency, and the upstream POST
 * count (which must stay at 1 per utterance across pause/resume/replay).
 */
@Composable
fun DevSpeakScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var apiKey by remember { mutableStateOf(BuildConfig.DEV_ELEVEN_KEY) }
    var voiceId by remember { mutableStateOf(BuildConfig.DEV_VOICE_ID) }
    var text by remember {
        mutableStateOf("Hello from Lector. This audio is streaming straight from ElevenLabs through ExoPlayer.")
    }
    var status by remember { mutableStateOf("idle") }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var upstreamPosts by remember { mutableIntStateOf(0) }
    var speakStartedAt by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    val currentApiKey by rememberUpdatedState(apiKey)
    val registry = remember { SpeakRequestRegistry() }
    val player = remember {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                upstreamPosts += 1
                chain.proceed(chain.request())
            }
            .build()
        TtsPlayerFactory.create(context, registry, TtsCache.get(context), client) { currentApiKey }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing && latencyMs == null && speakStartedAt > 0) {
                    latencyMs = SystemClock.elapsedRealtime() - speakStartedAt
                    status = "playing"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> status = "buffering…"
                    Player.STATE_ENDED -> status = "finished"
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                status = "error: ${error.cause?.message ?: error.message}"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("P1 streaming spike", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("xi-api-key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = voiceId,
            onValueChange = { voiceId = it },
            label = { Text("voice id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("text") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    latencyMs = null
                    speakStartedAt = SystemClock.elapsedRealtime()
                    status = "requesting…"
                    val uri = registry.register(
                        SpeakRequest(text = text.trim(), voiceId = voiceId.trim())
                    )
                    player.setMediaItem(MediaItem.fromUri(uri))
                    player.prepare()
                    player.play()
                },
                enabled = apiKey.isNotBlank() && voiceId.isNotBlank() && text.isNotBlank()
            ) { Text("Speak") }
            Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Text(if (isPlaying) "Pause" else "Resume")
            }
            OutlinedButton(onClick = {
                player.stop()
                status = "stopped"
            }) { Text("Stop") }
        }
        Text("status: $status")
        Text("first-audio latency: ${latencyMs?.let { "$it ms" } ?: "—"}")
        Text("upstream POSTs this session: $upstreamPosts")
        Text(
            "Replay the same text: it must play from cache and the POST count must not move.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
