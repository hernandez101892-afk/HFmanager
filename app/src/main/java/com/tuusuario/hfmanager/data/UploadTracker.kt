package com.tuusuario.hfmanager.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UploadTracker {
    sealed class UploadState {
        object Idle : UploadState()
        data class Processing(val fileName: String, val step: String) : UploadState()
        data class Uploading(
            val originalName: String,
            val newName: String,
            val bytesWritten: Long,
            val totalBytes: Long,
            val progress: Float, // 0.0f to 1.0f
            val speed: String = ""
        ) : UploadState()
        data class Success(val fileName: String, val commitUrl: String) : UploadState()
        data class Error(val fileName: String, val errorMessage: String) : UploadState()
    }

    private val _currentState = MutableStateFlow<UploadState>(UploadState.Idle)
    val currentState: StateFlow<UploadState> = _currentState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isCancelled = MutableStateFlow(false)
    val isCancelled: StateFlow<Boolean> = _isCancelled.asStateFlow()

    fun updateState(state: UploadState) {
        _currentState.value = state
        when (state) {
            is UploadState.Idle -> addLog("SYSTEM: Engine status: STANDBY_IDLE.")
            is UploadState.Processing -> addLog("PROCESS: File `${state.fileName}` processed node: ${state.step}")
            is UploadState.Uploading -> {
                // We avoid logging every byte packet to keep terminal readable
            }
            is UploadState.Success -> addLog("SUCCESS: Sincronización completa de `${state.fileName}`")
            is UploadState.Error -> addLog("FAILURE: Node error in `${state.fileName}`: ${state.errorMessage}")
        }
    }

    fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedLog = "[$timestamp] $message"
        _logs.value = (_logs.value + formattedLog).takeLast(120)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun triggerCancellation() {
        _isCancelled.value = true
        addLog("SYSTEM: Cancellation signal triggered.")
    }

    fun resetCancellation() {
        _isCancelled.value = false
    }
}
