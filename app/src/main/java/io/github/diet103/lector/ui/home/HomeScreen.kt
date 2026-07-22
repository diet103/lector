package io.github.diet103.lector.ui.home

import android.content.ComponentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.history.HistoryEntry
import io.github.diet103.lector.history.ReadSource
import io.github.diet103.lector.model.UserAccount
import io.github.diet103.lector.playback.PlaybackService
import io.github.diet103.lector.tts.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home (PLAN §6 P5): account status, a try-it box, the mini player, and whatever went wrong last.
 *
 * The mini player is a [MediaController] client exactly like the notification is — the app never
 * owns a player, so closing this screen can't interrupt playback.
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onChangeKey: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenRead: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf("idle") }
    var account by remember { mutableStateOf<UserAccount?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }
    // A selection made before setup was finished: pick it up once and clear it, so the trip
    // through onboarding doesn't lose what they were trying to read (PLAN §6 P5).
    var text by rememberSaveable {
        mutableStateOf(container.pendingText?.also { container.pendingText = null } ?: SAMPLE_TEXT)
    }

    val lastError by container.lastError.lastError.collectAsState()
    val voiceId by container.settings.voiceId.collectAsState()
    val historyEnabled by container.settings.historyEnabled.collectAsState()
    val noticeSeen by container.settings.historyNoticeSeen.collectAsState()
    val historyRevision by container.history.revision.collectAsState()

    var lastRead by remember { mutableStateOf<HistoryEntry?>(null) }
    LaunchedEffect(historyRevision, historyEnabled) {
        lastRead = withContext(Dispatchers.IO) { container.history.mostRecent() }
    }

    LaunchedEffect(Unit) {
        when (val result = container.elevenLabsApi.getUser(container.apiKeyProvider())) {
            is ApiResult.Ok -> account = result.value
            is ApiResult.Failed -> accountError = result.error.message
        }
    }

    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) playbackState = "playing"
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = when (state) {
                    Player.STATE_BUFFERING -> "buffering…"
                    Player.STATE_ENDED -> "finished"
                    Player.STATE_READY -> if (isPlaying) "playing" else "paused"
                    else -> "idle"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackState = "stopped"
            }
        }
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let {
                it.addListener(listener)
                controller = it
            }
        }, MoreExecutors.directExecutor())

        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    Surface(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Lector", style = MaterialTheme.typography.headlineMedium)

            AccountStatus(account = account, error = accountError)

            lastError?.let { error ->
                Spacer(Modifier.size(4.dp))
                Text(
                    error.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (error.isKeyProblem) {
                        TextButton(onClick = onChangeKey) { Text("Change key") }
                    }
                    TextButton(onClick = { container.lastError.clear() }) { Text("Dismiss") }
                }
            }

            if (!noticeSeen) {
                HistoryNotice(
                    enabled = historyEnabled,
                    onSetEnabled = { container.settings.setHistoryEnabled(it) },
                    onDismiss = { container.settings.markHistoryNoticeSeen() }
                )
            }

            lastRead?.let { entry ->
                LastRead(
                    entry = entry,
                    free = container.isFullyCached(entry),
                    onOpen = { onOpenRead(entry) },
                    onOpenHistory = onOpenHistory
                )
            }

            Spacer(Modifier.size(8.dp))
            Text("How to use it", style = MaterialTheme.typography.titleMedium)
            Text(
                "Select text in any app, then choose \"Read aloud (Lector)\". Or share text — or " +
                    "a screenshot — to Lector. Screenshots are read on your phone; the image " +
                    "never leaves it.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.size(8.dp))
            Text("Try it", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Text") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )

            MiniPlayer(
                controller = controller,
                isPlaying = isPlaying,
                state = playbackState,
                canSpeak = controller != null && text.isNotBlank() && !voiceId.isNullOrBlank(),
                onSpeak = {
                    val active = controller ?: return@MiniPlayer
                    container.lastError.clear()
                    val uri = container.beginRead(text.trim(), ReadSource.TRY_IT)
                    active.setMediaItem(MediaItem.Builder().setMediaId(uri.lastPathSegment!!).build())
                    active.prepare()
                    active.play()
                }
            )

            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

/**
 * Shown once, to everyone — including people upgrading from a version that stored nothing.
 *
 * Lector shipped v0.1 promising that selected text is never persisted, and this reverses that. It
 * gets said out loud rather than buried in a settings screen and a changelog nobody reads.
 */
@Composable
private fun HistoryNotice(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Lector now keeps a history", style = MaterialTheme.typography.titleMedium)
            Text(
                "What you have Lector read is saved on this phone so you can find it and hear it " +
                    "again — replays are free once the audio is cached. Screenshots are never " +
                    "saved, only the text read out of them. Nothing is backed up or leaves the " +
                    "device, and you can clear it any time in Settings.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = enabled, onCheckedChange = onSetEnabled)
                Text(
                    if (enabled) "Keeping history" else "Not keeping history",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Got it") }
            }
        }
    }
}

/** The cheap half of history: the thing you most likely want is the thing you just read. */
@Composable
private fun LastRead(
    entry: HistoryEntry,
    free: Boolean,
    onOpen: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Spacer(Modifier.size(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Last read", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onOpenHistory) { Text("History") }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                entry.title ?: entry.snippet(100),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (free) "Tap to read it again — free" else "Tap to open",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AccountStatus(account: UserAccount?, error: String?) {
    when {
        account != null -> {
            Text("Plan: ${account.tier}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${account.charactersRemaining} of ${account.characterLimit} characters left this period.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        error != null -> Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )

        else -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Checking your account…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MiniPlayer(
    controller: MediaController?,
    isPlaying: Boolean,
    state: String,
    canSpeak: Boolean,
    onSpeak: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onSpeak, enabled = canSpeak) { Text("Speak") }
        OutlinedButton(
            onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
            enabled = controller != null
        ) { Text(if (isPlaying) "Pause" else "Resume") }
        OutlinedButton(
            onClick = { controller?.stop() },
            enabled = controller != null
        ) { Text("Stop") }
    }
    Text(
        state,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private const val SAMPLE_TEXT =
    "Hello from Lector. This audio is streaming straight from ElevenLabs."
