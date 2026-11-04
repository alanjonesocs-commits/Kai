package com.inspiredandroid.kai.sherpa

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

private const val SAMPLE_RATE = 16_000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val REQUEST_MICROPHONE = 4107
private const val CHUNK_SAMPLES = 1_600 // 100 ms at 16 kHz

private data class SpikeModel(
    val id: String,
    val label: String,
    val repository: String,
    val revision: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val minimumEncoderBytes: Long,
)

private val SPIKE_MODELS = listOf(
    SpikeModel(
        id = "zipformer-en-20m-2023-02-17",
        label = "Zipformer English 20M (INT8 encoder)",
        repository = "csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
        revision = "d42f2d9f7ca24806fb667456a18a9f1b60f70d16",
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
        minimumEncoderBytes = 40_000_000L,
    ),
    SpikeModel(
        id = "zipformer-en-2023-06-21",
        label = "Zipformer English 2023-06-21 (INT8 encoder)",
        repository = "csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21",
        revision = "9a65b6ea94c311ca770c2bf895b30f456a22d703",
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
        minimumEncoderBytes = 170_000_000L,
    ),
)

class SherpaSpikeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var recordingFinalized = false
    private var recordingThread: Thread? = null
    private var wavFile: File? = null
    private var pcmBytesWritten = 0L

    private val modelRoot: File
        get() = File(filesDir, "sherpa-spike/models")

    private val recordingRoot: File
        get() = File(getExternalFilesDir("sherpa-recordings") ?: filesDir, "sherpa-recordings")

    private val resultsRoot: File
        get() = File(getExternalFilesDir("sherpa-results") ?: filesDir, "sherpa-results")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Nia Sherpa Spike"
        buildScreen()
        appendStatus(
            "Isolated Sherpa-ONNX experiment. Audio and transcripts stay in this app's private storage. " +
                "No recording or result is uploaded by this screen.\n" +
                "Models: Apache-2.0 model cards; native runtime: official sherpa-onnx 2.0.1 AAR.\n" +
                "Endpoint rule 2: 600 ms trailing silence; maximum utterance: 20 seconds."
        )
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this)
        status = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        root.addView(
            TextView(this).apply {
                text = "Nia — Sherpa streaming ASR spike"
                textSize = 21f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        addButton(root, "Download 20M model") { downloadModel(SPIKE_MODELS[0]) }
        addButton(root, "Download 2023-06-21 model") { downloadModel(SPIKE_MODELS[1]) }

        recordButton = Button(this).apply {
            text = "Record reference script"
            setOnClickListener {
                if (recording) stopRecording() else startRecording()
            }
        }
        root.addView(recordButton)

        addButton(root, "Run same WAV — 20M model") { runModel(SPIKE_MODELS[0]) }
        addButton(root, "Run same WAV — 2023-06-21 model") { runModel(SPIKE_MODELS[1]) }
        addButton(root, "Show local files and SHA-256") { showLocalFiles() }

        root.addView(status, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            setOnClickListener { action() }
        })
    }

    private fun appendStatus(message: String) {
        runOnUiThread {
            status.append("\n\n$message")
        }
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MICROPHONE
            )
            appendStatus("Microphone permission requested. Tap Record again after granting it.")
            return
        }

        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minimum <= 0) {
            appendStatus("AudioRecord rejected the 16 kHz mono PCM16 configuration: $minimum")
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            maxOf(minimum * 2, CHUNK_SAMPLES * 2 * 4)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            appendStatus("AudioRecord failed to initialize.")
            return
        }

        recordingRoot.mkdirs()
        val file = File(recordingRoot, "reference-${System.currentTimeMillis()}.wav")
        wavFile = file
        recordingFinalized = false
        pcmBytesWritten = 0L
        val output = BufferedOutputStream(FileOutputStream(file))
        output.write(ByteArray(44))

        audioRecord = recorder
        recording = true
        recordButton.text = "Stop recording"
        appendStatus("Recording started at 16 kHz mono PCM16. Read the locked 30-sentence script naturally.")
        recorder.startRecording()

        recordingThread = thread(name = "sherpa-spike-recording") {
            val samples = ShortArray(CHUNK_SAMPLES)
            try {
                while (recording) {
                    val count = recorder.read(samples, 0, samples.size)
                    if (count > 0) {
                        val bytes = ByteArray(count * 2)
                        for (index in 0 until count) {
                            val value = samples[index].toInt()
                            bytes[index * 2] = (value and 0xff).toByte()
                            bytes[index * 2 + 1] = ((value shr 8) and 0xff).toByte()
                        }
                        output.write(bytes)
                        pcmBytesWritten += bytes.size
                        if (pcmBytesWritten > SAMPLE_RATE.toLong() * 2 * 300) {
                            recording = false
                            break
                        }
                    }
                }
            } catch (error: Exception) {
                appendStatus("Recording error: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                try {
                    recorder.stop()
                } catch (_: Exception) {
                }
                recorder.release()
                audioRecord = null
                // Append 700 ms of silence so the streaming endpoint can close the final utterance.
                val silence = ByteArray((SAMPLE_RATE * 2 * 0.7).toInt())
                output.write(silence)
                pcmBytesWritten += silence.size
                output.flush()
                output.close()
                writeWavHeader(file, pcmBytesWritten)
                recordingFinalized = true
                recording = false
                runOnUiThread {
                    recordButton.text = "Record reference script"
                    appendStatus("Recording saved locally: ${file.name} (${file.length()} bytes).")
                }
            }
        }
    }

    private fun stopRecording() {
        recording = false
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        appendStatus("Stopping recording and finalizing the WAV file…")
    }

    private fun writeWavHeader(file: File, dataLength: Long) {
        val totalLength = dataLength + 36
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalLength.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLength.toInt())
        java.io.RandomAccessFile(file, "rw").use {
            it.seek(0)
            it.write(header.array())
        }
    }

    private fun downloadModel(model: SpikeModel) {
        thread(name = "sherpa-model-download") {
            try {
                val directory = File(modelRoot, model.id)
                directory.mkdirs()
                val files = listOf(model.encoder, model.decoder, model.joiner, "tokens.txt")
                for (name in files) {
                    val target = File(directory, name)
                    if (target.isFile && target.length() > 0L) {
                        appendStatus("Already downloaded: ${model.id}/$name")
                        continue
                    }
                    val temporary = File(directory, "$name.part")
                    val url = URL("https://huggingface.co/${model.repository}/resolve/${model.revision}/$name?download=true")
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 120_000
                        instanceFollowRedirects = true
                    }
                    try {
                        connection.inputStream.use { input ->
                            temporary.outputStream().buffered().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } finally {
                        connection.disconnect()
                    }
                    check(temporary.isFile && temporary.length() > 0L) { "Empty download: $name" }
                    check(temporary.renameTo(target)) { "Could not finalize download: $name" }
                    appendStatus("Downloaded $name (${target.length()} bytes)")
                }
                val encoderFile = File(directory, model.encoder)
                check(encoderFile.length() >= model.minimumEncoderBytes) {
                    "Encoder size is smaller than expected: ${encoderFile.length()} bytes"
                }
                val manifest = files.joinToString("\n") { name ->
                    "${sha256(File(directory, name))}  $name"
                }
                File(directory, "SHA256SUMS.txt").writeText(manifest + "\n")
                appendStatus("Model ready: ${model.label}\nSHA-256 values saved beside the model.")
            } catch (error: Exception) {
                appendStatus("Model download failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun runModel(model: SpikeModel) {
        val inputWav = wavFile
        if (inputWav == null || !inputWav.isFile) {
            appendStatus("Record the reference script first.")
            return
        }
        val directory = File(modelRoot, model.id)
        val encoder = File(directory, model.encoder)
        val decoder = File(directory, model.decoder)
        val joiner = File(directory, model.joiner)
        val tokens = File(directory, "tokens.txt")
        if (listOf(encoder, decoder, joiner, tokens).any { !it.isFile }) {
            appendStatus("Download this model before running it.")
            return
        }

        thread(name = "sherpa-streaming-decode") {
            val batteryBefore = batteryReading()
            val startedAt = SystemClock.elapsedRealtime()
            var recognizer: OnlineRecognizer? = null
            var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
            try {
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath
                    ),
                    tokens = tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer"
                )
                recognizer = OnlineRecognizer(
                    config = OnlineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = modelConfig,
                        endpointConfig = EndpointConfig(
                            rule1 = EndpointRule(false, 0.6f, 0.0f),
                            rule2 = EndpointRule(true, 0.6f, 0.0f),
                            rule3 = EndpointRule(false, 0.0f, 20.0f)
                        ),
                        enableEndpoint = true,
                        decodingMethod = "greedy_search"
                    )
                )
                stream = recognizer.createStream()

                val segments = mutableListOf<String>()
                var lastPartial = ""
                var firstSpeechWallMs: Long? = null
                var firstPartialMs: Long? = null
                var lastSpeechWallMs: Long? = null
                var endpointDelayMs: Long? = null
                var totalSamples = 0L
                val playbackStart = SystemClock.elapsedRealtime()

                java.io.DataInputStream(BufferedInputStream(FileInputStream(inputWav))).use { input ->
                    val header = ByteArray(44)
                    input.readFully(header)
                    val bytes = ByteArray(CHUNK_SAMPLES * 2)
                    while (true) {
                        val count = input.read(bytes)
                        if (count <= 1) break
                        val sampleCount = count / 2
                        val samples = FloatArray(sampleCount)
                        var squareSum = 0.0
                        for (index in 0 until sampleCount) {
                            val raw = ((bytes[index * 2 + 1].toInt() shl 8) or
                                (bytes[index * 2].toInt() and 0xff)).toShort()
                            val sample = raw / 32768.0f
                            samples[index] = sample
                            squareSum += sample.toDouble() * sample.toDouble()
                        }
                        val rms = sqrt(squareSum / sampleCount)
                        val chunkWallMs = SystemClock.elapsedRealtime()
                        if (rms > 0.012) {
                            if (firstSpeechWallMs == null) firstSpeechWallMs = chunkWallMs
                            lastSpeechWallMs = chunkWallMs
                        }

                        stream.acceptWaveform(samples, sampleRate = SAMPLE_RATE)
                        while (recognizer.isReady(stream)) recognizer.decode(stream)

                        val resultText = recognizer.getResult(stream).text.trim()
                        if (resultText.isNotBlank() && resultText != lastPartial) {
                            if (firstPartialMs == null) {
                                firstPartialMs = chunkWallMs - (firstSpeechWallMs ?: playbackStart)
                            }
                            lastPartial = resultText
                            appendStatus("Partial: $resultText")
                        }

                        if (recognizer.isEndpoint(stream)) {
                            val finalSegment = recognizer.getResult(stream).text.trim()
                            if (finalSegment.isNotBlank()) segments.add(finalSegment)
                            if (lastSpeechWallMs != null) {
                                endpointDelayMs = chunkWallMs - lastSpeechWallMs!!
                            }
                            recognizer.reset(stream)
                            lastPartial = ""
                        }

                        totalSamples += sampleCount
                        val expectedElapsed = totalSamples * 1000L / SAMPLE_RATE
                        val actualElapsed = SystemClock.elapsedRealtime() - playbackStart
                        if (expectedElapsed > actualElapsed) {
                            Thread.sleep(expectedElapsed - actualElapsed)
                        }
                    }
                }

                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val trailing = recognizer.getResult(stream).text.trim()
                if (trailing.isNotBlank() && (segments.isEmpty() || segments.last() != trailing)) {
                    segments.add(trailing)
                }

                val transcript = segments.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                val finishedAt = SystemClock.elapsedRealtime()
                val batteryAfter = batteryReading()
                val report = buildString {
                    appendLine("Model: ${model.label}")
                    appendLine("Model ID: ${model.id}")
                    appendLine("Model repository: ${model.repository}")
                    appendLine("Model revision: ${model.revision}")
                    appendLine("WAV: ${inputWav.name}")
                    appendLine("WAV SHA-256: ${sha256(inputWav)}")
                    appendLine("Encoder SHA-256: ${sha256(encoder)}")
                    appendLine("Decoder SHA-256: ${sha256(decoder)}")
                    appendLine("Joiner SHA-256: ${sha256(joiner)}")
                    appendLine("Tokens SHA-256: ${sha256(tokens)}")
                    appendLine("Endpoint rule 2: 600 ms")
                    appendLine("Maximum utterance: 20 s")
                    appendLine("First partial from first RMS-detected speech chunk: ${firstPartialMs ?: "not observed"} ms")
                    appendLine("Last endpoint delay from last RMS-detected speech chunk: ${endpointDelayMs ?: "not observed"} ms")
                    appendLine("Decode wall time: ${finishedAt - startedAt} ms")
                    appendLine("Battery: ${batteryBefore.first}% -> ${batteryAfter.first}%")
                    appendLine("Battery temperature: ${batteryBefore.second} C -> ${batteryAfter.second} C")
                    appendLine("Android thermal status: ${thermalStatus()}")
                    appendLine()
                    appendLine("TRANSCRIPT")
                    appendLine(transcript)
                }
                resultsRoot.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val reportFile = File(resultsRoot, "${model.id}-$timestamp.txt")
                reportFile.writeText(report)
                appendStatus(report + "\nSaved locally: ${reportFile.name}")
            } catch (error: Exception) {
                appendStatus("Recognition failed: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                try { stream?.release() } catch (_: Exception) {}
                try { recognizer?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun batteryReading(): Pair<Int, Float> {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val temperature = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10f
        return percent to temperature
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "not available on this Android version"
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private fun showLocalFiles() {
        val details = buildString {
            appendLine("Models:")
            SPIKE_MODELS.forEach { model ->
                val directory = File(modelRoot, model.id)
                appendLine("${model.label}: ${if (directory.isDirectory) "present" else "not downloaded"}")
                File(directory, "SHA256SUMS.txt").takeIf { it.isFile }?.let {
                    appendLine(it.readText())
                }
            }
            appendLine("Recording: ${wavFile?.absolutePath ?: "none"}")
            appendLine("Results folder: ${resultsRoot.absolutePath}")
        }
        appendStatus(details)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun onDestroy() {
        if (recording) stopRecording()
        super.onDestroy()
    }
}
