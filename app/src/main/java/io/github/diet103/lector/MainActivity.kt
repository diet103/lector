package io.github.diet103.lector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.diet103.lector.ui.dev.DevSpeakScreen
import io.github.diet103.lector.ui.theme.LectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LectorTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DevSpeakScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
