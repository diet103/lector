package io.github.diet103.lector.entry

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.diet103.lector.LectorApplication
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.playback.PlaybackService

/**
 * Translucent entry point for "Read aloud" selections and `text/plain` shares (PLAN §6 P3).
 *
 * **Contract — never sets an activity result.** For an editable PROCESS_TEXT caller (Gmail
 * compose, Keep) a returned result *replaces the user's selection*: silent data corruption
 * (risk #4). This activity only ever reads. Do not add `setResult(...)` anywhere.
 *
 * A thin scrim stays up during the handoff and finishes itself the instant the service reports
 * playback — the keep-alive that dodges the FGS-promotion race (§4). No `android:screenOrientation`
 * in the manifest: a translucent activity that fixes orientation crashes on Android 8.
 */
class ReadAloudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LectorApplication).container
        val extraction = IntentTextExtractor.extract(intent)

        if (extraction is TextExtraction.Extracted && extraction.truncated) {
            Toast.makeText(this, "Lector: text was long — reading the first part.", Toast.LENGTH_LONG).show()
        }

        setContent {
            ReadAloudScrim(
                extraction = extraction,
                hasKey = container.apiKeyProvider().isNotBlank(),
                container = container,
                onFinished = { finish() }
            )
        }
    }
}

@Composable
private fun ReadAloudScrim(
    extraction: TextExtraction,
    hasKey: Boolean,
    container: AppContainer,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val canPlay = extraction is TextExtraction.Extracted && hasKey

    var status by remember {
        mutableStateOf(
            when {
                extraction is TextExtraction.Failed && extraction.reason == ExtractionError.EMPTY ->
                    "No text to read."
                extraction is TextExtraction.Failed -> "Lector can't read this."
                !hasKey -> "No API key set yet. Open Lector to finish setup."
                else -> "Starting…"
            }
        )
    }
    var busy by remember { mutableStateOf(canPlay) }

    if (canPlay) {
        val request = (extraction as TextExtraction.Extracted)
        DisposableEffect(Unit) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            var controller: MediaController? = null
            val handler = Handler(Looper.getMainLooper())
            val giveUp = Runnable {
                status = "Taking too long. Tap to dismiss."
                busy = false
            }
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        handler.removeCallbacks(giveUp)
                        onFinished()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    handler.removeCallbacks(giveUp)
                    status = "Couldn't play that: ${error.cause?.message ?: error.message}. Tap to dismiss."
                    busy = false
                }
            }
            future.addListener({
                val connected = runCatching { future.get() }.getOrNull()
                if (connected == null) {
                    status = "Couldn't reach the player. Tap to dismiss."
                    busy = false
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                val uri = container.registry.register(
                    SpeakRequest(text = request.text, voiceId = container.defaultVoiceId)
                )
                connected.setMediaItem(MediaItem.Builder().setMediaId(uri.lastPathSegment!!).build())
                connected.prepare()
                connected.play()
            }, MoreExecutors.directExecutor())
            handler.postDelayed(giveUp, HANDOFF_TIMEOUT_MS)

            onDispose {
                handler.removeCallbacks(giveUp)
                controller?.removeListener(listener)
                MediaController.releaseFuture(future)
            }
        }
    }

    val dismissModifier = if (busy) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onFinished() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .then(dismissModifier),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shape = MaterialTheme.shapes.large
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val HANDOFF_TIMEOUT_MS = 8000L
