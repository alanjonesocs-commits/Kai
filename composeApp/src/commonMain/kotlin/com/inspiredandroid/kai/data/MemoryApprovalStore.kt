package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Immutable
@Serializable
data class PendingMemoryApproval(
    val id: String,
    val key: String,
    val content: String,
    val category: MemoryCategory = MemoryCategory.GENERAL,
    val source: String? = null,
    val sourceConversationId: String? = null,
    val createdAt: Long,
    /** Suggestions are treated as sensitive and local-only until the user reviews them. */
    val isSensitive: Boolean = true,
)

/**
 * Persists AI-generated memory proposals separately from approved memories.
 * A proposal is never included in chat context, provider prompts or portable exports.
 */
@OptIn(ExperimentalTime::class)
class MemoryApprovalStore(appSettings: AppSettings) {
    private val approvals = SettingsJsonList(
        read = appSettings::getPendingMemoryApprovalsJson,
        write = appSettings::setPendingMemoryApprovalsJson,
        itemSerializer = serializer<PendingMemoryApproval>(),
        label = "MemoryApprovalStore",
        onCorrupt = { appSettings.backupCorruptJson("MemoryApprovalStore", it) },
    )

    fun getPendingApprovals(): List<PendingMemoryApproval> = approvals.get().sortedBy { it.createdAt }

    suspend fun suggest(
        key: String,
        content: String,
        category: MemoryCategory = MemoryCategory.GENERAL,
        source: String? = null,
        sourceConversationId: String? = null,
    ): PendingMemoryApproval = sharedMutex.withLock {
        val current = approvals.get()
        current.firstOrNull { it.key == key && it.content == content }?.let { return@withLock it }
        val now = Clock.System.now().toEpochMilliseconds()
        val suggestion = PendingMemoryApproval(
            id = "pending_${now}_${current.size}_${key.hashCode()}_${content.hashCode()}",
            key = key,
            content = content,
            category = category,
            source = source,
            sourceConversationId = sourceConversationId,
            createdAt = now,
        )
        approvals.set(current + suggestion)
        suggestion
    }

    /** Writes to permanent memory only after an explicit user approval action. */
    suspend fun approve(id: String, memoryStore: MemoryStore): MemoryEntry? = sharedMutex.withLock {
        val suggestion = approvals.get().firstOrNull { it.id == id } ?: return@withLock null
        val saved = memoryStore.store(
            key = suggestion.key,
            content = suggestion.content,
            category = suggestion.category,
            source = suggestion.source,
            sourceConversationId = suggestion.sourceConversationId,
        )
        approvals.set(approvals.get().filterNot { it.id == id })
        saved
    }

    suspend fun reject(id: String): Boolean = sharedMutex.withLock {
        val current = approvals.get()
        val exists = current.any { it.id == id }
        if (exists) approvals.set(current.filterNot { it.id == id })
        exists
    }

    private companion object {
        // Tool runtime and Settings UI may hold separate instances over the same settings backend.
        val sharedMutex = Mutex()
    }
}
