package io.github.diet103.lector.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.diet103.lector.BuildConfig

const val REPO_URL = "https://github.com/diet103/lector"
private const val PRIVACY_URL = "https://github.com/diet103/lector/blob/main/PRIVACY.md"
private const val LICENSE_URL = "https://github.com/diet103/lector/blob/main/LICENSE"

/**
 * About + licences (PLAN §6 P7).
 *
 * The list is written by hand rather than generated. Six direct dependencies do not justify pulling
 * in the OSS-licences Gradle plugin, and a hand-written list is the only kind that can say the one
 * thing users actually need to know here — that ML Kit is the single component that is *not* open
 * source, even though everything Lector itself does with it happens offline.
 */
@Composable
fun AboutScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("About", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onDone) { Text("Done") }
            }

            Text("Lector ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Select-to-speak for Android, using your own ElevenLabs key. Free and open " +
                    "source, no accounts, no analytics, no ads.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { open(REPO_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Source code")
            }
            OutlinedButton(onClick = { open(PRIVACY_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Privacy policy")
            }
            OutlinedButton(onClick = { open(LICENSE_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Licence (Apache 2.0)")
            }

            SectionTitle("What Lector sends")
            Text(
                "Only the text you asked it to read, sent from this phone straight to " +
                    "ElevenLabs with your key. Screenshots are read on this device and the image " +
                    "itself is never uploaded. Nothing goes anywhere else.",
                style = MaterialTheme.typography.bodySmall
            )

            SectionTitle("Built with")
            DEPENDENCIES.forEach { dependency ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(dependency.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${dependency.holder} — ${dependency.licence}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            SectionTitle("One component is not open source")
            Text(
                "ML Kit Text Recognition, which reads text out of screenshots, is a Google " +
                    "library covered by Google's own terms rather than an open-source licence. " +
                    "It runs entirely offline — it is bundled into the app and never contacts " +
                    "Google — but it is the reason Lector cannot be listed on F-Droid as-is.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.size(24.dp))
        }
    }
}

private data class Dependency(val name: String, val holder: String, val licence: String)

private val DEPENDENCIES = listOf(
    Dependency("Jetpack Compose and AndroidX", "Google", "Apache 2.0"),
    Dependency("AndroidX Media3 / ExoPlayer", "Google", "Apache 2.0"),
    Dependency("Kotlin and kotlinx.coroutines", "JetBrains", "Apache 2.0"),
    Dependency("OkHttp and Okio", "Square", "Apache 2.0"),
    Dependency("jsoup", "Jonathan Hedley", "MIT"),
    Dependency("ML Kit Text Recognition", "Google", "Google APIs Terms of Service")
)

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.size(8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}
