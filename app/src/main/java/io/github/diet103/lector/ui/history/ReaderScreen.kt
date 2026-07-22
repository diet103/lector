package io.github.diet103.lector.ui.history

import android.content.ComponentName
import android.os.Bundle
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.diet103.lector.playback.ReadFromCommand
import io.github.diet103.lector.playback.SeekDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * One stored read, shown as text you can follow and tap into (v0.2).
 *
 * Two things are happening at once:
 *  - **Follow along.** The sentence being spoken is tinted and the current word marked. Timings are
 *    estimated from the audio duration ([EstimatedReadingClock]) rather than measured, so the
 *    sentence tint is doing real work: it absorbs the drift that would make a bare word highlight
 *    jitter. Highlighting only ever changes *colour*, never weight or spacing — anything that
 *    alters glyph advances re-flows the line ten times a second, and the text visibly crawls.
 *  - **Tap to start there.** This screen holds no opinion about whether that is allowed. It asks
 *    ([ReadFromCommand]) and the playback service decides, because it is the only place that can
 *    see both the cache and the loaded audio at once. An earlier version decided here *as well*,
 *    and the two answers drifted apart: the service refused a jump for the rest of a read that had
 *    still been downloading when it started, while this screen believed it was allowed the moment
 *    the download finished. Media3 drops an ungranted command in silence, so tapping a word did
 *    nothing at all. A tap that is refused (the audio really is still arriving) is held and retried,
 *    not dropped.
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
    var positionMs by remember { mutableLongStateOf(0L) }
    var isCurrent by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var caretOffset by remember { mutableIntStateOf(0) }

    // Whether every byte is on disk. Advisory only — it decides which line of copy to show, never
    // whether a jump happens. That is the service's call, made at the instant of the tap.
    var downloaded by remember(entry.key) { mutableStateOf(container.isFullyCached(entry)) }

    // The player is the authority on duration and knows it as soon as it prepares. The stored
    // value is only a starting point, so opening a read for the first time still highlights.
    var durationMs by remember(entry.key) { mutableLongStateOf(entry.durationMs ?: 0L) }

    // Derived rather than a plain val: the tap handler lives inside a `pointerInput` block that is
    // not restarted on every recomposition, so a captured clock would still be the one built when
    // the duration was zero. Reading through a State always gets the current one.
    val clock by remember(entry.key) { derivedStateOf { EstimatedReadingClock(entry.text, durationMs) } }

    // A tap the service refused because the audio is still arriving. Retried each tick rather than
    // dropped, so it lands as soon as it legitimately can.
    var pendingSeekOffset by remember(entry.key) { mutableStateOf<Int?>(null) }

    // Where a jump was aimed. Until the player's reported position reaches it, the caret stays put
    // — otherwise the position poller paints the pre-jump position back over it and the highlight
    // visibly snaps backwards before settling. The tick budget is a backstop so a jump that never
    // arrives leaves a stale highlight rather than a frozen one.
    var pinned by remember(entry.key) { mutableStateOf(false) }
    var pinnedTargetMs by remember(entry.key) { mutableLongStateOf(0L) }
    var pinTicks by remember(entry.key) { mutableIntStateOf(0) }

    // Replies come back asynchronously, so a slow answer to an earlier tap could otherwise land
    // after a later one and drag playback back to a word the reader has already moved on from.
    var requestSeq by remember(entry.key) { mutableIntStateOf(0) }

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

    /**
     * Asks the service to start reading from [charOffset]. It decides; we only report.
     * See [ReadFromCommand] for why this is not `seekTo`.
     */
    fun requestReadFrom(active: MediaController, charOffset: Int) {
        if (durationMs <= 0) {
            // No duration yet means no target to aim at. Hold the tap for the polling loop.
            pendingSeekOffset = charOffset
            return
        }
        val args = Bundle().apply {
            putLong(ReadFromCommand.ARG_POSITION_MS, clock.positionFor(charOffset))
        }
        val seq = ++requestSeq
        val future = active.sendCustomCommand(ReadFromCommand.COMMAND, args)
        future.addListener({
            // A later tap has already been sent; this answer is about a word we have left behind.
            if (seq != requestSeq) return@addListener
            val extras = runCatching { future.get() }.getOrNull()?.extras
            when (extras?.getString(ReadFromCommand.RESULT_DECISION)) {
                null, SeekDecision.REFUSE.name -> {
                    // Still arriving. Keep the tap and stop holding the caret, so the highlight
                    // goes back to following the voice instead of freezing where we aimed.
                    pendingSeekOffset = charOffset
                    pinned = false
                }
                else -> {
                    pendingSeekOffset = null
                    pinnedTargetMs = extras.getLong(ReadFromCommand.RESULT_POSITION_MS)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    // The player has no position callback, so it gets polled — but only while this read is the one
    // actually playing, so a backgrounded reader isn't waking up ten times a second for nothing.
    // The same loop learns the duration, which is why it also runs once when not playing.
    LaunchedEffect(controller, isPlaying, isCurrent) {
        val active = controller ?: return@LaunchedEffect
        do {
            if (isCurrent) {
                positionMs = active.currentPosition
                if (!downloaded) downloaded = container.isFullyCached(entry)
                val reported = active.duration
                if (reported != C.TIME_UNSET && reported > 0 && reported != durationMs) {
                    durationMs = reported
                    // Persist it so the list and the next open don't have to rediscover it.
                    withContext(Dispatchers.IO) {
                        container.history.markDuration(entry.key, reported)
                    }
                }
                if (pinned) {
                    pinTicks++
                    // Distance, not "past it". A jump *backwards* is already past its own target,
                    // so a one-sided test would unpin on the very first tick and let the old
                    // position paint itself back over the caret — the bounce this exists to stop.
                    val arrived = abs(positionMs - pinnedTargetMs) <= PIN_TOLERANCE_MS
                    if (arrived || pinTicks > PIN_MAX_TICKS) {
                        pinned = false
                        pinTicks = 0
                    }
                }
                // A tap the service refused, or one made before it knew how long the audio was.
                // Ask again once the reasons it would have refused are gone. That test is a
                // throttle on asking, not a second opinion on the answer: if it is wrong and we
                // stay quiet, the next tick re-checks and asks then; if it is wrong and we ask
                // anyway, the service still decides.
                pendingSeekOffset?.let { offset ->
                    if (durationMs > 0 && downloaded) requestReadFrom(active, offset)
                }
            }
            delay(TICK_MS)
        } while (isPlaying && isCurrent)
    }

    // While this read is playing the caret follows the voice; otherwise it stays where it was last
    // put, so the highlight doesn't snap back to the top the moment you pause.
    LaunchedEffect(positionMs, isCurrent, pinned) {
        if (isCurrent && !pinned) caretOffset = clock.charOffsetFor(positionMs)
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
            active.setMediaItem(MediaItem.Builder().setMediaId(container.replay(entry)).build())
            active.prepare()
            isCurrent = true
        }
        // Move the caret straight away and hold it there — the tap should feel answered even
        // though the real jump is a round trip to the service. With no duration yet there is
        // nothing to aim at, so there is nothing to hold either.
        caretOffset = charOffset
        pinned = durationMs > 0
        pinTicks = 0
        pinnedTargetMs = if (durationMs > 0) clock.positionFor(charOffset) else 0L

        requestReadFrom(active, charOffset)
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
                text = if (downloaded) {
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
                    onClick = { playFrom(0) },
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
                    // Deliberately not keyed on the duration or the cache: restarting this block
                    // cancels the gesture detector, and everything it reads is Compose state, so
                    // the handler already sees current values without being rebuilt.
                    .pointerInput(entry.key, controller) {
                        detectTapGestures { tap ->
                            val result = layout ?: return@detectTapGestures
                            val tapped = result.getOffsetForPosition(tap)
                            // Snap to the start of the word: mid-word would cut the first syllable.
                            playFrom(TextSpans.wordAt(entry.text, tapped).first)
                        }
                    }
            )

            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/** Ten ticks a second — smooth enough to follow, cheap enough not to matter. */
private const val TICK_MS = 100L

/** Two seconds. If a jump hasn't landed by then it isn't going to, so let the caret move again. */
private const val PIN_MAX_TICKS = 20

/** How close the playhead has to get to the jump's target before the caret is let go. */
private const val PIN_TOLERANCE_MS = 300L
