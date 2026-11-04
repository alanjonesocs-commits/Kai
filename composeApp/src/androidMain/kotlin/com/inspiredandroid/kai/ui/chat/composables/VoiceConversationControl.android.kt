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

            val recognitionFailureReported = AtomicBoolean(false)
            val engine = NiaAndroidAudioEngine(
                onPcmFrame = { samples, _ ->
                    runCatching { recognizer.acceptPcm16(samples) }
                        .onFailure { failure ->
                            // Audio callbacks run every 20 ms. Report a native/runtime
                            // failure once, then end the session instead of flooding UI.
                            if (recognitionFailureReported.compareAndSet(false, true)) {
                                scope.launch {
                                    latestOnError.value(
                                        failure.message ?: "Local speech recognition failed.",
                                    )
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
            latestOnError.value(message)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            latestOnActiveChange.value(false)
        } finally {
            // Stop the microphone thread before releasing the native recogniser it feeds.
            withContext(Dispatchers.IO) {
                runCatching { audioEngine?.close() }
                runCatching { streamingRecognizer?.close() }
            }
        }
    }
}
