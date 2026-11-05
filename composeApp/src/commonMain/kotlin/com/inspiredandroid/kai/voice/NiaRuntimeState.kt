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

enum class NiaVoiceOutputStatus {
    Unknown,
    NotInstalled,
    Preparing,
    PiperInstalled,
    PiperReady,
    SystemTtsFallback,
    Unavailable,
    Error,
}

enum class NiaSpeechRecognitionStatus {
    Unknown,
    Preparing,
    Ready,
    Error,
}

enum class NiaInteractionKind {
    UserInteraction,
    ConversationUpdated,
    VoiceTranscript,
    VoiceSessionStarted,
    VoiceSessionEnded,
    MemorySettingsChanged,
    MemoryApproved,
    MemoryRejected,
    MemoryUpdated,
    MemoryDeleted,
    MemoryPrivacyUpdated,
    SettingsExported,
    SettingsImported,
    SystemAction,
}

data class NiaInteractionEvent(
    val sequence: Long,
    val kind: NiaInteractionKind,
)

data class NiaRuntimeSnapshot(
    val voicePhase: NiaVoicePhase = NiaVoicePhase.Idle,
    val voiceSessionActive: Boolean = false,
    val waveformAmplitude: Float = 0f,
    val voiceOutputStatus: NiaVoiceOutputStatus = NiaVoiceOutputStatus.Unknown,
    val voiceOutputDetail: String? = null,
    val speechRecognitionStatus: NiaSpeechRecognitionStatus = NiaSpeechRecognitionStatus.Unknown,
    val speechRecognitionDetail: String? = null,
    val freeStorageBytes: Long? = null,
    val memoryEntryCount: Int? = null,
    val configuredProviderCount: Int? = null,
    val connectedProviderCount: Int? = null,
    val localModelCount: Int? = null,
    val lastInteraction: Long = 0L,
    val interactionEvents: List<NiaInteractionEvent> = emptyList(),
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
            val effectivePhase = when {
                active && current.voicePhase == NiaVoicePhase.Idle -> NiaVoicePhase.Listening
                !active && current.voicePhase == NiaVoicePhase.Listening -> NiaVoicePhase.Idle
                else -> current.voicePhase
            }
            current.copy(
                voiceSessionActive = active,
                voicePhase = effectivePhase,
                waveformAmplitude = if (active && effectivePhase == NiaVoicePhase.Listening) current.waveformAmplitude else 0f,
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
                waveformAmplitude = if (effectivePhase == NiaVoicePhase.Listening) current.waveformAmplitude else 0f,
            )
        }
    }

    fun setWaveformAmplitude(amplitude: Float) {
        mutableState.update { current ->
            if (current.voiceSessionActive && current.voicePhase == NiaVoicePhase.Listening) {
                current.copy(waveformAmplitude = amplitude.coerceIn(0f, 1f))
            } else {
                current.copy(waveformAmplitude = 0f)
            }
        }
    }

    fun updateVoiceOutputStatus(status: NiaVoiceOutputStatus, detail: String? = null) {
        mutableState.update { it.copy(voiceOutputStatus = status, voiceOutputDetail = detail) }
    }

    fun updateSpeechRecognitionStatus(status: NiaSpeechRecognitionStatus, detail: String? = null) {
        mutableState.update { it.copy(speechRecognitionStatus = status, speechRecognitionDetail = detail) }
    }

    fun updateFreeStorageBytes(bytes: Long?) {
        mutableState.update { it.copy(freeStorageBytes = bytes?.takeIf { value -> value > 0L }) }
    }

    fun updateMemoryStatus(entryCount: Int) {
        mutableState.update { it.copy(memoryEntryCount = entryCount.coerceAtLeast(0)) }
    }

    fun updateSystemStatus(
        configuredProviders: Int,
        connectedProviders: Int,
        localModels: Int,
    ) {
        mutableState.update {
            it.copy(
                configuredProviderCount = configuredProviders.coerceAtLeast(0),
                connectedProviderCount = connectedProviders.coerceIn(0, configuredProviders.coerceAtLeast(0)),
                localModelCount = localModels.coerceAtLeast(0),
            )
        }
    }

    /**
     * Records a privacy-safe event sequence for shared UI feedback and diagnostics.
     * Event payloads deliberately contain no message text or memory contents.
     */
    fun signalInteraction(kind: NiaInteractionKind = NiaInteractionKind.UserInteraction) {
        mutableState.update { current ->
            val sequence = current.lastInteraction + 1L
            current.copy(
                lastInteraction = sequence,
                interactionEvents = (current.interactionEvents + NiaInteractionEvent(sequence, kind))
                    .takeLast(MAX_INTERACTION_EVENTS),
            )
        }
    }

    private companion object {
        const val MAX_INTERACTION_EVENTS = 24
    }
}
