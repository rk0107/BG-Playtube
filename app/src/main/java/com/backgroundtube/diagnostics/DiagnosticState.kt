package com.backgroundtube.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DiagnosticState(
    val foregroundServiceState: String = "Stopped",
    val wakeLockState: String = "Released",
    val mediaSessionState: String = "Inactive",
    val playbackState: String = "Unknown",
    val networkStatus: String = "Unknown"
)

object DiagnosticsStore {
    private val mutableState = MutableStateFlow(DiagnosticState())
    val state: StateFlow<DiagnosticState> = mutableState

    fun updateForegroundService(state: String) {
        mutableState.update { it.copy(foregroundServiceState = state) }
    }

    fun updateWakeLock(state: String) {
        mutableState.update { it.copy(wakeLockState = state) }
    }

    fun updateMediaSession(state: String) {
        mutableState.update { it.copy(mediaSessionState = state) }
    }

    fun updatePlayback(state: String) {
        mutableState.update { it.copy(playbackState = state) }
    }

    fun updateNetwork(state: String) {
        mutableState.update { it.copy(networkStatus = state) }
    }
}
