package com.inspiredandroid.kai.nia.retrieval

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.MemoryEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * Android-side snapshot encoder and explicit raw-memory snapshot reader.
 *
 * The raw reader calls only AppSettings.getMemoriesJson(), which delegates to
 * Settings.getString(). It deliberately does not call MemoryStore.getAllMemories()
 * or SettingsJsonList.get(), so migration and corruption-backup callbacks cannot run.
 *
 * No data is written, repaired, migrated, or transferred by this object.
 */
internal object NiaMemorySnapshotBuilder {
    private const val FORMAT = "nia-memory-snapshot"
    private const val SCHEMA_VERSION = "1.0.0"

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        allowSpecialFloatingPointValues = false
        ignoreUnknownKeys = false
    }

    // Validation may accept future fields, but the original JsonObject is retained
    // for output so fields unknown to this app version are not discarded.
    private val validationJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
    }

    private val requiredRecordFields = setOf(
        "key", "content", "createdAt", "updatedAt", "category", "hitCount",
        "source", "id", "receiptStatus", "claimStatus", "factStatus",
        "resolutionStatus", "sourceConversationId", "provenance",
    )
    private val requiredProvenanceFields = setOf("source", "conversationId", "observedAt")

    /**
     * Explicit snapshot path from the raw settings value.
     *
     * A blank, malformed, non-array, legacy/incomplete, or invalid value fails closed.
     * In particular, it never converts a failed read into an empty-memory snapshot.
     */
    fun buildFromAppSettings(
        appSettings: AppSettings,
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): String = buildFromRawJson(
        rawMemoryJson = appSettings.getMemoriesJson(),
        snapshotId = snapshotId,
        createdAtEpochMs = createdAtEpochMs,
    )

    /**
     * Build from the exact stored JSON array without routing through MemoryStore.
     * Unknown record fields are retained byte-semantically as JSON values (object key
     * order/whitespace are canonicalised for the snapshot hash).
     */
    fun buildFromRawJson(
        rawMemoryJson: String,
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        require(rawMemoryJson.isNotBlank()) {
            "Canonical memory JSON is blank; snapshot refused"
        }
        val records = try {
            Json.parseToJsonElement(rawMemoryJson) as? JsonArray
                ?: throw IllegalArgumentException("Canonical memory JSON must be an array")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Canonical memory JSON is invalid; snapshot refused", e)
        }

        val seenIds = mutableSetOf<String>()
        records.forEachIndexed { index, element ->
            val record = element as? JsonObject
                ?: throw IllegalArgumentException("Memory record $index is not an object")
            val missing = requiredRecordFields - record.keys
            require(missing.isEmpty()) {
                "Memory record $index is incomplete; missing fields: ${missing.sorted().joinToString()}"
            }
            val decoded = try {
                validationJson.decodeFromJsonElement(MemoryEntry.serializer(), record)
            } catch (e: Exception) {
                throw IllegalArgumentException("Memory record $index failed validation", e)
            }
            require(decoded.id.isNotBlank() && decoded.key.isNotBlank()) {
                "Memory record $index has a blank id or key"
            }
            require(seenIds.add(decoded.id)) {
                "Duplicate memory record ID '${decoded.id}'; snapshot rejected without repair"
            }
            require(
                decoded.createdAt >= 0 && decoded.updatedAt >= 0 && decoded.hitCount >= 0 &&
                    decoded.provenance.all { it.observedAt >= 0 },
            ) {
                "Memory record $index has a negative timestamp or hit count"
            }
            decoded.provenance.forEachIndexed { provenanceIndex, _ ->
                val provenance = (record["provenance"] as JsonArray)[provenanceIndex] as? JsonObject
                    ?: throw IllegalArgumentException("Memory record $index provenance $provenanceIndex is not an object")
                val missingProvenance = requiredProvenanceFields - provenance.keys
                require(missingProvenance.isEmpty()) {
                    "Memory record $index provenance $provenanceIndex is incomplete"
                }
            }
        }

        return buildEnvelope(
            records = canonicalize(records) as JsonArray,
            snapshotId = snapshotId,
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    fun build(
        records: List<MemoryEntry>,
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        require(records.all { it.id.isNotBlank() && it.key.isNotBlank() }) {
            "Every memory record must have a non-blank id and key"
        }
        require(records.map { it.id }.distinct().size == records.size) {
            "Duplicate memory record IDs; snapshot rejected without repair"
        }
        require(records.all {
            it.createdAt >= 0 && it.updatedAt >= 0 && it.hitCount >= 0 &&
                it.provenance.all { provenance -> provenance.observedAt >= 0 }
        }) {
            "Invalid negative timestamp or hit count; snapshot rejected without repair"
        }

        val encodedRecords = json.encodeToJsonElement(
            ListSerializer(MemoryEntry.serializer()),
            records,
        )
        return buildEnvelope(
            records = canonicalize(encodedRecords) as JsonArray,
            snapshotId = snapshotId,
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    private fun buildEnvelope(
        records: JsonArray,
        snapshotId: String,
        createdAtEpochMs: Long,
    ): String {
        require(snapshotId.isNotBlank()) { "snapshotId must not be blank" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must be non-negative" }

        val canonicalBytes = json.encodeToString(JsonElement.serializer(), records).encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        val envelope = buildJsonObject {
            put("format", FORMAT)
            put("schema_version", SCHEMA_VERSION)
            put("snapshot_id", snapshotId)
            put("created_at_epoch_ms", createdAtEpochMs)
            put(
                "source",
                buildJsonObject {
                    put("system", "NIA15")
                    put("device_role", "canonical_memory_source")
                },
            )
            put("record_count", records.size)
            put("hash_algorithm", "SHA-256")
            put("records_hash", digest)
            put("records", records)
        }
        return json.encodeToString(JsonObject.serializer(), envelope)
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}
