package com.inspiredandroid.kai.nia.memory

import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val ID_PREFIX = "mem_"
private val kaiJson = Json { ignoreUnknownKeys = true; prettyPrint = true; explicitNulls = false }
private val knownKaiKeys = setOf("key", "content", "category", "hitCount", "source")

@OptIn(ExperimentalTime::class)
internal fun millisToIso(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

@OptIn(ExperimentalTime::class)
internal fun isoToMillis(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

fun MemoryEntry.toPamEpisode(): PamEpisode = PamEpisode(
    id = ID_PREFIX + key,
    timestamp = millisToIso(createdAt),
    updatedAt = millisToIso(updatedAt),
    key = key,
    content = content,
    category = category.name,
    hitCount = hitCount,
    source = source,
)

fun PamEpisode.toMemoryEntry(): MemoryEntry {
    val created = isoToMillis(timestamp)
    return MemoryEntry(
        key = key,
        content = content,
        createdAt = created,
        updatedAt = updatedAt?.let { isoToMillis(it) } ?: created,
        category = MemoryCategory.entries.firstOrNull { it.name == category } ?: MemoryCategory.GENERAL,
        hitCount = hitCount,
        source = source,
    )
}

fun memoriesToBundle(entries: List<MemoryEntry>, nowIso: String, previous: MemBundle? = null): MemBundle {
    val live = entries.map { it.toPamEpisode() }
    val liveIds = live.map { it.id }.toSet()
    val tombstones = previous?.episodes.orEmpty()
        .filter { it.id !in liveIds }
        .map { if (it.deletedAt == null) it.copy(deletedAt = nowIso) else it }
    val manifest = PamManifest(
        bundleId = "nia_$nowIso",
        createdAt = nowIso,
        schemaVersion = previous?.manifest?.schemaVersion ?: 1,
        checksum = "",
    )
    return MemBundle(manifest, (live + tombstones).sortedBy { it.id })
}

fun bundleToMemories(bundle: MemBundle): List<MemoryEntry> = bundle.episodes
    .filter { it.deletedAt == null }
    .mapNotNull { runCatching { it.toMemoryEntry() }.getOrNull() }
    .sortedWith(compareBy({ it.createdAt }, { it.key }))

// --- JSON BRIDGES ---

fun kaiJsonToMemBundle(kaiSettingsJson: String, nowIso: String): MemBundle {
    val root = runCatching { kaiJson.parseToJsonElement(kaiSettingsJson) }.getOrNull()?.jsonObject ?: JsonObject(emptyMap())
    
    // FIX: Kai uses "agent_memories"
    val memArray = (root["agent_memories"] as? JsonArray) ?: (root["memory"] as? JsonArray) ?: (root["memories"] as? JsonArray) ?: JsonArray(emptyList())

    val episodes = memArray.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val category = obj["category"]?.jsonPrimitive?.contentOrNull
        val hitCount = obj["hitCount"]?.jsonPrimitive?.intOrNull ?: 0
        val source = obj["source"]?.jsonPrimitive?.contentOrNull

        val extra = obj.filterKeys { it !in knownKaiKeys }
            .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }

        val originalTimestamp = obj["timestamp"]?.jsonPrimitive?.contentOrNull ?: nowIso

        PamEpisode(
            id = "mem_$key", timestamp = originalTimestamp, key = key, content = content,
            category = category, hitCount = hitCount, source = source, extra = extra
        )
    }
    val manifest = PamManifest(bundleId = "nia_$nowIso", createdAt = nowIso, schemaVersion = 1, checksum = "")
    return MemBundle(manifest, episodes)
}

fun memBundleToKaiJson(bundle: MemBundle, originalKaiJson: String): String {
    val root = runCatching { kaiJson.parseToJsonElement(originalKaiJson) }.getOrNull()?.jsonObject ?: JsonObject(emptyMap())
    val live = bundle.episodes.filter { it.deletedAt == null }
    val newMemory = JsonArray(live.map { ep ->
        buildJsonObject {
            put("key", ep.key); put("content", ep.content)
            ep.category?.let { put("category", it) }
            put("hitCount", ep.hitCount)
            ep.source?.let { put("source", it) }
            ep.extra.forEach { (k, v) -> put(k, v) }
        }
    })
    
    // FIX: Write back to "agent_memories"
    val newRoot = JsonObject(root.toMutableMap().apply { put("agent_memories", newMemory) })
    return kaiJson.encodeToString(JsonObject.serializer(), newRoot)
}
