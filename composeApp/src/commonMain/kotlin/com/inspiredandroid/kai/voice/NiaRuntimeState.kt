package com.inspiredandroid.kai.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One process-wide signal bus shared by NIA's Home, Conversation, Memory and Systems surfaces. */
enum class NiaVoicePhase {
    Idle,
    Listening,
    Thinking,
    Speaking,
}

data class NiaRuntimeSnapshot(
    val voicePhase: NiaVoicePhase = NiaVoicePhase.Idle,
    val waveformAmplitude: Float = 0f,
    val memoryEntryCount: Int? = null,
    val configuredProviderCount: Int? = null,
    val connectedProviderCount: Int? = null,
    val localModelCount: Int? = null,
    val lastInteraction: Long = 0L,
)

class NiaRuntimeState {
    private val mutableState = MutableStateFlow(NiaRuntimeSnapshot())
    val state: StateFlow<NiaRuntimeSnapshot> = mutableState.asStateFlow()

    fun setVoicePhase(phase: NiaVoicePhase) {
        mutableState.value = mutableState.value.copy(
            voicePhase = phase,
            waveformAmplitude = if (phase == NiaVoicePhase.Idle) 0f else mutableState.value.waveformAmplitude,
        )
    }

    fun setWaveformAmplitude(amplitude: Float) {
        mutableState.value = mutableState.value.copy(waveformAmplitude = amplitude.coerceIn(0f, 1f))
    }

    fun updateMemoryStatus(entryCount: Int) {
        mutableState.value = mutableState.value.copy(memoryEntryCount = entryCount.coerceAtLeast(0))
    }

    fun updateSystemStatus(
        configuredProviders: Int,
        connectedProviders: Int,
        localModels: Int,
    ) {
        mutableState.value = mutableState.value.copy(
            configuredProviderCount = configuredProviders.coerceAtLeast(0),
            connectedProviderCount = connectedProviders.coerceIn(0, configuredProviders.coerceAtLeast(0)),
            localModelCount = localModels.coerceAtLeast(0),
        )
    }

    /** Shared interaction pulse; surfaces can observe this sequence to animate in sync. */
    fun signalInteraction() {
        mutableState.value = mutableState.value.copy(lastInteraction = mutableState.value.lastInteraction + 1L)
    }
}
