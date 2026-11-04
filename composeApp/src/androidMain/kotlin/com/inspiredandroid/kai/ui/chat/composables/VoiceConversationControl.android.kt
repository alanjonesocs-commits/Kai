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
import com.inspiredandroid.kai.voice.NiaSherpaModelInstaller
import com.inspiredandroid.kai.voice.NiaSherpaStreamingRecognizer
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

private const val PCM_QUEUE_CAPACITY = 25
private const val SLOW_INFERENCE_NANOS = 250_000_000L

private data class QueuedPcmFrame(
    val samples: ShortArray,
    val capturedAtNanos: Long,
    val rms: Double,
    val peak: Int,
)

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
    val scope = rememberCoroutineScope()
    val latestOnRecognized = rememberUpdatedState(onRecognized)
    val latestOnError = rememberUpdatedState(onError)
    val latestOnActiveChange = rememberUpdatedState(onActiveChange)

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
                    onPartialTranscript = { transcript ->
                        NiaDiagnostics.record(
                            "INFO", "voice.asr", "partial_transcript",
                            "text=$transcript",
                        )
                    },
                    onFinalTranscript = { transcript ->
                        NiaDiagnostics.record(
                            "INFO", "voice.asr", "final_transcript",
                            "text=$transcript",
                        )
                        scope.launch {
                            latestOnRecognized.value(transcript)
                        }
                    },
                )
            }
            streamingRecognizer = recognizer

            // Keep native inference off the real-time microphone thread. Sherpa calls
            // must remain serial, so one worker drains this bounded FIFO in order.
            // At 20 ms per frame, 25 frames cap queued audio at 500 ms.
            val pcmFrames = Channel<QueuedPcmFrame>(capacity = PCM_QUEUE_CAPACITY)
            val recognitionFailureReported = AtomicBoolean(false)
            val queuedFrames = AtomicInteger(0)
            val capturedFrames = AtomicLong(0L)
            val processedFrames = AtomicLong(0L)
            val lastInferenceNanos = AtomicLong(0L)
            val maxInferenceNanos = AtomicLong(0L)
            val maxQueueDepth = AtomicInteger(0)
            val maxQueueWaitNanos = AtomicLong(0L)
            val minFrameRms = java.util.concurrent.atomic.AtomicReference(Double.POSITIVE_INFINITY)
            val maxFrameRms = java.util.concurrent.atomic.AtomicReference(0.0)
            val maxFramePeak = AtomicInteger(0)
            recognizerWorker = launch(Dispatchers.Default) {
                try {
                    for (frame in pcmFrames) {
                        queuedFrames.decrementAndGet()
                        val startedAt = System.nanoTime()
                        val queueWaitNanos = (startedAt - frame.capturedAtNanos).coerceAtLeast(0L)
                        maxQueueWaitNanos.updateAndGet { previous -> maxOf(previous, queueWaitNanos) }
                        recognizer.acceptPcm16(frame.samples)
                        val inferenceNanos = (System.nanoTime() - startedAt).coerceAtLeast(0L)
                        lastInferenceNanos.set(inferenceNanos)
                        maxInferenceNanos.updateAndGet { previous -> maxOf(previous, inferenceNanos) }
                        if (inferenceNanos >= SLOW_INFERENCE_NANOS) {
                            NiaDiagnostics.record(
                                "WARN", "voice.audio", "recognition_slow_inference",
                                "inference_ms=${inferenceNanos / 1_000_000} queue_wait_ms=${queueWaitNanos / 1_000_000} queued=${queuedFrames.get()}",
                            )
                        }
                        minFrameRms.updateAndGet { previous -> minOf(previous, frame.rms) }
                        maxFrameRms.updateAndGet { previous -> maxOf(previous, frame.rms) }
                        maxFramePeak.updateAndGet { previous -> maxOf(previous, frame.peak) }
                        val processed = processedFrames.incrementAndGet()
                        if (processed % 50L == 0L) {
                            NiaDiagnostics.record(
                                "INFO", "voice.audio", "recognition_throughput",
                                "processed=$processed captured=${capturedFrames.get()} queued=${queuedFrames.get()} last_inference_ms=${lastInferenceNanos.get() / 1_000_000} max_inference_ms=${maxInferenceNanos.get() / 1_000_000} max_queue_wait_ms=${maxQueueWaitNanos.get() / 1_000_000} max_queue_depth=${maxQueueDepth.get()} frame_rms_min=${minFrameRms.get()} frame_rms_max=${maxFrameRms.get()} frame_peak_max=${maxFramePeak.get()}",
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (recognitionFailureReported.compareAndSet(false, true)) {
                        pcmFrames.close(failure)
                        NiaDiagnostics.record(
                            "ERROR", "voice.recognizer", "inference",
                            failure.message ?: "Local speech recognition failed.",
                            failure::class.qualifiedName,
                            stackTrace = failure.stackTraceToString(),
                        )
                        scope.launch {
                            withContext(Dispatchers.IO) { audioEngine?.stopCapture() }
                            latestOnError.value(failure.message ?: "Local speech recognition failed.")
                            latestOnActiveChange.value(false)
                        }
                    }
                }
            }
            val engine = NiaAndroidAudioEngine(
                onPcmFrame = { samples, capturedAtNanos, rms, peak ->
                    // Never wait for inference here. A full queue ends the session
                    // visibly rather than silently dropping speech frames.
                    capturedFrames.incrementAndGet()
                    val queueDepth = queuedFrames.incrementAndGet()
                    maxQueueDepth.updateAndGet { previous -> maxOf(previous, queueDepth) }
                    val sendResult = pcmFrames.trySend(QueuedPcmFrame(samples, capturedAtNanos, rms, peak))
                    if (sendResult.isFailure) {
                        queuedFrames.decrementAndGet()
                        if (recognitionFailureReported.compareAndSet(false, true)) {
                            val failureCode = if (sendResult.isClosed) "recognition_channel_closed" else "recognition_queue_overflow"
                            val failureMessage = if (sendResult.isClosed) {
                                "Audio frame rejected because the recognizer channel was already closed; this is not evidence of a full queue."
                            } else {
                                "Speech recognizer queue reached capacity; session stopped to avoid silent audio loss."
                            }
                            NiaDiagnostics.record(
                                "ERROR",
                                "voice.audio",
                                failureCode,
                                "$failureMessage queued=${queuedFrames.get()} captured=${capturedFrames.get()} processed=${processedFrames.get()}",
                            )
                            pcmFrames.close(
                                IllegalStateException(
                                    if (sendResult.isClosed) "Speech recognition stopped unexpectedly because its processing channel closed." else "Speech recognition could not keep up with microphone audio. Please try again.",
                                ),
                            )
                            scope.launch {
                                withContext(Dispatchers.IO) { audioEngine?.stopCapture() }
                                latestOnError.value(if (sendResult.isClosed) "Speech recognition stopped unexpectedly because its processing channel closed." else "Speech recognition could not keep up with microphone audio. Please try again.")
                                latestOnActiveChange.value(false)
                            }
                        }
                    }
                },
                onVoiceActivity = { _, _ -> },
            )
            audioEngine = engine
            engine.startCapture()
            awaitCancellation()
        } catch (cancelled: CancellationException) {
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
            latestOnError.value(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            latestOnActiveChange.value(false)
        } finally {
            NiaDiagnostics.record("INFO", "voice", "session_end", "Voice session stopped.")
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
