package com.inspiredandroid.kai.ui.chat.composables

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.inspiredandroid.kai.permissionQualifier
import com.inspiredandroid.kai.tools.AppPermission
import com.inspiredandroid.kai.tools.PermissionController
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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
                        Toast.makeText(context, "Microphone permission is required for Nia voice conversations.", Toast.LENGTH_SHORT).show()
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

    if (listeningEnabled) {
        DisposableEffect(listeningEnabled) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                val message = "Speech recognition is unavailable on this device."
                latestOnError.value(message)
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                latestOnActiveChange.value(false)
                onDispose { }
            } else {
                val recognizer = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                    ) {
                        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        SpeechRecognizer.createSpeechRecognizer(context)
                    }
                }.getOrElse {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                val handler = Handler(Looper.getMainLooper())
                var disposed = false
                var lastReportedError: Int? = null
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                val restart = Runnable {
                    if (!disposed) {
                        runCatching { recognizer.startListening(intent) }
                            .onFailure { latestOnError.value(it.message ?: "Could not restart speech recognition.") }
                    }
                }
                recognizer.setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit

                        override fun onError(error: Int) {
                            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                val message = "Microphone permission was lost."
                                latestOnError.value(message)
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                latestOnActiveChange.value(false)
                                return
                            }
                            if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                                lastReportedError != error
                            ) {
                                val message = "Speech recognition error ($error). Retrying."
                                latestOnError.value(message)
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                lastReportedError = error
                            }
                            handler.removeCallbacks(restart)
                            handler.postDelayed(restart, if (
                                error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ) 450L else 1200L)
                        }

                        override fun onResults(results: Bundle?) {
                            val transcript = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                            if (!transcript.isNullOrEmpty()) {
                                lastReportedError = null
                                latestOnRecognized.value(transcript)
                            } else {
                                handler.removeCallbacks(restart)
                                handler.postDelayed(restart, 350L)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    },
                )
                runCatching { recognizer.startListening(intent) }
                    .onFailure { latestOnError.value(it.message ?: "Could not start speech recognition.") }

                onDispose {
                    disposed = true
                    handler.removeCallbacks(restart)
                    runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                }
            }
        }
    }
}
