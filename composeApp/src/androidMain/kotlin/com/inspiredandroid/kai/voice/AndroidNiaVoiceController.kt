package com.inspiredandroid.kai.voice

import android.content.Context
import com.inspiredandroid.kai.diagnostics.NiaDiagnostics
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
    private val runtime: NiaRuntimeState? = null,
) : NiaVoiceController {
    private val appContext = context.applicationContext
    private val audioEngine = NiaAndroidAudioEngine(
        onPcmFrame = { _, _ -> },
        onVoiceActivity = { _, _ -> },
    )
    private val piper = NorthernPiperEngine(
        modelDirectory = File(appContext.filesDir, "nia/voices/northern_piper"),
        audioEngine = audioEngine,
    )
    private val downloader = NorthernVoiceDownloader(appContext)
    private val operationMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)

    init {
        runtime?.updateVoiceOutputStatus(
            when {
                piper.isInstalled -> NiaVoiceOutputStatus.PiperInstalled
                systemTts != null -> NiaVoiceOutputStatus.NotInstalled
                else -> NiaVoiceOutputStatus.Unavailable
            },
            if (piper.isInstalled) null else "Northern Piper is not installed; system TTS remains available.",
        )
    }

    /**
     * Downloads and loads the optional offline voice only after an explicit user action.
     * A failed install leaves the existing system-TTS fallback intact.
     */
    override suspend fun prepare() {
        if (closed.get()) return
        operationMutex.withLock {
            if (closed.get()) return@withLock
            if (!piper.isInstalled) {
                runtime?.updateVoiceOutputStatus(NiaVoiceOutputStatus.Preparing)
                try {
                    downloader.download()
                } catch (cancelled: CancellationException) {
                    runtime?.updateVoiceOutputStatus(
                        if (systemTts != null) NiaVoiceOutputStatus.NotInstalled else NiaVoiceOutputStatus.Unavailable,
                        "Northern Piper installation was cancelled.",
                    )
                    throw cancelled
                }
            }

            if (!piper.isInstalled) {
                val detail = (downloader.state.value as? NorthernVoiceDownloader.State.Error)?.message
                    ?: "Northern Piper files are not available."
                updateFallbackStatus(detail)
                return@withLock
            }

            try {
                piper.prepare()
                runtime?.updateVoiceOutputStatus(NiaVoiceOutputStatus.PiperReady)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                piper.stop()
                updateFallbackStatus(error.message, error)
            }
        }
    }

    override suspend fun speak(text: String) {
        val phrase = text.trim()
        if (phrase.isEmpty() || closed.get()) return
        operationMutex.withLock {
            if (closed.get()) return@withLock
            if (piper.isInstalled) {
                try {
                    piper.speak(phrase)
                    runtime?.updateVoiceOutputStatus(NiaVoiceOutputStatus.PiperReady)
                    return@withLock
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    piper.stop()
                    updateFallbackStatus(error.message, error)
                }
            } else {
                updateFallbackStatus()
            }

            val fallback = systemTts ?: return@withLock
            try {
                fallback.say(phrase)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runtime?.updateVoiceOutputStatus(NiaVoiceOutputStatus.Error, error.message)
                throw error
            }
        }
    }

    override fun stop() {
        downloader.cancel()
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

    private fun updateFallbackStatus(detail: String? = null, error: Exception? = null) {
        val status = when {
            systemTts != null -> NiaVoiceOutputStatus.SystemTtsFallback
            error != null -> NiaVoiceOutputStatus.Error
            else -> NiaVoiceOutputStatus.Unavailable
        }
        runtime?.updateVoiceOutputStatus(status, detail)
        if (detail != null || error != null) {
            NiaDiagnostics.record(
                "WARN",
                "voice.tts",
                "piper_fallback",
                detail ?: error?.message ?: "Piper voice is unavailable.",
                error?.javaClass?.name,
                error?.stackTraceToString(),
            )
        }
    }
}
