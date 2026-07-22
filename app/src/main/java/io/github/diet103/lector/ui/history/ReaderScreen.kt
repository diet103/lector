package io.github.diet103.lector.ui.history

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.history.EstimatedReadingClock
import io.github.diet103.lector.history.HistoryEntry
import io.github.diet103.lector.history.TextSpans
import io.github.diet103.lector.playback.PlaybackService
import kotlinx.coroutines.delay

/**
 * One stored read, shown as text you can follow and tap into (v0.2).
 *
 * Two things are happening at once:
 *  - **Follow along.** The sentence being spoken is tinted and the current word emphasised. Timings
 *    are estimated from the audio duration ([EstimatedReadingClock]) rather than measured, so the
 *    sentence tint is doing real work: it absorbs the drift that would make a bare word highlight
 *    jitter.
 *  - **Tap to start there.** Only for a read whose audio is entirely cached. Seeking a read that is
 *    still streaming would re-request it from byte zero and bill the whole text again, so the tap
 *    is simply not offered — and the playback service withholds the seek command as well, rather
 *    than trusting this screen to behave.
 */
@Composable
fun ReaderScreen(
    container: AppContainer,
    entry: HistoryEntry,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var isCurrent by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var caretOffset by remember { mutableIntStateOf(0) }

    // Only ever true for a read that finished and whose bytes survived the cache's 50 MB LRU.
    val seekable = remember(entry.key) { container.isFullyCached(entry) }
    val clock = remember(entry.key, entry.durationMs) {
        EstimatedReadingClock(entry.text, entry.durationMs ?: 0L)
    }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                isCurrent = mediaItem?.mediaId == entry.key
            }
        }
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let {
                it.addListener(listener)
                isPlaying = it.isPlaying
                isCurrent = it.currentMediaItem?.mediaId == entry.key
                controller = it
            }
        }, MoreExecutors.directExecutor())

        onDispose {
            controller?.removeListener(listener)
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    // The player has no position callback, so it gets polled — but only while this read is the one
    // actually playing, so a backgrounded reader isn't waking up ten times a second for nothing.
    LaunchedEffect(controller, isPlaying, isCurrent) {
        val active = controller ?: return@LaunchedEffect
        while (isPlaying && isCurrent) {
            positionMs = active.currentPosition
            delay(TICK_MS)
        }
        if (!isPlaying) positionMs = active.currentPosition
    }

    // While this read is playing the caret follows the voice; otherwise it stays where it was last
    // put, so the highlight doesn't snap back to the top the moment you pause.
    LaunchedEffect(positionMs, isCurrent) {
        if (isCurrent) caretOffset = clock.charOffsetFor(positionMs)
    }

    val sentence = remember(caretOffset, entry.key) { TextSpans.sentenceAt(entry.text, caretOffset) }
    val word = remember(caretOffset, entry.key) { TextSpans.wordAt(entry.text, caretOffset) }

    val tint = MaterialTheme.colorScheme.primaryContainer
    val highlighted = remember(sentence, word, isCurrent) {
        AnnotatedString.Builder(entry.text).apply {
            if (isCurrent && entry.durationMs != null) {
                if (!sentence.isEmpty()) {
                    addStyle(SpanStyle(background = tint), sentence.first, sentence.last + 1)
                }
                if (!word.isEmpty()) {
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        word.first,
                        word.last + 1
                    )
                }
            }
        }.toAnnotatedString()
    }

    fun playFrom(charOffset: Int) {
        val active = controller ?: return
        if (!isCurrent) {
            active.setMediaItem(
                MediaItem.Builder().setMediaId(container.replay(entry).lastPathSegment!!).build()
            )
            active.prepare()
        }
        if (seekable && entry.durationMs != null) {
            active.seekTo(clock.positionFor(charOffset))
        }
        active.play()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                entry.title ?: entry.snippet(60),
                style = MaterialTheme.typography.titleLarge
            )
            entry.sourceUrl?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = TextDecoration.Underline
                )
            }

            Text(
                text = if (seekable) {
                    "Tap any word to start reading from there."
                } else {
                    "Replaying this will read it again from the start."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val active = controller
                        if (active != null && isCurrent && isPlaying) active.pause() else playFrom(caretOffset)
                    },
                    enabled = controller != null
                ) {
                    Text(if (isCurrent && isPlaying) "Pause" else "Play")
                }
                OutlinedButton(
                    onClick = { caretOffset = 0; playFrom(0) },
                    enabled = controller != null
                ) { Text("From the top") }
            }

            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodyLarge,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(seekable, entry.key, controller) {
                        detectTapGestures { tap ->
                            val result = layout ?: return@detectTapGestures
                            val tapped = result.getOffsetForPosition(tap)
                            // Snap to the start of the word: mid-word would cut the first syllable.
                            val start = TextSpans.wordAt(entry.text, tapped).first
                            caretOffset = start
                            playFrom(start)
                        }
                    }
            )

            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/** Ten ticks a second — smooth enough to follow, cheap enough not to matter. */
private const val TICK_MS = 100L
