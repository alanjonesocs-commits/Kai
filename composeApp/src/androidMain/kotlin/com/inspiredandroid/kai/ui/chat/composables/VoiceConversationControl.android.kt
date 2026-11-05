package com.inspiredandroid.kai.ui.chat.composables

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.inspiredandroid.kai.diagnostics.NiaDiagnostics
import com.inspiredandroid.kai.permissionQualifier
import com.inspiredandroid.kai.tools.AppPermission
import com.inspiredandroid.kai.tools.PermissionController
import com.inspiredandroid.kai.voice.NiaAndroidAudioEngine
import com.inspiredandroid.kai.voice.NiaRuntimeState
import com.inspiredandroid.kai.voice.NiaSherpaModelInstaller
import com.inspiredandroid.kai.voice.NiaSherpaStreamingRecognizer
import com.inspiredandroid.kai.voice.NiaSpeechRecognitionStatus
import com.inspiredandroid.kai.voice.NiaVoicePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

private const val PCM_QUEUE_CAPACITY = 25

@Composable
actual fun VoiceConversationControl(
    active: Boolean,
    listeningEnabled: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onRecognized: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val permissionController = koinInject<PermissionController>(permissionQualifier(AppPermission.RECORD_AUDIO))
    val runtime = koinInject<NiaRuntimeState>()
    val scope = rememberCoroutineScope()
    val latestOnRecognized = rememberUpdatedState(onRecognized)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnActiveChange = rememberUpdatedState(onActiveChange)

    LaunchedEffect(active) {
        runtime.setVoiceSessionActive(active)
    }

    IconButton(
        onClick = {
            if (active) {
                onActiveChange(false)
            } else {
                scope.launch {
                    if (permissionController.requestPermission()) {
                        onActiveChange(true)
                    } else {
                        Toast.makeText(
                            context,
                            "Microphone permission is required for Nia voice conversations.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        },
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.CallEnd else Icons.Filled.Call,
            contentDescription = if (active) "End Nia voice conversation" else "Start Nia voice conversation",
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    LaunchedEffect(listeningEnabled) {
        if (!listeningEnabled) return@LaunchedEffect
        runtime.setVoicePhase(NiaVoicePhase.Listening)
        runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Preparing)

        var audioEngine: NiaAndroidAudioEngine? = null
        var streamingRecognizer: NiaSherpaStreamingRecognizer? = null
        var recognizerWorker: Job? = null
        try {
            NiaDiagnostics.record("INFO", "voice", "session_start", "Starting local voice session.")
            // The first voice session downloads the pinned ~43 MB model into app-private
            // storage. Later sessions reuse the locally checked model files.
            Toast.makeText(
                context,
                "Preparing Nia's offline speech model if needed…",
                Toast.LENGTH_SHORT,
            ).show()
            val modelDirectory = NiaSherpaModelInstaller.ensureInstalled(context)
            val recognizer = withContext(Dispatchers.Default) {
                NiaSherpaStreamingRecognizer(
                    modelDirectory = modelDirectory,
                    onPartialTranscript = { /* Retained for future live captions. */ },
                    onFinalTranscript = { transcript ->
                        scope.launch {
                            runtime.signalInteraction()
                            latestOnRecognized.value(transcript)
                        }
                    },
                )
            }
            streamingRecognizer = recognizer
            runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Ready)

            // Keep native inference off the real-time microphone thread. Sherpa calls
            // must remain serial, so one worker drains this bounded FIFO in order.
            // At 20 ms per frame, 25 frames cap queued audio at 500 ms.
            val pcmFrames = Channel<ShortArray>(capacity = PCM_QUEUE_CAPACITY)
            val recognitionFailureReported = AtomicBoolean(false)
            val queuedFrames = AtomicInteger(0)
            val lastSlowFrameDiagnosticNanos = AtomicLong(0L)
            recognizerWorker = launch(Dispatchers.Default) {
                try {
                    for (samples in pcmFrames) {
                        queuedFrames.decrementAndGet()
                        val inferenceStarted = System.nanoTime()
                        recognizer.acceptPcm16(samples)
                        val inferenceDuration = System.nanoTime() - inferenceStarted
                        val now = System.nanoTime()
                        val lastDiagnostic = lastSlowFrameDiagnosticNanos.get()
                        if ((inferenceDuration > 20_000_000L || queuedFrames.get() >= PCM_QUEUE_CAPACITY / 2) &&
                            now - lastDiagnostic >= 5_000_000_000L &&
                            lastSlowFrameDiagnosticNanos.compareAndSet(lastDiagnostic, now)
                        ) {
                            NiaDiagnostics.record(
                                "WARN",
                                "voice.recognizer",
                                "slow_frame",
                                "Zipformer processing is approaching microphone frame cadence; inference=" +
                                    (inferenceDuration / 1_000_000).toString() + "ms, queued=" + queuedFrames.get(),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Error, failure.message)
                    if (recognitionFailureReported.compareAndSet(false, true)) {
                        NiaDiagnostics.record(
                            "ERROR",
                            "voice.recognizer",
                            "inference",
                            failure.message ?: "Local speech recognition failed.",
                            failure::class.qualifiedName,
                            stackTrace = failure.stackTraceToString(),
                        )
                        scope.launch {
                            latestOnError.value(
                                failure.message ?: "Local speech recognition failed.",
                            )
                            latestOnActiveChange.value(false)
                        }
                    }
                }
            }
            val engine = NiaAndroidAudioEngine(
                onPcmFrame = { samples, _ ->
                    runtime.setWaveformAmplitude(waveformLevel(samples))
                    queuedFrames.incrementAndGet()
                    // Never wait for inference here. A full queue ends the session
                    // visibly rather than silently dropping speech frames.
                    if (pcmFrames.trySend(samples).isFailure) {
                        queuedFrames.decrementAndGet()
                        if (recognitionFailureReported.compareAndSet(false, true)) {
                            val message = "Speech recognition could not keep up with microphone audio. Please try again."
                            runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Error, message)
                            NiaDiagnostics.record(
                                "ERROR",
                                "voice.audio",
                                "recognition_queue_overflow",
                                "Speech recognizer could not keep up; session stopped to avoid silent audio loss.",
                            )
                            pcmFrames.close(IllegalStateException(message))
                            // Surface the failure and stop capture. Closing the channel alone
                            // would leave AudioRecord running and repeatedly reject new frames.
                            scope.launch {
                                latestOnError.value(message)
                                latestOnActiveChange.value(false)
                            }
                        }
                    }
                },
                onVoiceActivity = { _, _ ->
                    runtime.setVoicePhase(NiaVoicePhase.Listening)
                },
            )
            audioEngine = engine
            engine.startCapture()
            runtime.setVoicePhase(NiaVoicePhase.Listening)
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            if (runtime.state.value.speechRecognitionStatus == NiaSpeechRecognitionStatus.Preparing) {
                runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Unknown)
            }
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message ?: "Could not start local speech recognition."
            NiaDiagnostics.record(
                "ERROR",
                "voice.session",
                "start_or_runtime",
                message,
                failure::class.qualifiedName,
                stackTrace = failure.stackTraceToString(),
            )
            runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Error, message)
            latestOnError.value(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            latestOnActiveChange.value(false)
        } finally {
            NiaDiagnostics.record("INFO", "voice", "session_end", "Voice session stopped.")
            runtime.setWaveformAmplitude(0f)
            // Stop the microphone thread before releasing the native recogniser it feeds.
            withContext(Dispatchers.IO) {
                runCatching { audioEngine?.close() }
            }
            // Stop and join the serial inference worker before releasing its native recogniser.
            runCatching { recognizerWorker?.cancelAndJoin() }
            withContext(Dispatchers.IO) {
                runCatching { streamingRecognizer?.close() }
            }
        }
    }
}

private fun waveformLevel(samples: ShortArray): Float {
    if (samples.isEmpty()) return 0f
    var squaredTotal = 0.0
    for (sample in samples) {
        val normalized = sample.toDouble() / 32768.0
        squaredTotal += normalized * normalized
    }
    return sqrt(squaredTotal / samples.size).toFloat().coerceIn(0f, 1f)
}
