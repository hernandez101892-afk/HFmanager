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
            val speed: String = "",
        ) : UploadState()
        data class Success(val fileName: String, val commitUrl: String) : UploadState()
        data class Error(val fileName: String, val errorMessage: String) : UploadState()
    }

    private val _currentState = MutableStateFlow<UploadState>(UploadState.Idle)
    val currentState: StateFlow<UploadState> = _currentState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isCancelled = MutableStateFlow(value = false)
    val isCancelled: StateFlow<Boolean> = _isCancelled.asStateFlow()

    private var lastUpdateMillis = 0L
    private var lastBytesWritten = 0L

    fun updateState(state: UploadState) {
        _currentState.value = state
        when (state) {
            is UploadState.Idle -> {
                addLog("SYSTEM: Engine status: STANDBY_IDLE.")
                lastUpdateMillis = 0L
                lastBytesWritten = 0L
            }
            is UploadState.Processing -> addLog("PROCESS: File `${state.fileName}` processed node: ${state.step}")
            is UploadState.Uploading -> {
                // Calculation of speed for UI/Bot
                val now = System.currentTimeMillis()
                if (lastUpdateMillis > 0) {
                    val duration = now - lastUpdateMillis
                    if (duration >= 1000) { // Update speed every second
                        val bytesSent = state.bytesWritten - lastBytesWritten
                        val speedKbps = (bytesSent * 1000) / (duration * 1024)
                        val speedFormatted = if (speedKbps > 1024) {
                            "%.2f MB/s".format(Locale.US, speedKbps / 1024f)
                        } else {
                            "$speedKbps KB/s"
                        }
                        
                        // We emit a new state with the speed updated if it's a significant change or periodically
                        _currentState.value = state.copy(speed = speedFormatted)
                        lastUpdateMillis = now
                        lastBytesWritten = state.bytesWritten
                    }
                } else {
                    lastUpdateMillis = now
                    lastBytesWritten = state.bytesWritten
                }
            }
            is UploadState.Success -> {
                addLog("SUCCESS: Sincronización completa de `${state.fileName}`")
                lastUpdateMillis = 0L
                lastBytesWritten = 0L
            }
            is UploadState.Error -> {
                addLog("FAILURE: Node error in `${state.fileName}`: ${state.errorMessage}")
                lastUpdateMillis = 0L
                lastBytesWritten = 0L
            }
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
