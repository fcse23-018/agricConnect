package bw.agriconnect.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bw.agriconnect.ui.chrome.MainScaffold
import bw.agriconnect.ui.components.AgriCard
import bw.agriconnect.ui.components.AgriPrimaryButton
import bw.agriconnect.ui.components.AgriSecondaryButton
import bw.agriconnect.ui.components.AgriTextField
import bw.agriconnect.ui.components.ErrorBanner
import bw.agriconnect.ui.components.InfoBanner
import bw.agriconnect.ui.theme.AgriSpacing

/**
 * Account verification: request a six-digit code, then enter it.
 *
 * The screen is deliberately explicit about three states a farmer can otherwise
 * be left guessing about:
 *  - whether the code was actually sent (the server reports the transport outcome
 *    to the signed-in owner only);
 *  - what to do when it was not (the Twilio-trial case is the common one);
 *  - that verification can be finished later without losing the account.
 *
 * It is reachable from Profile and directly after registration.
 */
@Composable
fun VerifyAccountScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: VerifyAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MainScaffold(title = "Verify your account", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(AgriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AgriSpacing.md),
        ) {
            when (state.step) {
                VerifyStep.LOADING -> LoadingBlock(state, viewModel)
                VerifyStep.ALREADY_VERIFIED, VerifyStep.VERIFIED -> VerifiedBlock(state, onDone)
                VerifyStep.UNAVAILABLE -> UnavailableBlock(onDone)
                VerifyStep.REQUEST_CODE, VerifyStep.ENTER_CODE -> CodeBlock(state, viewModel, onDone)
            }
        }
    }
}

@Composable
private fun LoadingBlock(state: VerifyAccountUiState, viewModel: VerifyAccountViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AgriSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        if (state.error == null) {
            CircularProgressIndicator()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                ErrorBanner(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::load,
                    onDismiss = viewModel::dismissError,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(
    state: VerifyAccountUiState,
    viewModel: VerifyAccountViewModel,
    onDone: () -> Unit,
) {
    AgriCard {
        Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            Text("Why verify?", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Verification confirms the phone number on your account. We use it for " +
                    "order updates and account recovery. You can keep using AgriConnect while " +
                    "your account is unverified.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (state.deliveryNotice != null) {
        val retrySend: (() -> Unit)? =
            if (state.resendSeconds == 0) viewModel::resendCode else null
        ErrorBanner(
            message = state.deliveryNotice.orEmpty(),
            onRetry = retrySend,
            onDismiss = viewModel::dismissDeliveryNotice,
        )
    }
    if (state.message != null) InfoBanner(message = state.message.orEmpty())
    if (state.devCode != null) {
        InfoBanner(
            message = "Development code: ${state.devCode}. This is shown only because the " +
                "server is running with dev codes enabled; it is never shown in production.",
        )
    }
    if (state.error != null) {
        ErrorBanner(
            message = state.error.orEmpty(),
            onRetry = if (state.step == VerifyStep.ENTER_CODE) viewModel::verify else viewModel::sendCode,
            onDismiss = viewModel::dismissError,
        )
    }

    if (state.step == VerifyStep.REQUEST_CODE) {
        AgriCard {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                Text("Send a code", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "We'll send a 6-digit code by SMS to ${state.identifier}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    // The code is minted for the account's own contact, so an
                    // editable field would only offer a destination that cannot
                    // work. Point at the profile instead.
                    text = "This is the number saved on your account. If it is wrong, " +
                        "update your profile first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AgriPrimaryButton(
                    text = "Send code",
                    onClick = viewModel::sendCode,
                    loading = state.isBusy,
                )
            }
        }
        AgriSecondaryButton(text = "I'll do this later", onClick = onDone)
    } else {
        AgriCard {
            Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.md)) {
                Text("Enter the code", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "We asked to send a 6-digit code to ${state.identifier}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AgriTextField(
                    value = state.code,
                    onValueChange = viewModel::onCodeChange,
                    label = "6-digit code",
                    helper = "The code expires in ${state.expiresInMinutes} minutes.",
                    keyboardType = KeyboardType.Number,
                    enabled = !state.isBusy,
                )
                AgriPrimaryButton(
                    text = "Verify account",
                    onClick = viewModel::verify,
                    enabled = state.canSubmitCode,
                    loading = state.isBusy,
                )
                AgriSecondaryButton(
                    text = resendLabel(state.resendSeconds),
                    onClick = viewModel::resendCode,
                    enabled = state.resendSeconds == 0 && !state.isBusy,
                )
            }
        }
        AgriSecondaryButton(text = "I'll do this later", onClick = onDone)
    }
}

@Composable
private fun VerifiedBlock(state: VerifyAccountUiState, onDone: () -> Unit) {
    AgriCard {
        Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            Text("Account verified", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (state.step == VerifyStep.VERIFIED) {
                    "Thank you — your account is now verified."
                } else {
                    "This account is already verified. Nothing else is needed."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.user?.let { user ->
                Text(
                    text = "Phone: ${user.phoneNumber}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    AgriPrimaryButton(text = "Continue", onClick = onDone)
}

@Composable
private fun UnavailableBlock(onDone: () -> Unit) {
    AgriCard {
        Column(verticalArrangement = Arrangement.spacedBy(AgriSpacing.sm)) {
            Text("Verification unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "This account is suspended or scheduled for deletion, so it cannot be " +
                    "verified right now. Contact support if you believe this is a mistake.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    AgriPrimaryButton(text = "Back", onClick = onDone)
}
