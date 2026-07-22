package io.github.diet103.lector.entry

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.LectorApplication
import io.github.diet103.lector.MainActivity
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.history.ReadSource
import io.github.diet103.lector.model.TtsError
import io.github.diet103.lector.ocr.TextBlockAssembler
import io.github.diet103.lector.playback.PlaybackErrorMapper
import io.github.diet103.lector.playback.PlaybackService
import io.github.diet103.lector.tts.ApiResult
import io.github.diet103.lector.web.SharedLink
import io.github.diet103.lector.ui.theme.LectorTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Translucent entry point for every way text reaches Lector (PLAN §6 P3–P4 + link reading): a
 * "Read aloud" selection, a `text/plain` share, a shared link whose page gets fetched, and a
 * shared screenshot that gets OCR'd on-device first.
 *
 * **Contract — never sets an activity result.** For an editable PROCESS_TEXT caller (Gmail
 * compose, Keep) a returned result *replaces the user's selection*: silent data corruption
 * (risk #4). This activity only ever reads. Do not add `setResult(...)` anywhere.
 *
 * A thin scrim stays up for the whole handoff — including the OCR or fetch pass — and finishes
 * itself the instant the service reports playback: the keep-alive that dodges the FGS-promotion
 * race (§4). No `android:screenOrientation` in the manifest: a translucent activity that fixes
 * orientation crashes on Android 8.
 */
class ReadAloudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as LectorApplication).container
        val image = SharedImageLoader.imageUri(intent)
        val start = initialStage(image, container)

        // Whatever slow work this particular share needs before there's anything to speak.
        val work: (suspend () -> Stage)? = when {
            start !is Stage.Busy -> null
            image != null -> {
                { readScreen(this, image, container) }
            }
            else -> start.link?.let { link -> { readLink(link, container) } }
        }

        setContent {
            // Without the app theme the scrim renders in Compose's default *light* scheme — a
            // white card over whatever dark app you were reading. Lector is dark-first.
            LectorTheme {
                ReadAloudScrim(
                    initial = start,
                    work = work,
                    image = image,
                    container = container,
                    onFinished = { finish() }
                )
            }
        }
    }

    /**
     * Text arrives already extracted; an image or a link can't, so those start [Stage.Busy]. The
     * key check comes first on those paths — no point spending an OCR pass or a page fetch on an
     * install that can't speak the result anyway.
     */
    private fun initialStage(image: Uri?, container: AppContainer): Stage {
        if (image != null) {
            return if (container.isSetUp) Stage.Busy("Reading your screen…") else notSetUp(container, null)
        }

        return when (val extraction = IntentTextExtractor.extract(intent, container.maxChars)) {
            is TextExtraction.Failed -> Stage.Failed(
                when (extraction.reason) {
                    ExtractionError.EMPTY -> "No text to read."
                    ExtractionError.UNSUPPORTED -> "Lector can't read this."
                }
            )

            is TextExtraction.Extracted -> {
                if (!container.isSetUp) return notSetUp(container, extraction.text)

                // A bare shared link is a request to read the *page*, not to spell out a URL.
                val link = SharedLink.detect(extraction.text)
                if (link != null) {
                    Stage.Busy("Fetching the page…", link = link)
                } else {
                    Stage.Speak(extraction.text, extraction.truncated, selectionOrShare())
                }
            }
        }
    }

    /** The selection toolbar and the share sheet are worth telling apart in the history list. */
    private fun selectionOrShare(): ReadSource =
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            ReadSource.SELECTION
        } else {
            ReadSource.SHARED_TEXT
        }

    /** Holds the selection across the setup detour so the user doesn't have to find it again. */
    private fun notSetUp(container: AppContainer, pending: String?): Stage {
        container.pendingText = pending
        return Stage.Failed(TtsError.NoApiKey.message, offerSetup = true)
    }
}

/** What the scrim is doing right now. */
private sealed interface Stage {

    /** Slow work is running — on-device OCR, or fetching a shared page. */
    data class Busy(val message: String, val link: String? = null) : Stage

    /** Text in hand — hand it to the playback service. */
    data class Speak(
        val text: String,
        val truncated: Boolean,
        val source: ReadSource,
        /** A fetched page's headline; null for every other path. */
        val title: String? = null,
        /** The page a link read came from. Never set for an image — the URI is not kept. */
        val sourceUrl: String? = null
    ) : Stage

    /** Terminal; the scrim becomes tap-to-dismiss. */
    data class Failed(val message: String, val offerSetup: Boolean = false) : Stage
}

/** Fetches a shared link and turns the page into something worth listening to. */
private suspend fun readLink(url: String, container: AppContainer): Stage {
    val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) { container.articleFetcher.fetch(url) }
        ?: return Stage.Failed(TtsError.LinkNotReadable.message)

    return when (result) {
        is ApiResult.Failed -> Stage.Failed(result.error.message)
        is ApiResult.Ok -> {
            val article = result.value
            // Lead with the headline: hearing the title first is how you know it fetched the
            // thing you meant.
            val body = listOfNotNull(article.title, article.text).joinToString("\n")
            // Shapes only, never content — enough to debug extraction without logging what you read.
            if (BuildConfig.DEBUG) {
                Log.d(OCR_TAG, "link: extracted=${article.text.length} chars titled=${article.title != null}")
            }
            val capped = SentenceCap.apply(body, container.maxChars)
            Stage.Speak(
                text = capped.text,
                truncated = capped.truncated,
                source = ReadSource.LINK,
                title = article.title,
                sourceUrl = url
            )
        }
    }
}

