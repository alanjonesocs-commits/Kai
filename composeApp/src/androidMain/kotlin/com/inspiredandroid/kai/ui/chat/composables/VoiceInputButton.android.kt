package com.inspiredandroid.kai.ui.chat.composables

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.inspiredandroid.kai.permissionQualifier
import com.inspiredandroid.kai.tools.AppPermission
import com.inspiredandroid.kai.tools.PermissionController
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.voice_input_content_description
import kai.composeapp.generated.resources.voice_input_failed
import kai.composeapp.generated.resources.voice_input_no_speech
import kai.composeapp.generated.resources.voice_input_permission_denied
import kai.composeapp.generated.resources.voice_input_stop_content_description
import kai.composeapp.generated.resources.voice_input_unavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@Composable
actual fun VoiceInputButton(onRecognized: (String) -> Unit) {
    val context = LocalContext.current
    val permissionController = koinInject<PermissionController>(permissionQualifier(AppPermission.RECORD_AUDIO))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latestOnRecognized by rememberUpdatedState(onRecognized)
    val permissionDenied = stringResource(Res.string.voice_input_permission_denied)
    val unavailable = stringResource(Res.string.voice_input_unavailable)
    val failed = stringResource(Res.string.voice_input_failed)
    val noSpeech = stringResource(Res.string.voice_input_no_speech)
    val startDescription = stringResource(Res.string.voice_input_content_description)
    val stopDescription = stringResource(Res.string.voice_input_stop_content_description)
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var isListening by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
        }
    }

    IconButton(
        onClick = {
            if (isListening) {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
                isListening = false
                return@IconButton
            }
            scope.launch {
                try {
                    if (!permissionController.requestPermission()) {
                        Toast.makeText(context, permissionDenied, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        Toast.makeText(context, unavailable, Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val activeRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    recognizer = activeRecognizer
                    activeRecognizer.setRecognitionListener(
                        object : RecognitionListener {
                            fun finish() {
                                isListening = false
                                if (recognizer === activeRecognizer) recognizer = null
                                activeRecognizer.destroy()
                            }

                            override fun onReadyForSpeech(params: Bundle?) {
                                isListening = true
                            }

                            override fun onBeginningOfSpeech() = Unit

                            override fun onRmsChanged(rmsdB: Float) = Unit

                            override fun onBufferReceived(buffer: ByteArray?) = Unit

                            override fun onEndOfSpeech() {
                                isListening = false
                            }

                            override fun onError(error: Int) {
                                finish()
                                val message = if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                ) {
                                    noSpeech
                                } else {
                                    failed
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }

                            override fun onResults(results: Bundle?) {
                                val text = results
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    ?.trim()
                                finish()
                                if (!text.isNullOrEmpty()) {
                                    latestOnRecognized(text)
                                } else {
                                    Toast.makeText(context, noSpeech, Toast.LENGTH_SHORT).show()
                                }
                            }

                            override fun onPartialResults(partialResults: Bundle?) = Unit

                            override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        },
                    )
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }
                    isListening = true
                    activeRecognizer.startListening(intent)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    recognizer?.destroy()
                    recognizer = null
                    isListening = false
                    Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                }
            }
        },
    ) {
        Icon(
            imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (isListening) stopDescription else startDescription,
        )
    }
}
