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
    fun lateListeningCallbackCannotRestartAnEndedSession() {
        val runtime = NiaRuntimeState()
        runtime.setVoiceSessionActive(true)
        runtime.setVoiceSessionActive(false)
        runtime.setVoicePhase(NiaVoicePhase.Listening)

        assertEquals(NiaVoicePhase.Idle, runtime.state.value.voicePhase)
        assertEquals(false, runtime.state.value.voiceSessionActive)
    }

}
