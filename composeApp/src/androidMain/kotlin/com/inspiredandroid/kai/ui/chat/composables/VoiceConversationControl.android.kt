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
import com.inspiredandroid.kai.permissionQualifier
import com.inspiredandroid.kai.tools.AppPermission
import com.inspiredandroid.kai.tools.PermissionController
import com.inspiredandroid.kai.voice.NiaAndroidAudioEngine
import com.inspiredandroid.kai.voice.NiaSherpaModelInstaller
import com.inspiredandroid.kai.voice.NiaSherpaStreamingRecognizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean

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
        try {
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
                            latestOnRecognized.value(transcript)
                        }
                    },
                )
            }
            streamingRecognizer = recognizer

            // Keep native inference off the real-time microphone thread. Sherpa calls
            // must remain serial, so one worker drains this bounded FIFO in order.
            // At 20 ms per frame, 25 frames cap queued audio at 500 ms.
            val pcmFrames = Channel<ShortArray>(capacity = PCM_QUEUE_CAPACITY)
            val recognitionFailureReported = AtomicBoolean(false)
            val recognizerWorker = launch(Dispatchers.Default) {
                try {
                    for (samples in pcmFrames) {
                        recognizer.acceptPcm16(samples)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (recognitionFailureReported.compareAndSet(false, true)) {
                        latestOnError.value(
                            failure.message ?: "Local speech recognition failed.",
                        )
                        latestOnActiveChange.value(false)
                    }
                }
            }
            val engine = NiaAndroidAudioEngine(
                onPcmFrame = { samples, _ ->
                    // Never wait for inference here. A full queue ends the session
                    // visibly rather than silently dropping speech frames.
                    if (pcmFrames.trySend(samples).isFailure &&
                        recognitionFailureReported.compareAndSet(false, true)
                    ) {
                        pcmFrames.close(
                            IllegalStateException(
                                "Speech recognition could not keep up with microphone audio. Please try again.",
                            ),
                        )
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
            latestOnError.value(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            latestOnActiveChange.value(false)
        } finally {
            // Stop the microphone thread before releasing the native recogniser it feeds.
            withContext(Dispatchers.IO) {
                runCatching { audioEngine?.close() }
            }
            // Stop the worker before releasing the native recogniser it owns.
            // Cancellation also prevents a slow decode from delaying session teardown.
            // The worker is cancelled automatically with this LaunchedEffect.
            withContext(Dispatchers.IO) {
                runCatching { streamingRecognizer?.close() }
            }
        }
    }

    private companion object {
        const val PCM_QUEUE_CAPACITY = 25
    }
}
