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
import com.inspiredandroid.kai.voice.NiaInteractionKind
import com.inspiredandroid.kai.voice.NiaRuntimeState
import com.inspiredandroid.kai.voice.NiaSherpaModelInstaller
import com.inspiredandroid.kai.voice.NiaSherpaStreamingRecognizer
import com.inspiredandroid.kai.voice.NiaSpeechRecognitionStatus
import com.inspiredandroid.kai.voice.NiaVoicePhase
import com.inspiredandroid.kai.voice.PcmFrameBatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

private const val PCM_QUEUE_CAPACITY = 25
private const val PCM_FRAME_SAMPLES = 320
private const val PCM_FRAMES_PER_BATCH = 5
private const val PCM_BATCH_DURATION_NANOS = 100_000_000L

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
                runtime.signalInteraction(NiaInteractionKind.VoiceSessionEnded)
                onActiveChange(false)
            } else {
                scope.launch {
                    if (permissionController.requestPermission()) {
                        runtime.signalInteraction(NiaInteractionKind.VoiceSessionStarted)
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
            contentDescription = if (active) "Stop voice conversation" else "Start voice conversation",
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
        var workerScope: CoroutineScope? = null
        val pcmFrames = Channel<ShortArray>(capacity = PCM_QUEUE_CAPACITY)
        val batcher = PcmFrameBatcher(
            frameSamples = PCM_FRAME_SAMPLES,
            framesPerBatch = PCM_FRAMES_PER_BATCH,
        )
        val recognitionFailureReported = AtomicBoolean(false)
        val queuedBatches = AtomicInteger(0)
        val lastSlowBatchDiagnosticNanos = AtomicLong(0L)
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
                            runtime.signalInteraction(NiaInteractionKind.VoiceTranscript)
                            latestOnRecognized.value(transcript)
                        }
                    },
                )
            }
            streamingRecognizer = recognizer
            runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Ready)

            // Native Zipformer inference stays serial and off the AudioRecord callback.
            // Five 20 ms capture frames form one 100 ms recognizer batch. Twenty-five
            // queued batches retain a 2.5 s bounded backlog without per-frame dispatch.
            val inferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            workerScope = inferenceScope
            recognizerWorker = inferenceScope.launch {
                try {
                    for (samples in pcmFrames) {
                        queuedBatches.decrementAndGet()
                        val inferenceStarted = System.nanoTime()
                        recognizer.acceptPcm16(samples)
                        val inferenceDuration = System.nanoTime() - inferenceStarted
                        val now = System.nanoTime()
                        val lastDiagnostic = lastSlowBatchDiagnosticNanos.get()
                        if ((
                                inferenceDuration > PCM_BATCH_DURATION_NANOS ||
                                    queuedBatches.get() >= PCM_QUEUE_CAPACITY / 2
                                ) &&
                            now - lastDiagnostic >= 5_000_000_000L &&
                            lastSlowBatchDiagnosticNanos.compareAndSet(lastDiagnostic, now)
                        ) {
                            NiaDiagnostics.record(
                                "WARN",
                                "voice.recognizer",
                                "slow_batch",
                                "Zipformer processing is approaching the 100 ms batch cadence; inference=" +
                                    (inferenceDuration / 1_000_000).toString() + "ms, queued_batches=" +
                                    queuedBatches.get(),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Error, failure.message)
                    pcmFrames.close(failure)
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
                onCaptureError = { message ->
                    runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Error, message)
                    if (recognitionFailureReported.compareAndSet(false, true)) {
                        NiaDiagnostics.record(
                            "ERROR",
                            "voice.audio",
                            "capture",
                            message,
                        )
                        scope.launch {
                            latestOnError.value(message)
                            latestOnActiveChange.value(false)
                        }
                        pcmFrames.close(IllegalStateException(message))
                    }
                },
                onPcmFrame = { samples, _ ->
                    runtime.setWaveformAmplitude(waveformLevel(samples))
                    val batch = batcher.append(samples)
                    if (batch != null) {
                        queuedBatches.incrementAndGet()
                        // Never wait for native inference on the real-time microphone thread.
                        // A full bounded queue still fails closed and records the rejected batch.
                        if (pcmFrames.trySend(batch).isFailure) {
                            queuedBatches.decrementAndGet()
                            if (recognitionFailureReported.compareAndSet(false, true)) {
                                val message = "Speech recognition could not keep up with microphone audio. Please try again."
                                runtime.updateSpeechRecognitionStatus(NiaSpeechRecognitionStatus.Error, message)
                                NiaDiagnostics.record(
                                    "ERROR",
                                    "voice.audio",
                                    "recognition_queue_overflow",
                                    "Zipformer queue full at 100 ms batch cadence; capacity=" +
                                        (PCM_QUEUE_CAPACITY * PCM_BATCH_DURATION_NANOS / 1_000_000L) + " ms, " +
                                        "queued_batches=${queuedBatches.get()}, rejected_samples=${batch.size}. " +
                                        "Session stopped to avoid silent audio loss.",
                                )
                                pcmFrames.close(IllegalStateException(message))
                                scope.launch {
                                    latestOnError.value(message)
                                    latestOnActiveChange.value(false)
                                }
                            }
                        }
                    }
                },
                onVoiceActivity = { voiced, _ ->
                    if (voiced) runtime.setVoicePhase(NiaVoicePhase.Listening)
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
            withContext(NonCancellable) {
                runtime.setWaveformAmplitude(0f)
                // Stop and join AudioRecord before draining the final partial batch.
                withContext(Dispatchers.IO) {
                    runCatching { audioEngine?.close() }
                }
                val trailingBatch = batcher.drain()
                if (trailingBatch != null && !recognitionFailureReported.get()) {
                    queuedBatches.incrementAndGet()
                    if (pcmFrames.trySend(trailingBatch).isFailure) {
                        queuedBatches.decrementAndGet()
                        NiaDiagnostics.record(
                            "WARN",
                            "voice.recognizer",
                            "recognition_tail_flush_failed",
                            "Final partial PCM batch could not be queued; samples=${trailingBatch.size}.",
                        )
                    }
                }
                // Closing after the tail flush lets the serial worker drain all accepted
                // audio in order before its native recognizer is released.
                pcmFrames.close()
                runCatching { recognizerWorker?.join() }
                workerScope?.cancel()
                withContext(Dispatchers.IO) {
                    runCatching { streamingRecognizer?.close() }
                }
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
