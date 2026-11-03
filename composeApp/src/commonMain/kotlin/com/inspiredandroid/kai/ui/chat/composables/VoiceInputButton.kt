package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.runtime.Composable

@Composable
expect fun VoiceInputButton(onRecognized: (String) -> Unit)
