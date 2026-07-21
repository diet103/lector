package io.github.diet103.lector.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.diet103.lector.BuildConfig
import io.github.diet103.lector.app.AppContainer
import io.github.diet103.lector.model.UserAccount
import kotlinx.coroutines.launch

/**
 * First-run setup (PLAN §6 P5): welcome → key with its required scopes → validated account.
 *
 * A plain step enum rather than a nav library — three screens don't justify the dependency
 * (PLAN §11, minimal dependencies).
 */
private enum class Step { Welcome, EnterKey, Ready }

@Composable
fun OnboardingScreen(
    container: AppContainer,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    var account by remember { mutableStateOf<UserAccount?>(null) }
    var voiceName by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {
                Step.Welcome -> WelcomeStep(onContinue = { step = Step.EnterKey })

                Step.EnterKey -> EnterKeyStep(
                    container = container,
                    onValidated = { validatedAccount, chosenVoice ->
                        account = validatedAccount
                        voiceName = chosenVoice
                        step = Step.Ready
                    }
                )

                Step.Ready -> ReadyStep(
                    account = account,
                    voiceName = voiceName,
                    onDone = onFinished
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Text("Lector", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Hear anything on your screen in a real voice.",
        style = MaterialTheme.typography.bodyLarge
    )
    Spacer(Modifier.size(8.dp))
    Bullet("Select text anywhere, then tap Read aloud (Lector).")
    Bullet("Or share text — or a screenshot — to Lector.")
    Bullet("Screenshots are read on your phone. The image never leaves it.")
    Spacer(Modifier.size(8.dp))
    Text(
        "Lector uses your own ElevenLabs account, so you pay ElevenLabs directly and nothing " +
            "goes through anyone else.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.size(8.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

@Composable
private fun EnterKeyStep(
    container: AppContainer,
    onValidated: (UserAccount, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Debug builds prefill from dev.properties so this screen can be re-run without retyping.
    var apiKey by rememberSaveable {
        mutableStateOf(if (BuildConfig.DEBUG) BuildConfig.DEV_ELEVEN_KEY else "")
    }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("Connect ElevenLabs", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Create an API key on the ElevenLabs dashboard and paste it below.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.size(4.dp))
    Text("The key needs these permissions:", style = MaterialTheme.typography.bodyMedium)
    Bullet("Text to Speech")
    Bullet("User: Read")
    Bullet("Voices: Read")
    Text(
        "Keys are often created with narrower access, which is the most common reason setup fails.",
        style = MaterialTheme.typography.bodySmall
    )

    OutlinedButton(
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(API_KEYS_URL)))
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Open ElevenLabs API keys") }

    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it; error = null },
        label = { Text("API key") },
        singleLine = true,
        enabled = !checking,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        trailingIcon = {
            TextButton(onClick = { revealed = !revealed }) {
                Text(if (revealed) "Hide" else "Show")
            }
        },
        isError = error != null,
        modifier = Modifier.fillMaxWidth()
    )

    error?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    Button(
        onClick = {
            checking = true
            error = null
            scope.launch {
                when (val outcome = validateAndStore(container, apiKey)) {
                    is SetupOutcome.Ready -> onValidated(outcome.account, outcome.voiceName)
                    is SetupOutcome.Failed -> {
                        error = outcome.message
                        checking = false
                    }
                }
            }
        },
        enabled = apiKey.isNotBlank() && !checking,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (checking) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Checking…")
            }
        } else {
            Text("Continue")
        }
    }
}

@Composable
private fun ReadyStep(account: UserAccount?, voiceName: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    var notificationsHandled by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notificationsHandled = true }

    val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

    Text("You're set up", style = MaterialTheme.typography.headlineSmall)

    account?.let {
        Text("Plan: ${it.tier}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "${it.charactersRemaining} of ${it.characterLimit} characters left this period.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (!it.canUseInstantVoiceCloning) {
            Text(
                "Your plan can't use cloned voices, so Lector will only offer voices that work.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    voiceName?.let { Text("Voice: $it", style = MaterialTheme.typography.bodyMedium) }

    if (needsNotifications && !notificationsHandled) {
        Spacer(Modifier.size(8.dp))
        Text(
            "Lector shows a notification while it's reading so you can pause it without coming " +
                "back to the app. Without it, playback still works but you lose those controls.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Allow notifications") }
    }

    Spacer(Modifier.size(8.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Start using Lector") }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private sealed interface SetupOutcome {
    data class Ready(val account: UserAccount, val voiceName: String) : SetupOutcome
    data class Failed(val message: String) : SetupOutcome
}

/**
 * Validates the pasted key and only then stores anything.
 *
 * `/v1/user` proves the key exists and carries `User: Read`; `/v2/voices` proves `Voices: Read`.
 * The Text-to-Speech scope can't be checked without spending the user's characters, so it stays
 * listed on screen rather than tested.
 */
private suspend fun validateAndStore(container: AppContainer, apiKey: String): SetupOutcome {
    val trimmed = apiKey.trim()

    val account = when (val result = container.elevenLabsApi.getUser(trimmed)) {
        is io.github.diet103.lector.tts.ApiResult.Ok -> result.value
        is io.github.diet103.lector.tts.ApiResult.Failed -> return SetupOutcome.Failed(result.error.message)
    }

    val voices = when (val result = container.elevenLabsApi.getVoices(trimmed)) {
        is io.github.diet103.lector.tts.ApiResult.Ok -> result.value
        is io.github.diet103.lector.tts.ApiResult.Failed -> return SetupOutcome.Failed(result.error.message)
    }

    // Never default to a voice the plan will refuse at synthesis time — that failure would only
    // show up later, mid-read, looking like a Lector bug.
    val usable = voices.firstOrNull { !it.isCloned || account.canUseInstantVoiceCloning }
        ?: return SetupOutcome.Failed(
            "This account has no voices Lector can use. Add a voice on the ElevenLabs dashboard."
        )

    if (!container.apiKeyStore.save(trimmed)) {
        return SetupOutcome.Failed("Couldn't store the key securely on this device.")
    }
    container.settings.setVoiceId(usable.voiceId)

    return SetupOutcome.Ready(account, usable.name)
}

private const val API_KEYS_URL = "https://elevenlabs.io/app/developers/api-keys"
