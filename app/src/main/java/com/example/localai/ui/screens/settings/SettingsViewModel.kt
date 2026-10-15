package com.example.localai.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.localai.data.AppInfo
import com.example.localai.data.device.DeviceResourceProvider
import com.example.localai.data.device.DeviceResourceSnapshot
import com.example.localai.data.download.InventorySummary
import com.example.localai.data.download.LocalModelInventory
import com.example.localai.data.inference.EngineState
import com.example.localai.data.inference.ModelSessionManager
import com.example.localai.ui.state.ChatModelState
import com.example.localai.ui.state.modelRuntimeState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the native engine can run here, as reported by the engine itself. */
sealed interface EngineAvailability {
    /** Not reported by the session yet. */
    data object Checking : EngineAvailability
    data object Available : EngineAvailability
    data class Unavailable(val reason: String) : EngineAvailability
}

/** Settings' real data. Null = not read yet or unreadable: shown as such, never as a number. */
data class SettingsUiState(
    /** Same definition as Home and My Models (LocalModelInventory). */
    val inventory: InventorySummary? = null,
    val inventoryError: String? = null,
    val engine: EngineAvailability = EngineAvailability.Checking,
    /** The model loaded in the shared inference session; independent of what is downloaded. */
    val runtime: ChatModelState = ChatModelState.NoModel,
    val device: DeviceResourceSnapshot? = null,
    /** The first measurement has finished (so a null [device] means it failed, not pending). */
    val deviceMeasured: Boolean = false,
    val app: AppInfo? = null
)

/**
 * Settings' state holder. It reads the same shared sources as Home and My Models, so the three
 * screens cannot disagree; [refresh] re-checks files and re-measures the device on resume.
 */
class SettingsViewModel(
    private val inventory: LocalModelInventory,
    private val session: ModelSessionManager?,
    private val deviceResources: DeviceResourceProvider,
    appInfo: () -> AppInfo?
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(app = runCatching { appInfo() }.getOrNull()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            inventory.summary
                .catch { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(inventoryError = "Downloaded models could not be read: ${e.message ?: e.javaClass.simpleName}") }
                }
                .collect { summary -> _state.update { it.copy(inventory = summary, inventoryError = null) } }
        }
        viewModelScope.launch {
            val flow = session?.let { s ->
                combine(s.engineState, s.active, s.loadStatus) { engine, active, status ->
                    val availability = if (engine is EngineState.Unavailable) {
                        EngineAvailability.Unavailable(engine.reason)
                    } else {
                        EngineAvailability.Available
                    }
                    availability to modelRuntimeState(engine, active, status)
                }
            } ?: flowOf(EngineAvailability.Unavailable("No inference engine in this build") to ChatModelState.Unavailable("none"))
            flow.collect { (engine, runtime) -> _state.update { it.copy(engine = engine, runtime = runtime) } }
        }
        measure()
    }

    fun refresh() {
        inventory.refresh()
        measure()
    }

    private fun measure() {
        viewModelScope.launch {
            val snapshot = try {
                deviceResources.snapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                null
            }
            _state.update { it.copy(device = snapshot, deviceMeasured = true) }
        }
    }
}
