package com.inspiredandroid.kai.voice

/** Shared chat-facing speech contract. Platform engines stay behind this interface. */
interface NiaVoiceController {
    /** Prepare optional local speech resources after an explicit user action. */
    suspend fun prepare() {}

    suspend fun speak(text: String)
    fun stop()
}
