package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
enum class MemoryCategory {
    GENERAL,
    LEARNING,
    ERROR,
    PREFERENCE,
}

@Serializable
enum class MemoryAssessment {
    CONFIRMED,
    REPORTED,
    ASSUMED,
    UNKNOWN,
    NEEDS_VERIFICATION,
}

@Serializable
data class MemoryProvenance(
    val source: String? = null,
    val conversationId: String? = null,
    val observedAt: Long,
)

@Immutable
@Serializable
data class MemoryEntry(
    val key: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val category: MemoryCategory = MemoryCategory.GENERAL,
    val hitCount: Int = 1,
    val source: String? = null,
    val id: String = key,
    val receiptStatus: MemoryAssessment = MemoryAssessment.CONFIRMED,
    val claimStatus: MemoryAssessment = MemoryAssessment.REPORTED,
    val factStatus: MemoryAssessment = MemoryAssessment.UNKNOWN,
    val resolutionStatus: MemoryAssessment = MemoryAssessment.UNKNOWN,
    val sourceConversationId: String? = null,
    val provenance: List<MemoryProvenance> = emptyList(),
)

@OptIn(ExperimentalTime::class)
class MemoryStore(appSettings: AppSettings) {

    private val memories = SettingsJsonList(
        read = appSettings::getMemoriesJson,
        write = appSettings::setMemoriesJson,
        itemSerializer = serializer<MemoryEntry>(),
        label = "MemoryStore",
        migrate = { migrateLegacyClaims(it) },
        onCorrupt = { appSettings.backupCorruptJson("MemoryStore", it) },
    )

    private fun migrateLegacyClaims(entries: List<MemoryEntry>): List<MemoryEntry>? {
        val conflictingKeys = entries.groupBy { it.key }
            .filterValues { claims -> claims.map { it.content }.distinct().size > 1 }
            .keys
        val duplicateIds = entries.groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
        val hasUnclassifiedConflicts = entries.any { entry ->
            entry.key in conflictingKeys &&
                (entry.receiptStatus != MemoryAssessment.CONFIRMED ||
                    entry.claimStatus != MemoryAssessment.REPORTED ||
                    entry.factStatus != MemoryAssessment.UNKNOWN ||
                    entry.resolutionStatus != MemoryAssessment.NEEDS_VERIFICATION)
        }
        val hasLegacyProvenance = entries.any { it.provenance.isEmpty() && (it.source != null || it.sourceConversationId != null) }
        if (duplicateIds.isEmpty() && entries.none { it.id.isBlank() } && !hasUnclassifiedConflicts && !hasLegacyProvenance) return null
        val usedIds = mutableSetOf<String>()
        val migrated = entries.mapIndexed { index, entry ->
            val baseId = entry.id.ifBlank { entry.key }
            var id = baseId
            var suffix = 1
            while (!usedIds.add(id)) {
                id = "$baseId:legacy:$index:$suffix"
                suffix++
            }
            val withProvenance = if (entry.provenance.isEmpty() && (entry.source != null || entry.sourceConversationId != null)) {
                entry.copy(provenance = listOf(MemoryProvenance(entry.source, entry.sourceConversationId, entry.createdAt)))
            } else {
                entry
            }
            if (entry.key in conflictingKeys) {
                withProvenance.copy(
                    id = id,
                    receiptStatus = MemoryAssessment.CONFIRMED,
                    claimStatus = MemoryAssessment.REPORTED,
                    factStatus = MemoryAssessment.UNKNOWN,
                    resolutionStatus = MemoryAssessment.NEEDS_VERIFICATION,
                )
            } else if (withProvenance.id != id) {
                withProvenance.copy(id = id)
            } else {
                withProvenance
            }
        }
        return migrated.takeIf { it != entries }
    }

