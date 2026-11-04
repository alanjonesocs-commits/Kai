package com.inspiredandroid.kai.nia.retrieval

import com.inspiredandroid.kai.data.MemoryAssessment
import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import com.inspiredandroid.kai.data.MemoryProvenance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NiaMemorySnapshotBuilderTest {
    private val json = Json

    private fun syntheticEntry(id: String = "synthetic-001") = MemoryEntry(
        key = "synthetic-key",
        content = "Fictional test memory only.",
        createdAt = 1_762_000_000_000,
        updatedAt = 1_762_000_000_000,
        category = MemoryCategory.GENERAL,
        hitCount = 1,
        source = null,
        id = id,
        receiptStatus = MemoryAssessment.CONFIRMED,
        claimStatus = MemoryAssessment.REPORTED,
        factStatus = MemoryAssessment.UNKNOWN,
        resolutionStatus = MemoryAssessment.NEEDS_VERIFICATION,
        sourceConversationId = null,
        provenance = listOf(MemoryProvenance("synthetic_test", "synthetic-conversation", 1_762_000_000_000)),
    )

    @Test
    fun snapshotContainsEveryMemoryEntryFieldIncludingNullDefaults() {
        val document = json.parseToJsonElement(
            NiaMemorySnapshotBuilder.build(
                records = listOf(syntheticEntry()),
                snapshotId = "snap-synthetic-001",
                createdAtEpochMs = 1_762_000_100_000,
            ),
        ).jsonObject

        assertEquals("nia-memory-snapshot", document["format"]?.jsonPrimitive?.content)
        assertEquals("1.0.0", document["schema_version"]?.jsonPrimitive?.content)
        assertEquals("snap-synthetic-001", document["snapshot_id"]?.jsonPrimitive?.content)
        assertEquals(1, document["record_count"]?.jsonPrimitive?.content?.toInt())
        assertEquals(64, document["records_hash"]?.jsonPrimitive?.content?.length)

        val record = document["records"]!!.jsonArray.single().jsonObject
        val required = setOf(
            "key", "content", "createdAt", "updatedAt", "category", "hitCount",
            "source", "id", "receiptStatus", "claimStatus", "factStatus",
            "resolutionStatus", "sourceConversationId", "provenance",
        )
        assertTrue(record.keys.containsAll(required))
        assertNotNull(record["source"])
        assertNotNull(record["sourceConversationId"])
        assertEquals(1, record["provenance"]!!.jsonArray.size)
    }

    @Test
    fun rawSnapshotPreservesUnknownFieldsWithoutTypedModelLoss() {
        val typedDocument = json.parseToJsonElement(
            NiaMemorySnapshotBuilder.build(
                records = listOf(syntheticEntry()),
                snapshotId = "snap-synthetic-raw",
                createdAtEpochMs = 1_762_000_100_000,
            ),
        ).jsonObject
        val rawRecord = typedDocument["records"]!!.jsonArray.single().jsonObject.toMutableMap()
        rawRecord["futureFieldFromNewerBuild"] = JsonPrimitive("preserve-this")
        val raw = JsonArray(listOf(JsonObject(rawRecord))).toString()

        val snapshot = json.parseToJsonElement(
            NiaMemorySnapshotBuilder.buildFromRawJson(
                rawMemoryJson = raw,
                snapshotId = "snap-synthetic-raw",
                createdAtEpochMs = 1_762_000_100_000,
            ),
        ).jsonObject
        val exportedRecord = snapshot["records"]!!.jsonArray.single().jsonObject

        assertEquals("preserve-this", exportedRecord["futureFieldFromNewerBuild"]?.jsonPrimitive?.content)
        assertEquals("synthetic-001", exportedRecord["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun malformedRawJsonFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.buildFromRawJson("{not-json")
        }
    }

    @Test
    fun blankRawJsonFailsClosedInsteadOfProducingEmptySnapshot() {
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.buildFromRawJson("  ")
        }
    }

    @Test
    fun incompleteLegacyRecordFailsClosedWithoutMigration() {
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.buildFromRawJson(
                """[{"key":"legacy-key","content":"legacy content"}]""",
            )
        }
    }

    @Test
    fun rawSnapshotRejectsDuplicateIdsWithoutRepair() {
        val record = json.parseToJsonElement(
            NiaMemorySnapshotBuilder.build(records = listOf(syntheticEntry())),
        ).jsonObject["records"]!!.jsonArray.single()
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.buildFromRawJson(
                JsonArray(listOf(record, record)).toString(),
            )
        }
    }

    @Test
    fun syntheticFixtureRecordsMatchPythonIndexerSha256() {
        fun entry(
            id: String,
            key: String,
            content: String,
            timestamp: Long,
            category: MemoryCategory,
            claim: MemoryAssessment,
            resolution: MemoryAssessment,
            conversation: String,
        ) = MemoryEntry(
            key = key,
            content = content,
            createdAt = timestamp,
            updatedAt = timestamp,
            category = category,
            hitCount = 1,
            source = "synthetic_test",
            id = id,
            receiptStatus = MemoryAssessment.CONFIRMED,
            claimStatus = claim,
            factStatus = MemoryAssessment.UNKNOWN,
            resolutionStatus = resolution,
            sourceConversationId = conversation,
            provenance = listOf(
                MemoryProvenance("synthetic_test", conversation, timestamp),
            ),
        )

        val records = listOf(
            entry("mem-syn-001", "notebook.preference", "Fictional test user prefers green notebooks.", 1793529000000, MemoryCategory.PREFERENCE, MemoryAssessment.REPORTED, MemoryAssessment.UNKNOWN, "synthetic-conversation-001"),
            entry("mem-syn-002", "workspace.preference", "Fictional test user said their preferred workspace is Room A.", 1793610000000, MemoryCategory.GENERAL, MemoryAssessment.REPORTED, MemoryAssessment.NEEDS_VERIFICATION, "synthetic-conversation-002"),
            entry("mem-syn-003", "workspace.preference", "Fictional test user later said their preferred workspace is Room B.", 1793696400000, MemoryCategory.GENERAL, MemoryAssessment.REPORTED, MemoryAssessment.NEEDS_VERIFICATION, "synthetic-conversation-003"),
            entry("mem-syn-004", "workspace.current_status", "Fictional test user's current preferred workspace has not been established.", 1793700000000, MemoryCategory.GENERAL, MemoryAssessment.UNKNOWN, MemoryAssessment.NEEDS_VERIFICATION, "synthetic-conversation-004"),
            entry("mem-syn-005", "workspace.change_hint", "A fictional note suggests a possible change to the user's workspace preference.", 1793770500000, MemoryCategory.LEARNING, MemoryAssessment.ASSUMED, MemoryAssessment.NEEDS_VERIFICATION, "synthetic-conversation-005"),
        )

        val document = json.parseToJsonElement(
            NiaMemorySnapshotBuilder.build(
                records = records,
                snapshotId = "snap-synthetic-000001",
                createdAtEpochMs = 1793770800000,
            ),
        ).jsonObject

        assertEquals(
            "8f1737efc3229fefb61e5e658e1216c41c1d261370e5934fcfac823e8646919b",
            document["records_hash"]?.jsonPrimitive?.content,
            "Kotlin snapshot hashing must match the checked-in Python indexer fixture",
        )
    }

    @Test
    fun duplicateIdsAreRejectedWithoutRepair() {
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.build(
                records = listOf(syntheticEntry("duplicate"), syntheticEntry("duplicate")),
                snapshotId = "snap-synthetic-duplicate",
                createdAtEpochMs = 1_762_000_100_000,
            )
        }
    }

    @Test
    fun blankIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.build(
                records = listOf(syntheticEntry(" ")),
                snapshotId = "snap-synthetic-blank",
                createdAtEpochMs = 1_762_000_100_000,
            )
        }
    }
}
