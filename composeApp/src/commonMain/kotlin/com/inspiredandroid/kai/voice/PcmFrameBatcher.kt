package com.inspiredandroid.kai.voice

/**
 * Groups fixed-size microphone frames without dropping or reordering PCM samples.
 *
 * The final partial batch can be drained when capture stops so the recognizer receives
 * every captured sample that was not rejected by its bounded queue.
 */
class PcmFrameBatcher(
    private val frameSamples: Int,
    private val framesPerBatch: Int,
) {
    init {
        require(frameSamples > 0) { "Frame sample count must be positive." }
        require(framesPerBatch > 0) { "Frames per batch must be positive." }
    }

    private val buffer = ShortArray(frameSamples * framesPerBatch)
    private var pendingSamples = 0

    val pendingSampleCount: Int
        get() = pendingSamples

    fun append(frame: ShortArray): ShortArray? {
        require(frame.size == frameSamples) {
            "Expected $frameSamples PCM samples, got ${frame.size}."
        }

        frame.copyInto(buffer, destinationOffset = pendingSamples)
        pendingSamples += frame.size
        if (pendingSamples < buffer.size) return null

        return buffer.copyOf().also {
            pendingSamples = 0
        }
    }

    fun drain(): ShortArray? {
        if (pendingSamples == 0) return null
        return buffer.copyOf(pendingSamples).also {
            pendingSamples = 0
        }
    }
}
