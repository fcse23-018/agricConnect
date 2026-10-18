package bw.agriconnect.ui.settings

import androidx.lifecycle.ViewModel
import bw.agriconnect.core.config.ServerConfig
import bw.agriconnect.core.util.BaseUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** State for the debug-only server picker. */
data class ServerSettingsUiState(
    /** What is currently typed in the field, not necessarily what is in use. */
    val input: String = "",
    /** The base URL this process is actually using. */
    val currentBaseUrl: String = "",
    /** The URL compiled into the build; what "Reset" restores. */
    val defaultBaseUrl: String = "",
    val isOverridden: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** Set once a change is persisted: the caller must restart the process to apply it. */
    val restartRequired: Boolean = false,
)

/**
 * Backs the debug "Server" screen.
 *
 * Nothing here can take effect mid-process: the Retrofit base URL is a singleton and
 * relative image paths resolve against an origin seeded at start-up. Saving therefore
 * writes the preference and asks the screen to restart the app.
 */
@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    private val serverConfig: ServerConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ServerSettingsUiState(
            input = serverConfig.baseUrl,
            currentBaseUrl = serverConfig.baseUrl,
            defaultBaseUrl = serverConfig.defaultBaseUrl,
            isOverridden = serverConfig.isOverridden,
        ),
    )
    val state: StateFlow<ServerSettingsUiState> = _state.asStateFlow()

    fun onInputChange(value: String) = _state.update { it.copy(input = value, error = null, message = null) }

    fun save() {
        val current = _state.value
        val normalized = BaseUrl.normalize(current.input)
        when {
            normalized == null ->
                _state.update { it.copy(error = "Enter an absolute http:// or https:// URL.", message = null) }

            normalized == current.currentBaseUrl ->
                _state.update { it.copy(error = null, message = "Already using this server.") }

            else -> {
                serverConfig.save(normalized)
                _state.update { it.copy(error = null, message = null, restartRequired = true) }
            }
        }
    }

    fun reset() {
        if (!_state.value.isOverridden) {
            _state.update { it.copy(error = null, message = "Already using the default server.") }
            return
        }
        serverConfig.reset()
        _state.update { it.copy(error = null, message = null, restartRequired = true) }
    }

    /** Consumed by the screen once it has restarted, so a recomposition cannot restart twice. */
    fun onRestartHandled() = _state.update { it.copy(restartRequired = false) }
}
