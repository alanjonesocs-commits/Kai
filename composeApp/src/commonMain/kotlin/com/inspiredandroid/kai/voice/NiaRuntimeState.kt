package com.inspiredandroid.kai.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One process-wide signal bus shared by NIA's Home, Conversation, Memory and Systems surfaces. */
enum class NiaVoicePhase {
    Idle,
    Listening,
    Thinking,
    Speaking,
}

data class NiaRuntimeSnapshot(
    val voicePhase: NiaVoicePhase = NiaVoicePhase.Idle,
    val voiceSessionActive: Boolean = false,
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

    /**
     * The user-owned voice session outlives the individual microphone capture window.
     * During model generation and speech playback capture is paused, but the session
     * remains active and returns to Listening when the conversation becomes idle.
     */
    fun setVoiceSessionActive(active: Boolean) {
        mutableState.update { current ->
            current.copy(
                voiceSessionActive = active,
                voicePhase = when {
                    active && current.voicePhase == NiaVoicePhase.Idle -> NiaVoicePhase.Listening
                    !active && current.voicePhase == NiaVoicePhase.Listening -> NiaVoicePhase.Idle
                    else -> current.voicePhase
                },
                waveformAmplitude = if (active) current.waveformAmplitude else 0f,
            )
        }
    }

    /** Conversation phases may override Listening while the active voice session is processing. */
    fun setVoicePhase(phase: NiaVoicePhase) {
        mutableState.update { current ->
            val effectivePhase = when {
                phase == NiaVoicePhase.Idle && current.voiceSessionActive -> NiaVoicePhase.Listening
                phase == NiaVoicePhase.Listening && !current.voiceSessionActive -> NiaVoicePhase.Idle
                phase == NiaVoicePhase.Listening &&
                    current.voicePhase in setOf(NiaVoicePhase.Thinking, NiaVoicePhase.Speaking) -> current.voicePhase
                else -> phase
            }
            current.copy(
                voicePhase = effectivePhase,
                waveformAmplitude = if (effectivePhase == NiaVoicePhase.Idle) 0f else current.waveformAmplitude,
            )
        }
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
