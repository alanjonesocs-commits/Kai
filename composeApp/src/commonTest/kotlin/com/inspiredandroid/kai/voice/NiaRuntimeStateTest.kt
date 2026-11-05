package com.inspiredandroid.kai.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NiaRuntimeStateTest {
    @Test
    fun activeVoiceSessionReturnsToListeningWhenConversationBecomesIdle() {
        val runtime = NiaRuntimeState()

        runtime.setVoiceSessionActive(true)
        assertEquals(NiaVoicePhase.Listening, runtime.state.value.voicePhase)

        runtime.setVoicePhase(NiaVoicePhase.Thinking)
        assertEquals(NiaVoicePhase.Thinking, runtime.state.value.voicePhase)

        runtime.setVoicePhase(NiaVoicePhase.Speaking)
        assertEquals(NiaVoicePhase.Speaking, runtime.state.value.voicePhase)

        runtime.setVoicePhase(NiaVoicePhase.Idle)
        assertEquals(NiaVoicePhase.Listening, runtime.state.value.voicePhase)
        assertTrue(runtime.state.value.voiceSessionActive)
    }

    @Test
    fun endingVoiceSessionClearsWaveformAndListeningState() {
        val runtime = NiaRuntimeState()

        runtime.setVoiceSessionActive(true)
        runtime.setWaveformAmplitude(0.8f)
        runtime.setVoiceSessionActive(false)

        assertEquals(NiaVoicePhase.Idle, runtime.state.value.voicePhase)
        assertEquals(0f, runtime.state.value.waveformAmplitude)
        assertEquals(false, runtime.state.value.voiceSessionActive)
    }

    @Test
    fun waveformAmplitudeIsClampedToPhysicalRange() {
        val runtime = NiaRuntimeState()
        runtime.setVoiceSessionActive(true)

        runtime.setWaveformAmplitude(-0.2f)
        assertEquals(0f, runtime.state.value.waveformAmplitude)

        runtime.setWaveformAmplitude(1.4f)
        assertEquals(1f, runtime.state.value.waveformAmplitude)
    }

    @Test
    fun microphoneListeningCallbacksDoNotOverrideThinkingOrSpeaking() {
        val runtime = NiaRuntimeState()
        runtime.setVoiceSessionActive(true)

        runtime.setVoicePhase(NiaVoicePhase.Thinking)
        runtime.setVoicePhase(NiaVoicePhase.Listening)
        assertEquals(NiaVoicePhase.Thinking, runtime.state.value.voicePhase)

        runtime.setVoicePhase(NiaVoicePhase.Speaking)
        runtime.setVoicePhase(NiaVoicePhase.Listening)
        assertEquals(NiaVoicePhase.Speaking, runtime.state.value.voicePhase)
    }

    @Test
    fun voiceBackendStorageAndInteractionTelemetryAreShared() {
        val runtime = NiaRuntimeState()

        runtime.updateVoiceOutputStatus(NiaVoiceOutputStatus.PiperReady)
        runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Ready)
        runtime.updateFreeStorageBytes(1_024L)
        runtime.signalInteraction()
        runtime.signalInteraction()

        assertEquals(NiaVoiceOutputStatus.PiperReady, runtime.state.value.voiceOutputStatus)
        assertEquals(NiaSpeechRecognitionStatus.Ready, runtime.state.value.speechRecognitionStatus)
        assertEquals(1_024L, runtime.state.value.freeStorageBytes)
        assertEquals(2L, runtime.state.value.lastInteraction)

        runtime.updateFreeStorageBytes(0L)
        assertEquals(null, runtime.state.value.freeStorageBytes)
    }

    @Test
    fun interactionEventsAreTypedOrderedAndBounded() {
        val runtime = NiaRuntimeState()

        runtime.signalInteraction(NiaInteractionKind.ConversationUpdated)
        runtime.signalInteraction(NiaInteractionKind.VoiceTranscript)
        repeat(30) {
            runtime.signalInteraction(NiaInteractionKind.SystemAction)
        }

        val snapshot = runtime.state.value
        assertEquals(32L, snapshot.lastInteraction)
        assertEquals(24, snapshot.interactionEvents.size)
        assertEquals(9L, snapshot.interactionEvents.first().sequence)
        assertEquals(32L, snapshot.interactionEvents.last().sequence)
        assertEquals(NiaInteractionKind.SystemAction, snapshot.interactionEvents.last().kind)
    }

    @Test
    fun providerAndModelCountsAreClampedToActualNonNegativeRanges() {
        val runtime = NiaRuntimeState()

        runtime.updateSystemStatus(configuredProviders = -1, connectedProviders = 5, localModels = -2)

        assertEquals(0, runtime.state.value.configuredProviderCount)
        assertEquals(0, runtime.state.value.connectedProviderCount)
        assertEquals(0, runtime.state.value.localModelCount)
    }

    @Test
    fun lateListeningCallbackCannotRestartAnEndedSession() {
        val runtime = NiaRuntimeState()
        runtime.setVoiceSessionActive(true)
        runtime.setVoiceSessionActive(false)
        runtime.setVoicePhase(NiaVoicePhase.Listening)

        assertEquals(NiaVoicePhase.Idle, runtime.state.value.voicePhase)
        assertEquals(false, runtime.state.value.voiceSessionActive)
    }

    @Test
    fun waveformIsClearedOutsideListeningAndLateFramesAreIgnored() {
        val runtime = NiaRuntimeState()
        runtime.setVoiceSessionActive(true)
        runtime.setWaveformAmplitude(0.6f)
        assertEquals(0.6f, runtime.state.value.waveformAmplitude)

        runtime.setVoicePhase(NiaVoicePhase.Thinking)
        assertEquals(0f, runtime.state.value.waveformAmplitude)
        runtime.setWaveformAmplitude(0.9f)
        assertEquals(0f, runtime.state.value.waveformAmplitude)

        runtime.setVoicePhase(NiaVoicePhase.Speaking)
        runtime.setWaveformAmplitude(0.8f)
        assertEquals(0f, runtime.state.value.waveformAmplitude)

        runtime.setVoicePhase(NiaVoicePhase.Idle)
        runtime.setWaveformAmplitude(0.4f)
        assertEquals(0.4f, runtime.state.value.waveformAmplitude)

        runtime.setVoiceSessionActive(false)
        runtime.setWaveformAmplitude(0.7f)
        assertEquals(0f, runtime.state.value.waveformAmplitude)
    }
}
