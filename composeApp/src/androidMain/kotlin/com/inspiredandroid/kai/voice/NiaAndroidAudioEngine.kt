package com.inspiredandroid.kai.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.os.Process
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Foreground-session PCM audio foundation for Nia.
 *
 * This class deliberately does not start itself, request permissions, run in the
 * background, or contact a network service. The caller must obtain RECORD_AUDIO
 * permission and own its lifecycle while the conversation UI is visible.
 *
 * Input is 16 kHz mono PCM16. Playback is 24 kHz mono PCM16. Acoustic effects
 * are enabled only when the device exposes them; availability is not guaranteed.
 */
internal class NiaAndroidAudioEngine(
    private val onPcmFrame: (ShortArray, Long) -> Unit,
    private val onVoiceActivity: (Boolean, Long) -> Unit,
    private val sampleRate: Int = INPUT_SAMPLE_RATE,
    private val frameSamples: Int = FRAME_SAMPLES,
) : Closeable {

    private val running = AtomicBoolean(false)
    private val playbackGeneration = AtomicLong(0L)
    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var captureThread: Thread? = null
    private var voiceActive = false
    private var voicedFrames = 0
    private var silentFrames = 0

    fun startCapture() {
        synchronized(lock) {
            check(!running.get()) { "Audio capture is already running." }
            check(sampleRate == INPUT_SAMPLE_RATE && frameSamples > 0) {
                "Unsupported capture format."
            }
            check(AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ) > 0) { "16 kHz microphone capture is unavailable." }

            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, frameSamples * 2 * 4),
            )
            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                "Could not initialise microphone capture."
            }

            recorder = audioRecord
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching {
                    AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply {
                        enabled = true
                    }
                }.getOrNull()
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = runCatching {
                    NoiseSuppressor.create(audioRecord.audioSessionId)?.apply {
                        enabled = true
                    }
                }.getOrNull()
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = runCatching {
                    AutomaticGainControl.create(audioRecord.audioSessionId)?.apply {
                        enabled = true
                    }
                }.getOrNull()
            }

            audioRecord.startRecording()
            running.set(true)
            captureThread = Thread({ captureLoop(audioRecord) }, "Nia-Pcm-Capture").apply {
                priority = Process.THREAD_PRIORITY_AUDIO
                start()
            }
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val frame = ShortArray(frameSamples)
        try {
            while (running.get()) {
                val count = audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) continue
                val samples = if (count == frame.size) frame.copyOf() else frame.copyOf(count)
                val rms = calculateRms(samples)
                updateVoiceActivity(rms)
                onPcmFrame(samples, System.nanoTime())
            }
        } catch (_: IllegalStateException) {
            // Recorder was stopped or invalidated by Android while the session ended.
        } finally {
            running.set(false)
        }
    }

    private fun updateVoiceActivity(rms: Double) {
        // Conservative starter VAD only. Production VAD should replace this with
        // a validated local model; thresholds require Pixel 9 acoustic calibration.
        val voiced = rms >= START_VOICE_RMS
        if (voiced) {
            voicedFrames++
            silentFrames = 0
        } else {
            silentFrames++
            voicedFrames = 0
        }

        if (!voiceActive && voicedFrames >= START_VOICE_FRAMES) {
            voiceActive = true
            onVoiceActivity(true, System.nanoTime())
        } else if (voiceActive && silentFrames >= END_VOICE_FRAMES) {
            voiceActive = false
            onVoiceActivity(false, System.nanoTime())
        }
    }

    /** Plays mono PCM16 samples. Returns the generation token for cancellation. */
    fun playPcm(samples: ShortArray, playbackSampleRate: Int = OUTPUT_SAMPLE_RATE): Long {
        require(playbackSampleRate in 8_000..48_000) { "Unsupported playback sample rate." }
        val generation = playbackGeneration.incrementAndGet()
        stopPlayerOnly()

        val minBuffer = AudioTrack.getMinBufferSize(
            playbackSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "PCM playback is unavailable." }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(playbackSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, samples.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        synchronized(lock) { player = track }
        check(track.state == AudioTrack.STATE_INITIALIZED) {
            track.release()
            "Could not initialise audio playback."
        }
        track.play()
        var offset = 0
        while (offset < samples.size && playbackGeneration.get() == generation) {
            val written = track.write(
                samples,
                offset,
                samples.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) break
            offset += written
        }
        if (playbackGeneration.get() == generation) {
            track.stop()
        }
        synchronized(lock) {
            if (player === track) {
                player = null
                track.release()
            }
        }
        return generation
    }

    /** Invalidates queued playback immediately; stale generations cannot continue. */
    fun interruptPlayback() {
        playbackGeneration.incrementAndGet()
        stopPlayerOnly()
    }

    private fun stopPlayerOnly() {
        val old = synchronized(lock) {
            player.also { player = null }
        }
        runCatching {
            old?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
                it.flush()
                it.stop()
                it.release()
            }
        }
    }

    fun stopCapture() {
        running.set(false)
        val oldRecorder: AudioRecord?
        val oldThread: Thread?
        synchronized(lock) {
            oldRecorder = recorder
            recorder = null
            oldThread = captureThread
            captureThread = null
        }
        runCatching { oldRecorder?.stop() }
        oldThread?.takeIf { it !== Thread.currentThread() }?.join(1_000)
        oldRecorder?.release()
        echoCanceler?.release()
        noiseSuppressor?.release()
        gainControl?.release()
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        voiceActive = false
        voicedFrames = 0
        silentFrames = 0
    }

    override fun close() {
        interruptPlayback()
        stopCapture()
    }

    private fun calculateRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val normalised = sample / 32768.0
            sum += normalised * normalised
        }
        return sqrt(sum / samples.size)
    }

    private companion object {
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
        const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz
        const val START_VOICE_RMS = 0.018
        const val START_VOICE_FRAMES = 3
        const val END_VOICE_FRAMES = 18
    }
}
