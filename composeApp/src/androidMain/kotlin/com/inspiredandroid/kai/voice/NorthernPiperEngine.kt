package com.inspiredandroid.kai.voice

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Offline Northern English Piper voice, backed by the Sherpa-ONNX version
 * already bundled for NIA speech recognition.
 *
 * Expected directory layout:
 * files/nia/voices/northern_piper/
 *   en_GB-northern_english_male-medium.onnx
 *   tokens.txt
 *   espeak-ng-data/
 */
internal class NorthernPiperEngine(
    private val modelDirectory: File,
    private val audioEngine: NiaAndroidAudioEngine,
) : Closeable {
    private val generation = AtomicLong(0L)
    private val synthesisMutex = Mutex()

    @Volatile
    private var offlineTts: OfflineTts? = null

    val isReady: Boolean
        get() = modelFilesArePresent() && offlineTts != null

    val isInstalled: Boolean
        get() = modelFilesArePresent()

    /**
     * Loads the native model once. Call from a background dispatcher.
     * The installer is responsible for downloading and validating all files.
     */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        synthesisMutex.withLock {
            if (offlineTts != null) return@withLock
            check(modelFilesArePresent()) {
                "Northern Piper voice is not installed."
            }

            val model = File(modelDirectory, MODEL_FILE)
            val tokens = File(modelDirectory, TOKENS_FILE)
            val espeakData = File(modelDirectory, ESPEAK_DIRECTORY)
            offlineTts = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.absolutePath,
                            tokens = tokens.absolutePath,
                            dataDir = espeakData.absolutePath,
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f,
                        ),
                        numThreads = MODEL_THREADS,
                        debug = false,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                ),
            )
        }
    }

    /**
     * Synthesises and streams PCM chunks to the interruptible AudioTrack path.
     * A new speak request or stop() invalidates the active generation.
     */
    suspend fun speak(text: String, speed: Float = 1.0f) {
        val phrase = text.trim()
        if (phrase.isEmpty()) return
        require(speed in MIN_SPEED..MAX_SPEED) { "Speech speed must be between 0.8 and 1.2." }

        if (offlineTts == null) prepare()
        val request = generation.incrementAndGet()
        synthesisMutex.withLock {
            val tts = offlineTts ?: error("Northern Piper could not be initialised.")
            withContext(Dispatchers.IO) {
                if (generation.get() != request) return@withContext
                audioEngine.playPcmStream(tts.sampleRate()) { writeSamples ->
                    tts.generateWithConfigAndCallback(
                        text = phrase,
                        config = GenerationConfig(
                            sid = 0,
                            speed = speed,
                            silenceScale = 0.2f,
                        ),
                    ) { samples ->
                        if (generation.get() == request && writeSamples(samples)) 1 else 0
                    }
                }
            }
        }
    }

    /** Stops both native generation (through its callback) and active PCM playback. */
    fun stop() {
        generation.incrementAndGet()
        audioEngine.interruptPlayback()
    }

    override fun close() {
        stop()
        offlineTts?.free()
        offlineTts = null
    }

    private fun modelFilesArePresent(): Boolean {
        val model = File(modelDirectory, MODEL_FILE)
        val tokens = File(modelDirectory, TOKENS_FILE)
        val espeakData = File(modelDirectory, ESPEAK_DIRECTORY)
        return model.isFile && model.length() >= MIN_MODEL_BYTES &&
            tokens.isFile && tokens.length() > 0L &&
            espeakData.isDirectory &&
            File(espeakData, "phontab").isFile &&
            File(espeakData, "phonindex").isFile
    }

    private companion object {
        const val MODEL_FILE = "en_GB-northern_english_male-medium.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val ESPEAK_DIRECTORY = "espeak-ng-data"
        const val MIN_MODEL_BYTES = 50_000_000L
        const val MODEL_THREADS = 2
        const val MIN_SPEED = 0.8f
        const val MAX_SPEED = 1.2f
    }
}
