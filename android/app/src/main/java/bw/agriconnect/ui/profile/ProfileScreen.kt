package bw.agriconnect.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.core.util.ImageFiles
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriRemoteImage
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.components.RoleBadge
import bw.agriconnect.ui.theme.AgriColors
import bw.agriconnect.ui.theme.AgriSpacing
import kotlinx.coroutines.launch

/**
 * Profile hub for the signed-in user: identity, editable contact details, password
 * change, and the shortcuts into settings, notification preferences, privacy, account
 * deletion and sign-out. Rendered as a top-level tab (no back affordance).
 */
@Composable
fun ProfileScreen(
    onBack: (() -> Unit)? = null,
    onSettings: () -> Unit = {},
    onNotifications: () -> Unit,
    onPrivacy: () -> Unit,
    onDeleteAccount: () -> Unit,
    onLogout: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            ImageFiles.read(context, uri)
                .onSuccess(viewModel::uploadAvatar)
                .onFailure { viewModel.onAvatarError(it.message ?: "Could not read the selected image.") }
        }
    }

    MainScaffold(title = "Profile", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(
                            imageUrl = state.avatarUrl,
                            initials = state.stateLocalInitials(),
                        )
                        Spacer(Modifier.size(AgriSpacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = state.fullName.ifBlank { state.session?.fullName.orEmpty() },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            state.session?.let { RoleBadge(role = it.role) }
                        }
                    }
                    AgriSecondaryButton(
                        text = if (state.avatarUrl == null) "Add profile photo" else "Change profile photo",
                        onClick = { pickAvatar.launch("image/*") },
                        enabled = !state.isUploadingAvatar,
                        loading = state.isUploadingAvatar,
                    )
                }
            }

            if (state.message != null) InfoBanner(message = state.message.orEmpty())
            if (state.error != null) ErrorBanner(message = state.error.orEmpty())

            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                    Text(
                        text = "Your details",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AgriTextField(
                        value = state.fullName,
                        onValueChange = viewModel::onNameChange,
                        label = "Full name",
                    )
                    AgriTextField(
                        value = state.email,
                        onValueChange = viewModel::onEmailChange,
                        label = "Email",
                    )
                    AgriPrimaryButton(
                        text = "Save profile",
                        onClick = viewModel::saveProfile,
                        loading = state.isSaving,
                    )
                    AgriSecondaryButton(
                        text = "Change password",
                        onClick = { viewModel.togglePasswordDialog(true) },
                    )
                }
            }

            AgriCard {
                Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
                    Text(
                        text = "Preferences",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AgriSecondaryButton(text = "App settings", onClick = onSettings)
                    AgriSecondaryButton(text = "Notification preferences", onClick = onNotifications)
                    AgriSecondaryButton(text = "Privacy & consent", onClick = onPrivacy)
                }
            }

            HorizontalDivider()

            AgriSecondaryButton(text = "Delete account", onClick = onDeleteAccount, accent = true)
            AgriPrimaryButton(text = "Sign out", onClick = { viewModel.logout(onLogout) })
        }
    }

    if (state.showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.togglePasswordDialog(false) },
            title = { Text("Change password") },
            text = {
                Column {
                    AgriTextField(
                        value = state.currentPassword,
                        onValueChange = viewModel::onCurrentPasswordChange,
                        label = "Current password",
                    )
                    Spacer(Modifier.height(AgriSpacing.sm))
                    AgriTextField(
                        value = state.newPassword,
                        onValueChange = viewModel::onNewPasswordChange,
                        label = "New password",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::changePassword) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.togglePasswordDialog(false) }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileAvatar(imageUrl: String?, initials: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(AgriColors.ForestGreen),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNullOrBlank()) {
            Text(initials, color = Color.White, style = MaterialTheme.typography.titleMedium)
        } else {
            AgriRemoteImage(
                url = imageUrl,
                contentDescription = "Profile photo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private fun ProfileUiState.stateLocalInitials(): String =
    (fullName.ifBlank { session?.fullName ?: "A" })
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.take(1).uppercase() }
        .ifBlank { "A" }