    suspend fun store(
        key: String,
        content: String,
        category: MemoryCategory = MemoryCategory.GENERAL,
        source: String? = null,
        sourceConversationId: String? = null,
    ): MemoryEntry {
        val now = Clock.System.now().toEpochMilliseconds()
        var storedEntry: MemoryEntry? = null
        memories.update { current ->
            current.firstOrNull { it.key == key && it.content == content }?.let {
                val observation = newProvenance(source, sourceConversationId, now)
                val alreadyObserved = observation != null && it.provenance.any { existing ->
                    existing.source == observation.source && existing.conversationId == observation.conversationId
                }
                if (observation == null || alreadyObserved) {
                    storedEntry = it
                    return@update current
                }
                val updated = it.copy(updatedAt = now, provenance = it.provenance + observation)
                storedEntry = updated
                return@update current.map { entry -> if (entry.id == it.id) updated else entry }
            }

            val conflicts = current.filter { it.key == key }
            val idBase = if (conflicts.isEmpty()) key else "$key:$now"
            var id = idBase
            var suffix = 1
            while (current.any { it.id == id }) {
                id = "$idBase:$suffix"
                suffix++
            }
            val entry = MemoryEntry(
                key = key,
                content = content,
                createdAt = now,
                updatedAt = now,
                category = category,
                source = source,
                id = id,
                sourceConversationId = sourceConversationId,
                resolutionStatus = if (conflicts.isEmpty()) MemoryAssessment.UNKNOWN else MemoryAssessment.NEEDS_VERIFICATION,
                provenance = listOfNotNull(newProvenance(source, sourceConversationId, now)),
            )
            storedEntry = entry
            if (conflicts.isEmpty()) {
                current + entry
            } else {
                current.map { existing ->
                    if (existing.key == key) {
                        existing.copy(
                            receiptStatus = MemoryAssessment.CONFIRMED,
                            claimStatus = MemoryAssessment.REPORTED,
                            factStatus = MemoryAssessment.UNKNOWN,
                            resolutionStatus = MemoryAssessment.NEEDS_VERIFICATION,
                        )
                    } else {
                        existing
                    }
                } + entry
            }
        }
        return checkNotNull(storedEntry) { "MemoryStore update completed without returning an entry" }
    }

    private fun newProvenance(
        source: String?,
        sourceConversationId: String?,
        observedAt: Long,
    ): MemoryProvenance? = if (source == null && sourceConversationId == null) {
        null
    } else {
        MemoryProvenance(source, sourceConversationId, observedAt)
    }

    suspend fun updateContent(key: String, content: String): MemoryEntry? {
        val matches = memories.get().filter { it.key == key }
        if (matches.size != 1) return null
        return updateContentById(matches.single().id, content)
    }

    suspend fun updateContentById(id: String, content: String): MemoryEntry? =
        mutateEntry(id) { it.copy(content = content, updatedAt = Clock.System.now().toEpochMilliseconds()) }

    suspend fun reinforceMemory(key: String): MemoryEntry? {
        val matches = memories.get().filter { it.key == key }
        if (matches.size != 1) return null
        return mutateEntry(matches.single().id) {
            it.copy(hitCount = it.hitCount + 1, updatedAt = Clock.System.now().toEpochMilliseconds())
        }
    }

    /** Applies [transform] to the entry under [id], persisting only when it exists. */
    private suspend fun mutateEntry(key: String, transform: (MemoryEntry) -> MemoryEntry): MemoryEntry? {
        var updated: MemoryEntry? = null
        memories.update { current ->
            val existing = current.find { it.id == key } ?: current.singleOrNull { it.key == key } ?: return@update current
            val next = transform(existing)
            updated = next
            current.map { if (it.id == existing.id) next else it }
        }
        return updated
    }

    fun getPromotionCandidates(minHits: Int = 5): List<MemoryEntry> = memories.get().filter { it.hitCount >= minHits }

    suspend fun forget(key: String): Boolean {
        var removed = false
        memories.update { current ->
            val matches = current.filter { it.key == key }
            if (matches.size != 1) return@update current
            removed = true
            current.filterNot { it.id == matches.single().id }
        }
        return removed
    }

    suspend fun forgetById(id: String): Boolean {
        var removed = false
        memories.update { current ->
            removed = current.any { it.id == id }
            if (removed) current.filterNot { it.id == id } else current
        }
        return removed
    }

    fun getAllMemories(): List<MemoryEntry> = memories.get()
}
