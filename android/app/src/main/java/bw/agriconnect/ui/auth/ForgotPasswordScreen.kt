package bw.agriconnect.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.components.AgriPasswordField
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.BrandLogo
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ForgotPasswordViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = AgriSpacing.xl, vertical = AgriSpacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo(markSize = 56.dp)
        Spacer(Modifier.height(AgriSpacing.lg))
        Text("Reset password", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(AgriSpacing.lg))
        when (state.step) {
            ResetStep.Request -> {
                AgriTextField(value = state.identifier, onValueChange = viewModel::onIdentifierChange, label = "Phone or email")
                Spacer(Modifier.height(AgriSpacing.lg))
                AgriPrimaryButton(text = "Send reset code", onClick = { viewModel.requestCode() }, loading = state.isBusy)
            }
            ResetStep.Verify -> {
                AgriTextField(value = state.code, onValueChange = viewModel::onCodeChange, label = "Reset code", keyboardType = KeyboardType.Number)
                Spacer(Modifier.height(AgriSpacing.lg))
                AgriPrimaryButton(text = "Verify", onClick = viewModel::verify, loading = state.isBusy)
            }
            ResetStep.Reset -> {
                AgriPasswordField(value = state.newPassword, onValueChange = viewModel::onNewPasswordChange, label = "New password")
                Spacer(Modifier.height(AgriSpacing.md))
                AgriPasswordField(value = state.confirmPassword, onValueChange = viewModel::onConfirmPasswordChange, label = "Confirm password")
                Spacer(Modifier.height(AgriSpacing.lg))
                AgriPrimaryButton(text = "Update password", onClick = { viewModel.reset(onDone) }, loading = state.isBusy)
            }
            ResetStep.Done -> {
                Text("Password updated. You can sign in now.", style = MaterialTheme.typography.bodyMedium, color = AgriColors.SuccessGreen)
                Spacer(Modifier.height(AgriSpacing.lg))
                AgriPrimaryButton(text = "Back to sign in", onClick = onDone)
            }
        }
        if (state.message != null) { Spacer(Modifier.height(AgriSpacing.md)); Text(state.message.orEmpty(), color = AgriColors.SuccessGreen) }
        if (state.error != null) { Spacer(Modifier.height(AgriSpacing.md)); Text(state.error.orEmpty(), color = AgriColors.ErrorRed) }
        Spacer(Modifier.height(AgriSpacing.lg))
        AgriPrimaryButton(text = "Back", onClick = onBack, enabled = !state.isBusy)
    }
}