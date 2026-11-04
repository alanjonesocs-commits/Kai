package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.runtime.Composable

/**
 * Platform voice-call control. Android owns microphone permission and speech recognition;
 * other platforms provide a no-op implementation until their voice stack is available.
 */
@Composable
expect fun VoiceConversationControl(
    active: Boolean,
    listeningEnabled: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onRecognized: (String) -> Unit,
    onError: (String) -> Unit,
)
