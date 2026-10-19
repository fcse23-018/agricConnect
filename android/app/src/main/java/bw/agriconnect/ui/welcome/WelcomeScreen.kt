package bw.agriconnect.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import bw.agriconnect.BuildConfig
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AuthScaffold
import bw.agriconnect.ui.components.BrandLogo
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onSignIn: () -> Unit,
    onServerSettings: (() -> Unit)? = null,
) {
    AuthScaffold(card = false) {
        BrandLogo(markSize = 80.dp, darkContent = true)
        Spacer(Modifier.height(AgriSpacing.xxl))
        Text(
            text = "Welcome to AgriConnect",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AgriSpacing.sm))
        Text(
            text = "Smart, offline-first farm management, satellite crop insights and produce marketplace for farm owners, farmers and buyers in Botswana.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AgriSpacing.xxl))
        AgriPrimaryButton(text = "Get started", onClick = onGetStarted)
        Spacer(Modifier.height(AgriSpacing.md))
        AgriSecondaryButton(text = "I already have an account", onClick = onSignIn)

        // Debug builds only: let a tester re-point the app before signing in. Without
        // this, a wrong compiled-in or persisted server address is unrecoverable from
        // the login screen, because Server settings lives behind the auth wall.
        if (BuildConfig.DEBUG && onServerSettings != null) {
            Spacer(Modifier.height(AgriSpacing.lg))
            TextButton(onClick = onServerSettings) {
                Text("Server settings", color = Color.White)
            }
            Text(
                text = BuildConfig.API_BASE_URL,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}