package com.inspiredandroid.kai.voice

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads and safely installs the optional offline Northern English Piper voice.
 * Model files remain in app-private storage and are never bundled into the APK.
 */
internal class NorthernVoiceDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, MODEL_DIRECTORY)
    private val archiveFile = File(appContext.cacheDir, ARCHIVE_NAME)
    private val stagingDirectory = File(appContext.filesDir, "$MODEL_DIRECTORY.installing")
    private val mutex = Mutex()
    private val cancelled = AtomicBoolean(false)

    @Volatile private var activeJob: Job? = null

    sealed interface State {
        data object Idle : State
        data class Progress(val percent: Int, val phase: String) : State
        data object Success : State
        data class Error(val message: String) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    val isDownloaded: Boolean
        get() = validModelDirectory(modelDirectory)

    suspend fun download(onProgress: (Int) -> Unit = {}) = mutex.withLock {
        if (isDownloaded) {
            mutableState.value = State.Success
            onProgress(100)
            return@withLock
        }

        cancelled.set(false)
        activeJob = currentCoroutineContext()[Job]
        try {
            withContext(Dispatchers.IO) {
                check(appContext.filesDir.usableSpace >= MIN_FREE_SPACE_BYTES) {
                    "Not enough free storage. Free at least 200 MB and try again."
                }
                archiveFile.delete()
                deleteTree(stagingDirectory)
                check(stagingDirectory.mkdirs()) {
                    "Could not create the temporary Northern voice folder."
                }

                downloadArchive(onProgress)
                currentCoroutineContext().ensureActive()
                extractArchive(onProgress)
                currentCoroutineContext().ensureActive()

                check(validModelDirectory(stagingDirectory)) {
                    "The Northern voice package is incomplete or damaged."
                }

                // Swap only after every required file has passed validation.
                deleteTree(modelDirectory)
                check(stagingDirectory.renameTo(modelDirectory)) {
                    "Could not move the installed Northern voice into place."
                }
                check(isDownloaded) { "Northern voice validation failed after installation." }
                archiveFile.delete()
                mutableState.value = State.Success
                onProgress(100)
            }
        } catch (cancelledException: CancellationException) {
            deleteTree(stagingDirectory)
            archiveFile.delete()
            mutableState.value = State.Idle
            throw cancelledException
        } catch (error: Exception) {
            deleteTree(stagingDirectory)
            archiveFile.delete()
            mutableState.value = State.Error(
                error.message?.takeIf { it.isNotBlank() } ?: "Northern voice download failed.",
            )
        } finally {
            activeJob = null
            cancelled.set(false)
        }
    }

    fun cancel() {
        cancelled.set(true)
        activeJob?.cancel()
    }

    private fun downloadArchive(onProgress: (Int) -> Unit) {
        mutableState.value = State.Progress(0, "Downloading Northern voice")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ALAN-NIA15/1.0")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Northern voice download failed (HTTP ${connection.responseCode})."
            }
            val expectedLength = connection.contentLengthLong
            var received = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(archiveFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContextForDownloadCheck()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        if (expectedLength > 0L) {
                            val percent = ((received * 45L) / expectedLength).toInt().coerceIn(0, 45)
                            mutableState.value = State.Progress(percent, "Downloading Northern voice")
                            onProgress(percent)
                        }
                    }
                    output.fd.sync()
                }
            }
            check(archiveFile.isFile && archiveFile.length() >= MIN_ARCHIVE_BYTES) {
                "Northern voice archive is incomplete."
            }
            check(expectedLength <= 0L || archiveFile.length() == expectedLength) {
                "Northern voice archive failed its download length check."
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractArchive(onProgress: (Int) -> Unit) {
        mutableState.value = State.Progress(46, "Installing voice files")
        var expandedBytes = 0L
        var entries = 0
        val allowedFiles = setOf(MODEL_FILE, TOKENS_FILE)
        BufferedInputStream(archiveFile.inputStream()).use { buffered ->
            BZip2CompressorInputStream(buffered).use { bz2 ->
                TarArchiveInputStream(bz2).use { tar ->
                    while (true) {
                        currentCoroutineContextForDownloadCheck()
                        val entry = tar.nextTarEntry ?: break
                        entries++
                        check(entries <= MAX_ARCHIVE_ENTRIES) { "Northern voice archive contains too many files." }
                        check(
                            !entry.isSymbolicLink && !entry.isLink && !entry.isCharacterDevice &&
                                !entry.isBlockDevice && !entry.isFIFO,
                        ) { "Northern voice archive contains an unsupported file type." }

                        val rawName = entry.name.replace('\\', '/')
                        check(!rawName.startsWith("/") && !Regex("(^|/)\\.\\.(/|$)").containsMatchIn(rawName)) {
                            "Northern voice archive contains an unsafe path."
                        }
                        if (entry.isDirectory) continue
                        val destinationRelative = mapArchivePath(rawName) ?: continue
                        expandedBytes += entry.size.coerceAtLeast(0L)
                        check(expandedBytes <= MAX_EXPANDED_BYTES) {
                            "Northern voice archive exceeds the allowed expanded size."
                        }
                        val destination = File(stagingDirectory, destinationRelative)
                        check(destination.canonicalPath.startsWith(stagingDirectory.canonicalPath + File.separator)) {
                            "Northern voice archive contains an unsafe destination."
                        }
                        check(destination.parentFile?.let { it.exists() || it.mkdirs() } == true) {
                            "Could not create a voice model directory."
                        }
                        FileOutputStream(destination).use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            var copied = 0L
                            while (true) {
                                currentCoroutineContextForDownloadCheck()
                                val count = tar.read(buffer)
                                if (count < 0) break
                                copied += count
                                check(copied <= entry.size && copied <= MAX_EXPANDED_BYTES) {
                                    "Northern voice archive entry exceeded its declared size."
                                }
                                output.write(buffer, 0, count)
                            }
                            check(copied == entry.size) { "A Northern voice file was truncated." }
                            output.fd.sync()
                        }
                        val percent = (46 + (expandedBytes * 53L / MAX_EXPANDED_BYTES).toInt()).coerceIn(46, 99)
                        mutableState.value = State.Progress(percent, "Installing voice files")
                        onProgress(percent)
                    }
                }
            }
        }
        check(allowedFiles.all { File(stagingDirectory, it).isFile }) {
            "Northern voice model or token file is missing."
        }
    }

    private fun mapArchivePath(rawName: String): String? {
        val clean = rawName.removePrefix("./").trimStart('/')
        val segments = clean.split('/').filter { it.isNotEmpty() }
        val modelIndex = segments.indexOf(MODEL_FILE)
        if (modelIndex >= 0) return MODEL_FILE
        val tokensIndex = segments.indexOf(TOKENS_FILE)
        if (tokensIndex >= 0) return TOKENS_FILE
        val espeakIndex = segments.indexOf(ESPEAK_DIRECTORY)
        if (espeakIndex >= 0 && espeakIndex + 1 < segments.size) {
            return (listOf(ESPEAK_DIRECTORY) + segments.drop(espeakIndex + 1)).joinToString(File.separator)
        }
        return null
    }

    private fun validModelDirectory(directory: File): Boolean {
        val model = File(directory, MODEL_FILE)
        val tokens = File(directory, TOKENS_FILE)
        val espeak = File(directory, ESPEAK_DIRECTORY)
        return model.isFile && model.length() >= MIN_MODEL_BYTES &&
            tokens.isFile && tokens.length() > 0L &&
            File(espeak, "phontab").isFile &&
            File(espeak, "phonindex").isFile
    }

    // The download/extraction loops run on Dispatchers.IO and poll the owning job.
    private fun currentCoroutineContextForDownloadCheck() {
        if (cancelled.get() || activeJob?.isActive == false) {
            throw CancellationException("Northern voice download cancelled.")
        }
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }

    private companion object {
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_GB-northern_english_male-medium.tar.bz2"
        const val MODEL_DIRECTORY = "nia/voices/northern_piper"
        const val ARCHIVE_NAME = "nia-northern-piper.tar.bz2"
        const val MODEL_FILE = "en_GB-northern_english_male-medium.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val ESPEAK_DIRECTORY = "espeak-ng-data"
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 90_000
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MIN_ARCHIVE_BYTES = 10_000_000L
        const val MIN_MODEL_BYTES = 50_000_000L
        const val MIN_FREE_SPACE_BYTES = 200_000_000L
        const val MAX_EXPANDED_BYTES = 250_000_000L
        const val MAX_ARCHIVE_ENTRIES = 20_000
    }
}
