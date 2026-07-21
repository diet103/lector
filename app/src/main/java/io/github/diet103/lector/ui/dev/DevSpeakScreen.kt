package io.github.diet103.lector.ui.dev

import android.content.ComponentName
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.LectorApplication
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.playback.PlaybackService

/**
 * P1/P2 spike surface — replaced by the real Home screen in P5. It no longer owns a player;
 * it connects to [PlaybackService] through a [MediaController], the same way the notification
 * and Quick Settings controls do. That's what makes P2 demonstrable: press Speak, then Home,
 * and audio keeps playing from the service with controls in the shade. The POST counter (debug
 * only) reads the service's live request tally so the billing invariant stays visible on-device.
 */
@Composable
fun DevSpeakScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as LectorApplication).container }

    var apiKey by remember { mutableStateOf(container.apiKey) }
    var voiceId by remember { mutableStateOf(BuildConfig.DEV_VOICE_ID) }
    var text by remember {
        mutableStateOf("Hello from Lector. This audio is streaming straight from ElevenLabs through ExoPlayer.")
    }
    var status by remember { mutableStateOf("connecting…") }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var upstreamPosts by remember { mutableIntStateOf(0) }
    var speakStartedAt by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                upstreamPosts = container.upstreamPostCount.get()
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
                upstreamPosts = container.upstreamPostCount.get()
            }

            override fun onPlayerError(error: PlaybackException) {
                status = "error: ${error.cause?.message ?: error.message}"
            }
        }
        future.addListener({
            val connected = future.get()
            connected.addListener(listener)
            controller = connected
            status = "idle"
        }, MoreExecutors.directExecutor())

        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("P2 playback service", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                container.apiKey = it
            },
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
                    val c = controller ?: return@Button
                    latencyMs = null
                    speakStartedAt = SystemClock.elapsedRealtime()
                    status = "requesting…"
                    val uri = container.registry.register(
                        SpeakRequest(text = text.trim(), voiceId = voiceId.trim())
                    )
                    c.setMediaItem(MediaItem.Builder().setMediaId(uri.lastPathSegment!!).build())
                    c.prepare()
                    c.play()
                },
                enabled = controller != null && apiKey.isNotBlank() && voiceId.isNotBlank() && text.isNotBlank()
            ) { Text("Speak") }
            Button(onClick = {
                val c = controller ?: return@Button
                if (c.isPlaying) c.pause() else c.play()
            }) {
                Text(if (isPlaying) "Pause" else "Resume")
            }
            OutlinedButton(onClick = {
                controller?.stop()
                status = "stopped"
            }) { Text("Stop") }
        }
        Text("status: $status")
        Text("first-audio latency: ${latencyMs?.let { "$it ms" } ?: "—"}")
        Text("upstream POSTs this session: $upstreamPosts")
        Text(
            "Press Speak, then Home — audio keeps playing with controls in the notification shade. " +
                "Replay the same text and the POST count must not move.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
