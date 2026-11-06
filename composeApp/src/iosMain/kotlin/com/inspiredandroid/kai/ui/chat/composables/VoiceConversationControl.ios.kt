package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.runtime.Composable

@Composable
actual fun VoiceConversationControl(
    active: Boolean,
    listeningEnabled: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onRecognized: (String) -> Unit,
    onError: (String) -> Unit,
) = Unit
