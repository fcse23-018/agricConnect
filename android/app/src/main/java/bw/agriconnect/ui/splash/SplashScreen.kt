package bw.agriconnect.ui.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import bw.agriconnect.data.repository.AuthRepository
import bw.agriconnect.ui.components.BrandLogo
import bw.agriconnect.ui.theme.AgriSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores the session once, then reports whether the user is signed in.
 *
 * Restoring only needs the persisted identity and the presence of a refresh token
 * (see [bw.agriconnect.data.session.SessionManager.restore]); no network call is
 * made here, so a cold start offline still lands on the right screen instantly.
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _signedIn = MutableStateFlow<Boolean?>(null)

    /** `null` while resolving; `true`/`false` once the session state is known. */
    val signedIn: StateFlow<Boolean?> = _signedIn.asStateFlow()

    init {
        viewModelScope.launch {
            val session = authRepository.restoreSession()
            _signedIn.value = session != null
        }
    }
}

@Composable
fun SplashScreen(
    onResolved: (Boolean) -> Unit,
    viewModel: SplashViewModel = hiltViewModel(),
) {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()

    LaunchedEffect(signedIn) {
        signedIn?.let(onResolved)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AgriSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandLogo(markSize = 72.dp)
        Spacer(Modifier.height(AgriSpacing.lg))
        Text(
            text = "Farming made simpler.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
