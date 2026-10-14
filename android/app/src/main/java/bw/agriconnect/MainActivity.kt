package bw.agriconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import bw.agriconnect.ui.AgriConnectRoot
import bw.agriconnect.ui.theme.AgriConnectTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity. Compose owns navigation from here; `@AndroidEntryPoint` makes
 * it a Hilt injection site so the navigation host can obtain its `@HiltViewModel`s.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgriConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AgriConnectRoot()
                }
            }
        }
    }
}
