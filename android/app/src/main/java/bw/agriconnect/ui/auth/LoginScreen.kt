package bw.agriconnect.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.BuildConfig
import bw.agriconnect.ui.components.AgriPasswordField
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.AuthScaffold
import bw.agriconnect.ui.components.BrandLogo
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Sign-in screen. A pure function of [LoginUiState]; all side effects live in the
 * ViewModel. On success it hands control to [onLoggedIn] (the nav graph replaces
 * this destination so "back" cannot return to a signed-out login form).
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onForgotPassword: () -> Unit = {},
    onRegister: () -> Unit = {},
    onServerSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AuthScaffold(onBack = onBack) {
        Spacer(Modifier.height(AgriSpacing.xl))
        BrandLogo(markSize = 64.dp)
        Spacer(Modifier.height(AgriSpacing.lg))

        Text(
            text = "Welcome back",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(AgriSpacing.xs))
        Text(
            text = "Sign in to sync your farm records.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(AgriSpacing.xl))

        AgriTextField(
            value = state.identifier,
            onValueChange = viewModel::onIdentifierChange,
            label = "Phone number or email",
            error = state.identifierError,
            enabled = !state.isSubmitting,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        )

        Spacer(Modifier.height(AgriSpacing.lg))

        AgriPasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = "Password",
            error = state.passwordError,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Done,
        )

        if (state.formError != null) {
            Spacer(Modifier.height(AgriSpacing.md))
            Text(
                text = state.formError.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = AgriColors.ErrorRed,
            )
        }

        Spacer(Modifier.height(AgriSpacing.xl))

        AgriPrimaryButton(
            text = "Sign in",
            onClick = { viewModel.submit(onLoggedIn) },
            enabled = state.canSubmit,
            loading = state.isSubmitting,
        )

        Spacer(Modifier.height(AgriSpacing.md))

        AgriTextFieldButton(text = "Forgot password?", onClick = onForgotPassword, enabled = !state.isSubmitting)

        Spacer(Modifier.height(AgriSpacing.xs))

        AgriTextFieldButton(text = "Create an account", onClick = onRegister, enabled = !state.isSubmitting)

        // Debug builds only: recover from a wrong server address before signing in.
        if (BuildConfig.DEBUG && onServerSettings != null) {
            Spacer(Modifier.height(AgriSpacing.md))
            AgriTextFieldButton(text = "Server settings", onClick = onServerSettings, enabled = true)
            Text(
                text = BuildConfig.API_BASE_URL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A centred text link styled as a low-emphasis action. */
@Composable
private fun AgriTextFieldButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = AgriColors.ForestGreen)
    }
}
