package com.inspiredandroid.kai.nia.retrieval

import com.inspiredandroid.kai.data.MemoryEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

/**
 * Pure Android-side snapshot encoder.
 *
 * This class accepts an already supplied list and does not read DataRepository,
 * AppSettings, MemoryStore, files, or the sandbox. It is deliberately not wired
 * to an automatic or real-memory transfer path.
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

    fun build(
        records: List<MemoryEntry>,
        snapshotId: String = UUID.randomUUID().toString(),
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        require(snapshotId.isNotBlank()) { "snapshotId must not be blank" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must be non-negative" }
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
        val canonicalRecords = canonicalize(encodedRecords)
        val canonicalBytes = json.encodeToString(JsonElement.serializer(), canonicalRecords)
            .encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalBytes)
            .joinToString("") { byte -> "%02x".format(byte) }

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
            put("records", canonicalRecords)
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
        is JsonPrimitive -> element
    }
}
