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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One stored read, shown as text you can follow and tap into (v0.2).
 *
 * Two things are happening at once:
 *  - **Follow along.** The sentence being spoken is tinted and the current word marked. Timings are
 *    estimated from the audio duration ([EstimatedReadingClock]) rather than measured, so the
 *    sentence tint is doing real work: it absorbs the drift that would make a bare word highlight
 *    jitter. Highlighting only ever changes *colour*, never weight or spacing — anything that
 *    alters glyph advances re-flows the line ten times a second, and the text visibly crawls.
 *  - **Tap to start there.** Free only once a read's audio is entirely on disk; seeking one that is
 *    still arriving would re-request it from byte zero and bill the whole text again. So a tap made
 *    too early is *held* and applied the moment the audio is complete, rather than being dropped —
 *    which is what made it look like tapping always restarted from the top. The playback service
 *    withholds the seek command in the same conditions, rather than trusting this screen to behave.
 */
@Composable
fun ReaderScreen(
    container: AppContainer,
    entry: HistoryEntry,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentVoiceId by container.settings.voiceId.collectAsState()

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var isCurrent by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var caretOffset by remember { mutableIntStateOf(0) }

    // Whether every byte is on disk, and therefore whether jumping around is free.
    //
    // Re-checked as playback proceeds rather than sampled once when the screen opens: a read that
    // is still downloading becomes seekable partway through, and a value captured at open would
    // stay false forever — which looked exactly like "tapping only ever goes back to the start".
    var seekable by remember(entry.key) { mutableStateOf(container.isFullyCached(entry)) }

    // The player is the authority on duration and knows it as soon as it prepares. The stored
    // value is only a starting point, so opening a read for the first time still highlights.
    var durationMs by remember(entry.key) { mutableStateOf(entry.durationMs ?: 0L) }
    val clock = remember(entry.key, durationMs) { EstimatedReadingClock(entry.text, durationMs) }

    // A tap made before the player knows how long the audio is. Applied the moment it does.
    var pendingSeekOffset by remember(entry.key) { mutableStateOf<Int?>(null) }

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
    // The same loop learns the duration, which is why it also runs once when not playing.
    LaunchedEffect(controller, isPlaying, isCurrent) {
        val active = controller ?: return@LaunchedEffect
        do {
            if (isCurrent) {
                positionMs = active.currentPosition
                if (!seekable) seekable = container.isFullyCached(entry)
                val reported = active.duration
                if (reported != C.TIME_UNSET && reported > 0 && reported != durationMs) {
                    durationMs = reported
                    // Persist it so the list and the next open don't have to rediscover it.
                    withContext(Dispatchers.IO) {
                        container.history.markDuration(entry.key, reported)
                    }
                }
                // A tap that arrived before the audio was ready to be jumped around in. Held, not
                // dropped, so it lands as soon as it legitimately can.
                pendingSeekOffset?.let { offset ->
                    if (durationMs > 0 && seekable) {
                        active.seekTo(EstimatedReadingClock(entry.text, durationMs).positionFor(offset))
                        pendingSeekOffset = null
                    }
                }
            }
            delay(TICK_MS)
        } while (isPlaying && isCurrent)
    }

    // While this read is playing the caret follows the voice; otherwise it stays where it was last
    // put, so the highlight doesn't snap back to the top the moment you pause.
    LaunchedEffect(positionMs, isCurrent) {
        if (isCurrent) caretOffset = clock.charOffsetFor(positionMs)
    }

    val sentence = remember(caretOffset, entry.key) { TextSpans.sentenceAt(entry.text, caretOffset) }
    val word = remember(caretOffset, entry.key) { TextSpans.wordAt(entry.text, caretOffset) }

    // Every background is paired with its own `on` colour. Setting a background alone left the
    // text at `onSurface`, and with dynamic colour the container tone comes from the wallpaper —
    // so light-on-light was not a bad guess but a guarantee waiting to happen.
    val sentenceBackground = MaterialTheme.colorScheme.primaryContainer
    val sentenceForeground = MaterialTheme.colorScheme.onPrimaryContainer
    val wordBackground = MaterialTheme.colorScheme.primary
    val wordForeground = MaterialTheme.colorScheme.onPrimary

    val highlighted = remember(sentence, word, isCurrent, durationMs) {
        AnnotatedString.Builder(entry.text).apply {
            if (isCurrent && durationMs > 0) {
                if (!sentence.isEmpty()) {
                    addStyle(
                        SpanStyle(background = sentenceBackground, color = sentenceForeground),
                        sentence.first,
                        sentence.last + 1
                    )
                }
                if (!word.isEmpty()) {
                    // Colour only — never weight, size or letter spacing. Those change glyph
                    // advances, so the line re-flows ten times a second as the word moves: text
                    // visibly wriggles and gaps blink open between words.
                    addStyle(
                        SpanStyle(background = wordBackground, color = wordForeground),
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
            isCurrent = true
        }
        // Asked live rather than trusting the flag: a read can finish downloading between the
        // screen opening and the tap.
        if (!seekable) seekable = container.isFullyCached(entry)

        if (seekable && durationMs > 0) {
            active.seekTo(clock.positionFor(charOffset))
        } else {
            // Either the duration hasn't arrived yet or the audio is still coming down. Hold the
            // tap and let the polling loop land it, instead of silently starting from the top.
            pendingSeekOffset = charOffset
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
                    "Still downloading — tap a word and it will jump there once it's ready."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Audio is synthesised once, in whatever voice was set at the time, and the cache is
            // keyed on that. Replaying in a newly-chosen voice would be a fresh synthesis and a
            // fresh charge — so a past read keeps its original voice, and says so rather than
            // looking like the voice setting is broken.
            if (currentVoiceId != null && entry.voiceId != currentVoiceId) {
                Text(
                    "Recorded in the voice you had set at the time. Playing it again keeps that " +
                        "voice and stays free — reading it in your current voice would cost " +
                        "characters, so Lector doesn't do it behind your back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
