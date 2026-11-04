package com.inspiredandroid.kai.nia.retrieval

import com.inspiredandroid.kai.data.MemoryAssessment
import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import kotlinx.serialization.json.Json
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
        provenance = emptyList(),
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

        val record = document["records"]!!.jsonArray.single().jsonObject
        val required = setOf(
            "key", "content", "createdAt", "updatedAt", "category", "hitCount",
            "source", "id", "receiptStatus", "claimStatus", "factStatus",
            "resolutionStatus", "sourceConversationId", "provenance",
        )
        assertTrue(record.keys.containsAll(required))
        assertNotNull(record["source"])
        assertNotNull(record["sourceConversationId"])
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
