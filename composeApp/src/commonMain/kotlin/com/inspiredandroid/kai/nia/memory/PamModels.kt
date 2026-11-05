package com.inspiredandroid.kai.nia.memory

import com.inspiredandroid.kai.data.MemoryAssessment
import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryProvenance
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
    val receiptStatus: String? = null,
    val claimStatus: String? = null,
    val factStatus: String? = null,
    val resolutionStatus: String? = null,
    val sourceConversationId: String? = null,
    val provenance: List<MemoryProvenance> = emptyList(),
    val providerShareAllowed: Boolean = false,
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
        fun parseEpisodes(text: String): List<PamEpisode> {
            val episodes = text
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { memJson.decodeFromString(PamEpisode.serializer(), it) }
                .toList()
            episodes.forEachIndexed { index, episode ->
                require(episode.id.isNotBlank()) { "Memory episode $index has no id" }
                require(episode.key.isNotBlank()) { "Memory episode $index has no key" }
                isoToMillis(episode.timestamp)
                episode.updatedAt?.let(::isoToMillis)
                episode.deletedAt?.let(::isoToMillis)
                require(episode.hitCount >= 0) { "Memory episode $index has a negative hit count" }
                episode.category?.let { category ->
                    require(MemoryCategory.entries.any { it.name == category }) {
                        "Memory episode $index has an unknown category"
                    }
                }
                listOf(episode.receiptStatus, episode.claimStatus, episode.factStatus, episode.resolutionStatus)
                    .filterNotNull()
                    .forEach { status ->
                        require(MemoryAssessment.entries.any { it.name == status }) {
                            "Memory episode $index has an unknown assessment"
                        }
                    }
            }
            require(episodes.map { it.id }.distinct().size == episodes.size) {
                "Memory bundle contains duplicate episode ids"
            }
            return episodes
        }
    }
}
