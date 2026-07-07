package com.rjnr.pocketnode.ui.screens.status

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.DatabaseMaintenanceUtil
import com.rjnr.pocketnode.data.gateway.GatewayRepository
import com.rjnr.pocketnode.data.gateway.models.JniHeaderView
import com.rjnr.pocketnode.data.gateway.models.JniRemoteNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject

data class NodeStatusUiState(
    val tipHeader: JniHeaderView? = null,
    val peers: List<JniRemoteNode> = emptyList(),
    val scriptsJson: String = "",
    val rpcResult: String = "",
    val logs: List<String> = emptyList(),
    val dbSizeBytes: Long = 0L,
    /** Persistent app-error journal (survives restarts; works in release builds). */
    val appErrors: List<com.rjnr.pocketnode.data.diagnostics.ErrorJournal.Entry> = emptyList(),
)

@HiltViewModel
class NodeStatusViewModel @Inject constructor(
    private val repository: GatewayRepository,
    private val json: Json,
    private val appDatabase: AppDatabase,
    private val errorJournal: com.rjnr.pocketnode.data.diagnostics.ErrorJournal,
) : ViewModel() {

    /** Copy-all payload for the App errors card. */
    fun errorDump(): String = errorJournal.dump()

    fun clearErrorJournal() {
        errorJournal.clear()
        _uiState.update { it.copy(appErrors = emptyList()) }
    }

    private val _uiState = MutableStateFlow(NodeStatusUiState())
    val uiState: StateFlow<NodeStatusUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var logcatProcess: Process? = null
    private var logJob: Job? = null

    init {
        startRefreshing()
        startLogcat()
    }

    private fun startRefreshing() {
        refreshJob = viewModelScope.launch {
            while (isActive) {
                updateStatus()
                delay(3000) // Refresh every 3 seconds
            }
        }
    }

    private suspend fun updateStatus() {
        // Each repository call is wrapped individually so a transient JNI
        // failure (common during sync-mode restart, where the light client
        // briefly throws while the new mode initializes) does not freeze the
        // displayed state on the previous mode's stale values. Without this,
        // the outer catch would short-circuit before _uiState.update and the
        // old tipHeader/peers would stick around indefinitely. (#90)
        val tipRaw = runCatching { repository.getTipHeader() ?: "" }.getOrDefault("")
        val peersRaw = runCatching { repository.getPeers() ?: "" }.getOrDefault("")
        val scripts = runCatching { repository.getScripts() ?: "" }.getOrDefault("")

        val parsedTip = runCatching {
            if (tipRaw.isBlank()) null else json.decodeFromString<JniHeaderView>(tipRaw)
        }.getOrNull()

        val parsedPeers = runCatching {
            if (peersRaw.isBlank()) emptyList() else json.decodeFromString<List<JniRemoteNode>>(peersRaw)
        }.getOrDefault(emptyList())

        val dbSize = withContext(Dispatchers.IO) {
            runCatching { DatabaseMaintenanceUtil.getDatabaseSizeBytes(appDatabase) }.getOrDefault(0L)
        }

        _uiState.update {
            it.copy(
                tipHeader = parsedTip,
                peers = parsedPeers,
                scriptsJson = scripts,
                dbSizeBytes = dbSize,
                appErrors = errorJournal.entries().asReversed(), // newest first
            )
        }
    }

    fun callRpc(method: String) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(rpcResult = "Calling...") }
                val result = repository.callRpc(method) ?: "null"
                _uiState.update { it.copy(rpcResult = result) }
            } catch (e: Exception) {
                _uiState.update { it.copy(rpcResult = "Error: ${e.message}") }
            }
        }
    }

    private fun startLogcat() {
        logJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Clear previous logs
                _uiState.update { it.copy(logs = emptyList()) }

                // Start logcat process
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "time", "-s", "ckb-light-client:*", "LightClientService:*", "LightClientNative:*", "NodeStatusVM:*")
                )
                logcatProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String? = reader.readLine()
                val batch = ArrayList<String>()
                var lastUpdate = System.currentTimeMillis()

                while (isActive && line != null) {
                    batch.add(line)
                    
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 500 || batch.size > 100) {
                        val newBatch = batch.toList()
                        batch.clear()
                        lastUpdate = now
                        
                        _uiState.update { current ->
                            val newLogs = current.logs + newBatch
                            current.copy(logs = newLogs.takeLast(1000))
                        }
                    }
                    
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.e("NodeStatusVM", "Error reading logs", e)
                _uiState.update { it.copy(logs = it.logs + "Error reading logs: ${e.message}") }
            }
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        logJob?.cancel()
        logcatProcess?.destroy()
    }
}
