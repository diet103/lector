package io.github.diet103.lector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.diet103.lector.ui.about.AboutScreen
import io.github.diet103.lector.ui.home.HomeScreen
import io.github.diet103.lector.ui.onboarding.OnboardingScreen
import io.github.diet103.lector.ui.settings.SettingsScreen
import io.github.diet103.lector.ui.theme.LectorTheme
import io.github.diet103.lector.ui.voices.VoicePickerScreen

/** Where the single activity currently is. A plain enum: four destinations don't earn a nav library. */
private enum class Screen { Home, Settings, Voices, About }

/**
 * Onboarding until there's a usable key and voice, then Home. The notification permission is
 * asked for inside onboarding with a rationale (PLAN §6 P5), replacing P2's bare prompt-on-launch.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LectorApplication).container

        setContent {
            LectorTheme {
                var setUp by remember { mutableStateOf(container.isSetUp) }
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }

                // Back out of a sub-screen rather than leaving the app.
                BackHandler(enabled = setUp && screen != Screen.Home) {
                    screen = when (screen) {
                        Screen.Voices, Screen.About -> Screen.Settings
                        else -> Screen.Home
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val content = Modifier.padding(innerPadding)

                    if (!setUp) {
                        OnboardingScreen(
                            container = container,
                            onFinished = { setUp = true; screen = Screen.Home },
                            modifier = content
                        )
                        return@Scaffold
                    }

                    when (screen) {
                        Screen.Home -> HomeScreen(
                            container = container,
                            onChangeKey = { signOut(container); setUp = false },
                            onOpenSettings = { screen = Screen.Settings },
                            modifier = content
                        )

                        Screen.Settings -> SettingsScreen(
                            container = container,
                            onPickVoice = { screen = Screen.Voices },
                            onOpenAbout = { screen = Screen.About },
                            onSignOut = { signOut(container); setUp = false },
                            onDone = { screen = Screen.Home },
                            modifier = content
                        )

                        Screen.Voices -> VoicePickerScreen(
                            container = container,
                            onDone = { screen = Screen.Settings },
                            modifier = content
                        )

                        Screen.About -> AboutScreen(
                            onDone = { screen = Screen.Settings },
                            modifier = content
                        )
                    }
                }
            }
        }
    }

    /** Clears the key and every setting; the ElevenLabs account itself is untouched. */
    private fun signOut(container: io.github.diet103.lector.app.AppContainer) {
        container.apiKeyStore.clear()
        container.settings.clear()
        container.lastError.clear()
    }
}