@Composable
private fun ReadAloudScrim(
    initial: Stage,
    work: (suspend () -> Stage)?,
    image: Uri?,
    container: AppContainer,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf(initial) }
    val current = stage

    if (work != null && current is Stage.Busy) {
        LaunchedEffect(work) { stage = work() }
    }

    // Whatever the user chooses in Android's delete dialog, the read has already started and the
    // scrim's job is done.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { onFinished() }

    if (current is Stage.Speak) {
        SpeakHandoff(
            speak = current,
            container = container,
            onPlaying = {
                val request = screenshotDeleteRequest(context, image, container)
                if (request == null) {
                    onFinished()
                } else {
                    deleteLauncher.launch(IntentSenderRequest.Builder(request).build())
                }
            },
            onError = { stage = Stage.Failed(it) }
        )
    }

    val busy = current !is Stage.Failed
    val message = when (current) {
        is Stage.Busy -> current.message
        is Stage.Speak -> "Starting…"
        is Stage.Failed -> "${current.message} Tap to dismiss."
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
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                if (current is Stage.Failed && current.offerSetup) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(context, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onFinished()
                    }) { Text("Set up Lector") }
                }
            }
        }
    }
}

/** Connects a controller, starts the text playing, and finishes the scrim on first playback. */
@Composable
private fun SpeakHandoff(
    speak: Stage.Speak,
    container: AppContainer,
    onPlaying: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(speak) {
        if (speak.truncated) {
            Toast.makeText(
                context,
                "Lector: text was long — reading the first part.",
                Toast.LENGTH_LONG
            ).show()
        }

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        var controller: MediaController? = null
        val handler = Handler(Looper.getMainLooper())
        val giveUp = Runnable { onError("Taking too long.") }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    handler.removeCallbacks(giveUp)
                    onPlaying()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handler.removeCallbacks(giveUp)
                // Recorded as well as shown: the scrim is about to be dismissed, and Home is
                // where the user will look for what went wrong.
                val mapped = PlaybackErrorMapper.map(error)
                container.lastError.record(mapped)
                onError(mapped.message)
            }
        }
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull()
            if (connected == null) {
                onError("Couldn't reach the player.")
                return@addListener
            }
            controller = connected
            connected.addListener(listener)
            val uri = container.beginRead(
                text = speak.text,
                source = speak.source,
                title = speak.title,
                sourceUrl = speak.sourceUrl
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

/**
 * Shared screenshot → speakable text, entirely on-device. Decoding is I/O so it moves off the
 * main thread; ML Kit does its own threading and the wrapper just suspends.
 */
private suspend fun readScreen(context: Context, uri: Uri, container: AppContainer): Stage {
    val loaded = withContext(Dispatchers.IO) {
        runCatching { SharedImageLoader.load(context, uri) }
    }
    val bitmap = loaded.getOrNull() ?: run {
        // Unconditional, unlike the shape logging below. On a release build this is the only trace
        // a bug report can carry, and a URI scheme plus an exception type say nothing about what
        // the user was reading.
        Log.w(OCR_TAG, "could not open ${uri.scheme} image", loaded.exceptionOrNull())
        return Stage.Failed("Couldn't open that image.")
    }

    val height = bitmap.height
    var failure: Throwable? = null
    val blocks = try {
        withTimeoutOrNull(OCR_TIMEOUT_MS) { container.screenTextRecognizer.recognize(bitmap) }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (thrown: Exception) {
        failure = thrown
        null
    }

    // Only once ML Kit has handed the bitmap back — recycling one it is still reading crashes it.
    if (blocks == null) {
        val shape = "${bitmap.width}x$height ${bitmap.config}"
        return if (failure == null) {
            Log.w(OCR_TAG, "OCR timed out after $OCR_TIMEOUT_MS ms on $shape")
            Stage.Failed("That image took too long to read.")
        } else {
            Log.w(OCR_TAG, "OCR failed on $shape", failure)
            Stage.Failed("Couldn't read that image.")
        }
    }
    bitmap.recycle()

    val text = TextBlockAssembler.assemble(blocks, height)
    // Shapes only, never content: enough to debug the pipeline without logging what you read.
    if (BuildConfig.DEBUG) {
        Log.d(OCR_TAG, "blocks=${blocks.size} assembled=${text.length} chars")
    }
    if (text.isBlank()) return Stage.Failed("No text found in that image.")

    val capped = SentenceCap.apply(text, container.maxChars)
    // Recognized text only. The bitmap is already recycled and the content:// URI is deliberately
    // not carried through — a history of what you read must never become a folder of screenshots.
    return Stage.Speak(capped.text, capped.truncated, ReadSource.SCREENSHOT)
}

/**
 * The "delete the screenshot once it's been read" request, or `null` when it doesn't apply.
 *
 * Android hands a shared image over read-only, so deletion has to go through the system, which
 * always shows its own confirmation — that dialog can't be suppressed without the
 * manage-all-files permission, which this app will never ask for. Only MediaStore images can be
 * deleted this way; a file from some other provider simply isn't ours to remove.
 */
private fun screenshotDeleteRequest(
    context: Context,
    image: Uri?,
    container: AppContainer
): IntentSender? {
    if (image == null || !container.settings.deleteScreenshotAfterReading.value) return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    if (image.authority != MediaStore.AUTHORITY) return null

    return runCatching {
        MediaStore.createDeleteRequest(context.contentResolver, listOf(image)).intentSender
    }.getOrNull()
}

private const val FETCH_TIMEOUT_MS = 20000L
private const val OCR_TAG = "LectorOcr"
private const val NO_KEY = "No API key set yet. Open Lector to finish setup."
private const val HANDOFF_TIMEOUT_MS = 8000L
private const val OCR_TIMEOUT_MS = 15000L
