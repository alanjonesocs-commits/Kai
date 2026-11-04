package com.inspiredandroid.kai.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs the pinned, Apache-2.0 English 20M streaming Zipformer model.
 *
 * Model weights are downloaded on first use rather than inflating every APK.
 * The revision is immutable; partial downloads are kept as .part files and
 * only promoted after the HTTP transfer completes successfully.
 */
internal object NiaSherpaModelInstaller {
    private const val MODEL_REPOSITORY =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    private const val MODEL_REVISION = "d42f2d9f7ca24806fb667456a18a9f1b60f70d16"
    private const val MODEL_DIRECTORY = "nia/speech-models/zipformer-en-20m"

    private data class ModelFile(
        val name: String,
        val minimumBytes: Long,
    )

    private val modelFiles = listOf(
        ModelFile("encoder-epoch-99-avg-1.int8.onnx", 40_000_000L),
        ModelFile("decoder-epoch-99-avg-1.onnx", 400_000L),
        ModelFile("joiner-epoch-99-avg-1.int8.onnx", 200_000L),
        ModelFile("tokens.txt", 4_000L),
    )

    suspend fun ensureInstalled(context: Context): File = withContext(Dispatchers.IO) {
        val directory = File(context.applicationContext.filesDir, MODEL_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "Could not create Nia's local speech model directory."
        }

        modelFiles.forEach { modelFile ->
            val destination = File(directory, modelFile.name)
            if (destination.isFile && destination.length() >= modelFile.minimumBytes) {
                return@forEach
            }

            val temporary = File(directory, "${modelFile.name}.part")
            temporary.delete()
            val url = URL(
                "$MODEL_REPOSITORY/resolve/$MODEL_REVISION/${modelFile.name}",
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 90_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ALAN-NIA15/1.0")
            }

            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Could not download speech model file ${modelFile.name} (HTTP ${connection.responseCode})."
                }
                val expectedLength = connection.contentLengthLong
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(temporary).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                check(temporary.length() >= modelFile.minimumBytes) {
                    "Downloaded speech model file ${modelFile.name} is incomplete."
                }
                check(expectedLength <= 0L || temporary.length() == expectedLength) {
                    "Downloaded speech model file ${modelFile.name} failed its length check."
                }
                check(temporary.renameTo(destination)) {
                    "Could not install speech model file ${modelFile.name}."
                }
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }

        directory
    }
}
