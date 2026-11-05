package com.inspiredandroid.kai.voice

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable
import java.io.File

/**
 * Streaming, on-device English ASR adapter for Nia's 16 kHz mono PCM capture.
 *
 * The model is deliberately stored outside the APK. The caller must provision the
 * Apache-2.0 model files in [modelDirectory] before constructing this class.
 * All calls to [acceptPcm16] must be serialised on one audio-processing thread.
 */
internal class NiaSherpaStreamingRecognizer(
    modelDirectory: File,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalTranscript: (String) -> Unit,
) : Closeable {
    private val recognizer: OnlineRecognizer
    private val stream: com.k2fsa.sherpa.onnx.OnlineStream
    private var lastPartial = ""

    init {
        val encoder = File(modelDirectory, ENCODER_FILE)
        val decoder = File(modelDirectory, DECODER_FILE)
        val joiner = File(modelDirectory, JOINER_FILE)
        val tokens = File(modelDirectory, TOKENS_FILE)

        listOf(encoder, decoder, joiner, tokens).forEach { file ->
            require(file.isFile && file.length() > 0L) {
                "Nia offline speech model is incomplete: ${file.name}"
            }
        }

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath,
            ),
            tokens = tokens.absolutePath,
            numThreads = MODEL_THREADS,
            provider = "cpu",
            modelType = "zipformer",
        )
        recognizer = OnlineRecognizer(
            config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = modelConfig,
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f),
                ),
                enableEndpoint = true,
            ),
        )
        stream = recognizer.createStream()
    }

    /**
     * Feed one PCM16 frame captured at 16 kHz. Partial results are cumulative;
     * final results are emitted once the recogniser detects an utterance endpoint.
     */
    fun acceptPcm16(samples: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        require(sampleRate == SAMPLE_RATE) { "Sherpa model requires 16 kHz audio." }
        if (samples.isEmpty()) return

        val floats = FloatArray(samples.size) { index -> samples[index] / 32768.0f }
        stream.acceptWaveform(floats, sampleRate = sampleRate)

        while (recognizer.isReady(stream)) {
            recognizer.decode(stream)
        }

        val transcript = recognizer.getResult(stream).text.trim()
        if (transcript.isNotEmpty() && transcript != lastPartial) {
            lastPartial = transcript
            onPartialTranscript(transcript)
        }

        if (recognizer.isEndpoint(stream)) {
            if (transcript.isNotEmpty()) {
                onFinalTranscript(transcript)
            }
            recognizer.reset(stream)
            lastPartial = ""
        }
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val MODEL_THREADS = 4
        const val ENCODER_FILE = "encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER_FILE = "decoder-epoch-99-avg-1.onnx"
        const val JOINER_FILE = "joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
    }
}
