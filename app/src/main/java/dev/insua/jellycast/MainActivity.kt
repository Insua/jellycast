package dev.insua.jellycast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.insua.jellycast.designsystem.JellyCastTheme
import dev.insua.jellycast.navigation.JellyCastNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JellyCastTheme {
                JellyCastNavHost()
            }
        }
    }
}
