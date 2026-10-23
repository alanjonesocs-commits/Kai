package com.inspiredandroid.kai.nia.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PamManifest(
    val bundleId: String,
    val createdAt: String,
    val schemaVersion: Int,
    val checksum: String,
)

@Serializable
data class PamEpisode(
    val id: String,
    val timestamp: String,
    val updatedAt: String? = null,
    val key: String,
    val content: String,
    val category: String? = null,
    val hitCount: Int = 0,
    val source: String? = null,
    val deletedAt: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

private val memJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

data class MemBundle(
    val manifest: PamManifest,
    val episodes: List<PamEpisode>,
) {
    fun toJsonLines(): String = episodes.joinToString(separator = "\n", postfix = "\n") {
        memJson.encodeToString(PamEpisode.serializer(), it)
    }

    companion object {
        fun parseEpisodes(text: String): List<PamEpisode> = text
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { memJson.decodeFromString(PamEpisode.serializer(), it) }
            .toList()
    }
}
