package dev.insua.jellycast.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder for the real navigation graph. Real routes (server / home / library /
 * player / settings) will be wired up in later tasks once the corresponding
 * :feature modules exist. This exists only so :app compiles and runs in Task 1.
 */
@Composable
fun JellyCastNavHost() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "JellyCast")
        }
    }
}
