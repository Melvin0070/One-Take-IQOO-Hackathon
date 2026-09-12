package com.onetake.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Scaffold only. Lane G owns every screen from here.
 * Read app/AGENTS.md and docs/agents/lanes.md#lane-g before adding to it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OneTakeApp() }
    }
}

@Composable
private fun OneTakeApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text("One-Take")
        }
    }
}
