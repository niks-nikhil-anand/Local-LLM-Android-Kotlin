package com.example.localai.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * Everything Home shows, all from real sources. Null means "not known yet" (or unreadable): the
 * screen shows a loading or unavailable state, never a stand-in number.
 */
data class HomeUiState(
    /** Usable downloaded models and their storage, from [LocalModelInventory]. Null until first read. */
    val inventory: InventorySummary? = null,
    /** The model actually loaded in the shared inference session — independent of downloads. */
    val runtime: ChatModelState = ChatModelState.NoModel,
    /** Record id of the loaded model, so the right card is marked even if file names repeat. */
    val loadedRecordId: String? = null,
    /** Measured memory and storage. Null until measured or if measuring failed. */
    val device: DeviceResourceSnapshot? = null,
    /** The first measurement has finished (so a null [device] means it failed, not pending). */
    val deviceMeasured: Boolean = false,
    /** Set when the local model records could not be read. */
    val error: String? = null
) {
    val loading: Boolean get() = inventory == null && error == null
}

/**
 * Home's single state holder. It reads the same sources as My Models and Chat — the download
 * records via [LocalModelInventory], the shared [ModelSessionManager] and the device provider —
 * so the screens cannot disagree. It reacts to record and session changes; [refresh] re-checks the
 * files and re-measures the device (called when Home resumes). No polling.
 */
class HomeViewModel(
    private val inventory: LocalModelInventory,
    private val session: ModelSessionManager?,
    private val deviceResources: DeviceResourceProvider
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            inventory.summary
                .catch { e ->
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = "Your downloaded models could not be read: ${e.message ?: e.javaClass.simpleName}") }
                }
                .collect { summary -> _state.update { it.copy(inventory = summary, error = null) } }
        }
        viewModelScope.launch {
            val runtime = session?.let { s ->
                combine(s.engineState, s.active, s.loadStatus) { engine, active, status ->
                    modelRuntimeState(engine, active, status) to active?.recordId
                }
            } ?: flowOf(modelRuntimeState(EngineState.Unavailable("No inference engine"), null, emptyMap()) to null)
            runtime.collect { (r, id) -> _state.update { it.copy(runtime = r, loadedRecordId = id) } }
        }
        measure()
    }

    /** Re-checks model files (e.g. removed outside the app) and re-measures memory and storage. */
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
