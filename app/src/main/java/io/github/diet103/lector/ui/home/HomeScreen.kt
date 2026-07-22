package io.github.diet103.lector.ui.home

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.model.SpeakRequest
import io.github.diet103.lector.model.TtsError
import io.github.diet103.lector.model.UserAccount
import io.github.diet103.lector.playback.PlaybackService
import io.github.diet103.lector.tts.ApiResult

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

            NotificationNudge()

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
                    val uri = container.registry.register(container.speakRequest(text.trim()))
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
 * Shown only while notifications are denied.
 *
 * This is a quiet failure worth surfacing: without the permission the foreground service still runs
 * and audio still plays perfectly, so nothing looks broken — the media notification simply never
 * appears, and the only way to stop playback is to come back into the app. It is entirely possible
 * to use Lector for weeks without realising that is why. Onboarding offers the permission, but
 * skipping it there left no second chance.
 */
@Composable
private fun NotificationNudge() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun isGranted() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(isGranted()) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    // Android stops offering the dialog after enough refusals; from then on only Settings can fix
    // it. There is no way to tell that state apart from "never asked" up front, so we learn it from
    // a request that returns denied without the system having offered a rationale.
    var settingsOnly by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        settingsOnly = !allowed && activity?.shouldShowRequestPermissionRationale(
            Manifest.permission.POST_NOTIFICATIONS
        ) == false
    }

    // Re-check on resume so the card disappears after the permission is granted from system
    // Settings, where no result ever comes back to us.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = isGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted || dismissed) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Playback controls are hidden", style = MaterialTheme.typography.titleMedium)
        Text(
            "Notifications are off, so Lector can't show its play/pause notification. Reading " +
                "still works — you just have to come back here to stop it.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (settingsOnly) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                } else {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }) {
                Text(if (settingsOnly) "Open notification settings" else "Turn on notifications")
            }
            TextButton(onClick = { dismissed = true }) { Text("Not now") }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
