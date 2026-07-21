package io.github.diet103.lector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.diet103.lector.ui.home.HomeScreen
import io.github.diet103.lector.ui.onboarding.OnboardingScreen
import io.github.diet103.lector.ui.theme.LectorTheme

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

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (setUp) {
                        HomeScreen(
                            container = container,
                            onChangeKey = {
                                container.apiKeyStore.clear()
                                container.lastError.clear()
                                setUp = false
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        OnboardingScreen(
                            container = container,
                            onFinished = { setUp = true },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}
