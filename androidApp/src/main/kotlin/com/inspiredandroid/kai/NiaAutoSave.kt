package com.inspiredandroid.kai

import android.content.Context
import android.util.Log
import com.inspiredandroid.kai.data.DataRepository
import com.inspiredandroid.kai.data.ImportSection
import com.inspiredandroid.kai.nia.memory.kaiJsonToMemBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Saves NIA memory to a .mem file when the app leaves the foreground. Save only, never loads. */
object NiaAutoSave {
    private const val FILE_NAME = "nia-memory.mem"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun save(context: Context, repository: DataRepository) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        scope.launch {
            runCatching {
                val json = repository.exportSettingsToJson(setOf(ImportSection.MEMORY))
                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date())
                val bundle = kaiJsonToMemBundle(json, nowIso)
                if (bundle.episodes.isEmpty()) return@runCatching // never overwrite with empty
                val target = File(dir, FILE_NAME)
                val temp = File(dir, "$FILE_NAME.tmp")
                temp.writeText(bundle.toJsonLines())
                if (target.exists()) target.copyTo(File(dir, "$FILE_NAME.bak"), overwrite = true)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }.onFailure { Log.w("NiaAutoSave", "Auto-save failed", it) }
        }
    }
}
