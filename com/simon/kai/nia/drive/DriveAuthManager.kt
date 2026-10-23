package com.simon.kai.nia.drive

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class DriveAuthManager(
    context: Context,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "drive_auth_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at_millis"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val EXPIRY_SKEW_MS = 60_000L

        // YOUR ACTUAL CLIENT ID IS HERE:
        const val CLIENT_ID = "490486700457-vibubq7nm5v799ob7d2ulk269grkjtq0.apps.googleusercontent.com"
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun saveTokens(accessToken: String, refreshToken: String?, expiresAtMillis: Long) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, expiresAtMillis)
            apply()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    suspend fun refreshIfNeeded(): String = withContext(Dispatchers.IO) {
        val current = prefs.getString(KEY_ACCESS_TOKEN, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val now = System.currentTimeMillis()

        if (current != null && expiresAt - EXPIRY_SKEW_MS > now) return@withContext current

        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw IllegalStateException("No refresh token — user must sign in again")

        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val req = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "Token refresh failed ${resp.code}: $text" }
            val obj = json.parseToJsonElement(text).jsonObject
            val newAccess = obj["access_token"]?.jsonPrimitive?.content
                ?: error("Token response missing access_token")
            val expiresInSec = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
            val newExpiry = System.currentTimeMillis() + expiresInSec * 1000
            saveTokens(newAccess, refreshToken = null, expiresAtMillis = newExpiry)
            newAccess
        }
    }
}
