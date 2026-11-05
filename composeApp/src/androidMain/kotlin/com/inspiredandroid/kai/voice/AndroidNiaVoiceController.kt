package com.inspiredandroid.kai.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.marc_apps.tts.TextToSpeechInstance
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Android implementation: prefer installed offline Piper, otherwise retain system TTS. */
class AndroidNiaVoiceController(
    context: Context,
    private val systemTts: TextToSpeechInstance?,
) : NiaVoiceController {
    private val audioEngine = NiaAndroidAudioEngine(
        onPcmFrame = { _, _ -> },
        onVoiceActivity = { _, _ -> },
    )
    private val piper = NorthernPiperEngine(
        modelDirectory = File(context.filesDir, "nia/voices/northern_piper"),
        audioEngine = audioEngine,
    )
    private val operationMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)

    override suspend fun speak(text: String) {
        val phrase = text.trim()
        if (phrase.isEmpty() || closed.get()) return
        operationMutex.withLock {
            if (closed.get()) return@withLock
            if (piper.isInstalled) {
                try {
                    piper.speak(phrase)
                    return@withLock
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A damaged/unloadable local model must not silence the conversation.
                    piper.stop()
                }
            }
            systemTts?.say(phrase)
        }
    }

    override fun stop() {
        piper.stop()
        systemTts?.stop()
    }

    /** Native resources are released only after any manager-owned speak call exits. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        cleanupScope.launch {
            operationMutex.withLock {
                piper.close()
                audioEngine.close()
            }
            cleanupScope.cancel()
        }
    }
}
