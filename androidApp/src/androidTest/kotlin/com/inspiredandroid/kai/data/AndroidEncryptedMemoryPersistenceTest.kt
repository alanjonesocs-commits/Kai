package com.inspiredandroid.kai.data

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.inspiredandroid.kai.createSecureSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Exercises the production encrypted-settings factory and MemoryStore together.
 *
 * A fresh Settings wrapper and MemoryStore are constructed for the read-back so this checks
 * persistence through the same encrypted SharedPreferences file used by the app. This is an
 * in-process reopen test; a physical force-stop/relaunch check is still required on a Pixel.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEncryptedMemoryPersistenceTest {

    @Test
    fun memorySurvivesReopeningEncryptedSettings() = runTest {
        val key = "nia_android_diag_${UUID.randomUUID()}"
        val firstStore = MemoryStore(AppSettings(createSecureSettings()))
        Log.i("NIA_DIAG", "WRITE_ATTEMPT key=$key")
        val saved = firstStore.store(
            key = key,
            content = "encrypted persistence round-trip",
            source = "android-instrumentation-test",
        )

        try {
            val immediate = firstStore.getAllMemories().singleOrNull { it.id == saved.id }
            Log.i("NIA_DIAG", "IMMEDIATE_READ=${immediate?.content ?: "NULL"}")
            assertNotNull("Memory was not readable immediately after store", immediate)

            val reopenedStore = MemoryStore(AppSettings(createSecureSettings()))
            val restored = reopenedStore.getAllMemories().singleOrNull { it.id == saved.id }
            Log.i("NIA_DIAG", "REOPENED_SETTINGS_READ=${restored?.content ?: "NULL"}")

            assertNotNull("Memory was not readable from a fresh encrypted Settings instance", restored)
            assertEquals(saved.key, restored!!.key)
            assertEquals(saved.content, restored.content)
            assertEquals(saved.provenance, restored.provenance)
            assertEquals(1, reopenedStore.getAllMemories().count { it.id == saved.id })
        } finally {
            // Remove only this uniquely named diagnostic record. Never clear the user's
            // encrypted preferences file or any other memory.
            runCatching {
                MemoryStore(AppSettings(createSecureSettings())).forgetById(saved.id)
            }
        }
    }
}
