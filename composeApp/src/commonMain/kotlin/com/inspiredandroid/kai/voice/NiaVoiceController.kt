package com.inspiredandroid.kai.voice

/** Shared chat-facing speech contract. Platform engines stay behind this interface. */
interface NiaVoiceController {
    suspend fun speak(text: String)
    fun stop()
}
