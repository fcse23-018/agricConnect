package bw.agriconnect.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
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
import bw.agriconnect.domain.model.UserRole
import bw.agriconnect.ui.components.AgriPasswordField
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.BrandLogo
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing

@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    onBackToLogin: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
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
        Text("Create account", style = MaterialTheme.typography.headlineMedium)
        Text("Join AgriConnect as a farmer, employee or buyer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(AgriSpacing.xl))
        AgriTextField(value = state.fullName, onValueChange = viewModel::onFullNameChange, label = "Full name", error = state.fullNameError)
        Spacer(Modifier.height(AgriSpacing.md))
        AgriTextField(value = state.phone, onValueChange = viewModel::onPhoneChange, label = "Phone number", error = state.phoneError, keyboardType = KeyboardType.Phone)
        Spacer(Modifier.height(AgriSpacing.md))
        AgriTextField(value = state.email, onValueChange = viewModel::onEmailChange, label = "Email (optional)", error = state.emailError, keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(AgriSpacing.md))
        AgriPasswordField(value = state.password, onValueChange = viewModel::onPasswordChange, label = "Password", error = state.passwordError)
        Spacer(Modifier.height(AgriSpacing.md))
        AgriPasswordField(value = state.confirmPassword, onValueChange = viewModel::onConfirmPasswordChange, label = "Confirm password", error = state.confirmPasswordError)
        Spacer(Modifier.height(AgriSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            listOf(UserRole.FARMER, UserRole.FARM_EMPLOYEE, UserRole.BUYER).forEach { r ->
                FilterChip(selected = state.role == r, onClick = { viewModel.onRoleChange(r) }, label = { Text(r.displayName) })
            }
        }
        if (state.formError != null) {
            Spacer(Modifier.height(AgriSpacing.md))
            Text(state.formError.orEmpty(), color = AgriColors.ErrorRed, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(AgriSpacing.xl))
        AgriPrimaryButton(text = "Sign up", onClick = { viewModel.submit(onRegistered) }, enabled = state.canSubmit, loading = state.isSubmitting)
        Spacer(Modifier.height(AgriSpacing.md))
        AgriPrimaryButton(text = "Back to sign in", onClick = onBackToLogin, enabled = !state.isSubmitting)
    }
}