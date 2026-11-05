package com.inspiredandroid.kai.nia.memory

import com.inspiredandroid.kai.data.MemoryAssessment
import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import kotlinx.serialization.json.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val ID_PREFIX = "mem_"
private val kaiJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    explicitNulls = false
}
private val knownKaiKeys = setOf(
    "id",
    "key",
    "content",
    "timestamp",
    "createdAt",
    "updatedAt",
    "category",
    "hitCount",
    "source",
    "receiptStatus",
    "claimStatus",
    "factStatus",
    "resolutionStatus",
    "sourceConversationId",
    "provenance",
    "providerShareAllowed",
)

@OptIn(ExperimentalTime::class)
internal fun millisToIso(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

@OptIn(ExperimentalTime::class)
internal fun isoToMillis(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

fun MemoryEntry.toPamEpisode(): PamEpisode = PamEpisode(
    id = ID_PREFIX + id,
    timestamp = millisToIso(createdAt),
    updatedAt = millisToIso(updatedAt),
    key = key,
    content = content,
    category = category.name,
    hitCount = hitCount,
    source = source,
    receiptStatus = receiptStatus.name,
    claimStatus = claimStatus.name,
    factStatus = factStatus.name,
    resolutionStatus = resolutionStatus.name,
    sourceConversationId = sourceConversationId,
    provenance = provenance,
    providerShareAllowed = providerShareAllowed,
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
        id = id.removePrefix(ID_PREFIX).ifEmpty { key },
        receiptStatus = MemoryAssessment.entries.firstOrNull { it.name == receiptStatus } ?: MemoryAssessment.CONFIRMED,
        claimStatus = MemoryAssessment.entries.firstOrNull { it.name == claimStatus } ?: MemoryAssessment.REPORTED,
        factStatus = MemoryAssessment.entries.firstOrNull { it.name == factStatus } ?: MemoryAssessment.UNKNOWN,
        resolutionStatus = MemoryAssessment.entries.firstOrNull { it.name == resolutionStatus } ?: MemoryAssessment.UNKNOWN,
        sourceConversationId = sourceConversationId,
        provenance = provenance,
        providerShareAllowed = providerShareAllowed,
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
    isoToMillis(nowIso)
    val root = kaiJson.parseToJsonElement(kaiSettingsJson).jsonObject

    val memoryElement = root["agent_memories"] ?: root["memory"] ?: root["memories"]
    require(memoryElement == null || memoryElement is JsonArray) { "Memory entries must be an array" }
    val memArray = memoryElement?.jsonArray ?: JsonArray(emptyList())

    val episodes = memArray.mapIndexed { index, el ->
        val obj = el as? JsonObject ?: throw IllegalArgumentException("Memory entry $index must be an object")
        val key = obj["key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Memory entry $index has no key")
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: key
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val category = obj["category"]?.jsonPrimitive?.contentOrNull
        val hitCount = obj["hitCount"]?.jsonPrimitive?.intOrNull ?: 0
        val source = obj["source"]?.jsonPrimitive?.contentOrNull

        val extra = obj.filterKeys { it !in knownKaiKeys }
            .mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }

        val originalTimestamp = readKaiTimestamp(obj["timestamp"] ?: obj["createdAt"], nowIso)
        val updatedAt = obj["updatedAt"]?.let { readKaiTimestamp(it, originalTimestamp) }

        PamEpisode(
            id = if (id.startsWith(ID_PREFIX)) id else ID_PREFIX + id,
            timestamp = originalTimestamp,
            updatedAt = updatedAt,
            key = key,
            content = content,
            category = category,
            hitCount = hitCount,
            source = source,
            receiptStatus = obj["receiptStatus"]?.jsonPrimitive?.contentOrNull,
            claimStatus = obj["claimStatus"]?.jsonPrimitive?.contentOrNull,
            factStatus = obj["factStatus"]?.jsonPrimitive?.contentOrNull,
            resolutionStatus = obj["resolutionStatus"]?.jsonPrimitive?.contentOrNull,
            sourceConversationId = obj["sourceConversationId"]?.jsonPrimitive?.contentOrNull,
            provenance = obj["provenance"]?.let { element ->
                runCatching {
                    kaiJson.decodeFromJsonElement<List<com.inspiredandroid.kai.data.MemoryProvenance>>(element)
                }.getOrNull()
            }.orEmpty(),
            providerShareAllowed = obj["providerShareAllowed"]?.jsonPrimitive?.booleanOrNull ?: false,
            extra = extra,
        )
    }
    require(episodes.map { it.id }.distinct().size == episodes.size) {
        "Memory settings contain duplicate ids"
    }
    val manifest = PamManifest(bundleId = "nia_$nowIso", createdAt = nowIso, schemaVersion = 1, checksum = "")
    return MemBundle(manifest, episodes)
}

private fun readKaiTimestamp(element: JsonElement?, fallback: String): String {
    if (element == null) return fallback
    val value = element.jsonPrimitive.content
    return value.toLongOrNull()?.let(::millisToIso) ?: run {
        isoToMillis(value)
        value
    }
}

fun memBundleToKaiJson(bundle: MemBundle, originalKaiJson: String): String {
    val root = kaiJson.parseToJsonElement(originalKaiJson).jsonObject
    val live = bundle.episodes.filter { it.deletedAt == null }
    live.forEach { episode -> episode.toMemoryEntry() }
    require(live.map { it.id }.distinct().size == live.size) {
        "Memory bundle contains duplicate episode ids"
    }
    val newMemory = JsonArray(
        live.map { ep ->
            buildJsonObject {
                put("key", ep.key)
                put("id", ep.id.removePrefix(ID_PREFIX))
                put("content", ep.content)
                put("createdAt", isoToMillis(ep.timestamp))
                put("updatedAt", isoToMillis(ep.updatedAt ?: ep.timestamp))
                ep.category?.let { put("category", it) }
                put("hitCount", ep.hitCount)
                ep.source?.let { put("source", it) }
                ep.receiptStatus?.let { put("receiptStatus", it) }
                ep.claimStatus?.let { put("claimStatus", it) }
                ep.factStatus?.let { put("factStatus", it) }
                ep.resolutionStatus?.let { put("resolutionStatus", it) }
                ep.sourceConversationId?.let { put("sourceConversationId", it) }
                if (ep.provenance.isNotEmpty()) {
                    put("provenance", kaiJson.encodeToJsonElement(ep.provenance))
                }
                put("providerShareAllowed", ep.providerShareAllowed)
                ep.extra.forEach { (k, v) -> put(k, v) }
            }
        },
    )

    val newRoot = JsonObject(root.toMutableMap().apply { put("agent_memories", newMemory) })
    return kaiJson.encodeToString(JsonObject.serializer(), newRoot)
}
