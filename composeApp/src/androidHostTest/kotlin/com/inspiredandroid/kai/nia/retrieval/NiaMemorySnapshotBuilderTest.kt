package com.inspiredandroid.kai.nia.retrieval

import com.inspiredandroid.kai.data.AppSettings
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
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
    fun schema11GoldenFixtureMatchesPythonCanonicalHashes() {
        val rawRecords = """[{"category":"PREFERENCE","claimStatus":"REPORTED","content":"Fictional test user prefers green notebooks.","createdAt":1793529000000,"factStatus":"UNKNOWN","futureField":{"😀":"emoji key","～":"fullwidth tilde key"},"hitCount":1,"id":"mem-syn-001","key":"notebook.preference","provenance":[{"conversationId":"synthetic-conversation-001","observedAt":1793529000000,"source":"synthetic_test"}],"receiptStatus":"CONFIRMED","resolutionStatus":"NEEDS_VERIFICATION","source":"synthetic_test","sourceConversationId":"synthetic-conversation-001","updatedAt":1793529000000},{"category":"GENERAL","claimStatus":"REPORTED","content":"Fictional test user said Room A.","createdAt":1793610000000,"factStatus":"UNKNOWN","hitCount":1,"id":"mem-syn-002","key":"workspace.preference","provenance":[],"receiptStatus":"CONFIRMED","resolutionStatus":"NEEDS_VERIFICATION","source":null,"sourceConversationId":null,"updatedAt":1793610000000},{"category":"GENERAL","claimStatus":"REPORTED","content":"Fictional test user later said Room B.","createdAt":1793696400000,"factStatus":"UNKNOWN","hitCount":2,"id":"mem-syn-003","key":"workspace.preference","provenance":[{"conversationId":"synthetic-conversation-003","observedAt":1793696400000,"source":"synthetic_test"}],"receiptStatus":"CONFIRMED","resolutionStatus":"NEEDS_VERIFICATION","source":"synthetic_test","sourceConversationId":"synthetic-conversation-003","updatedAt":1793696400000}]"""

        val document = json.parseToJsonElement(
            NiaMemorySnapshotBuilder.buildFromRawJson(
                rawMemoryJson = rawRecords,
                snapshotId = "snap-synthetic-000001",
                createdAtEpochMs = 1793770800000,
                sourceApplication = "NIA15",
                sourcePlatform = "Android",
                sourceDeviceLabel = "synthetic-fixture",
                sourceSystem = "NIA15",
            ),
        ).jsonObject

        assertEquals("1.1.0", document["schema_version"]?.jsonPrimitive?.content)
        assertEquals(
            "0fd7b83ee7a898cd608b12447a621dbda4ed3361c6fd8ecab6d929107f7b7fce",
            document["records_hash"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "1799c1ba33866cab76c46937dcc5dce7e95077089da6758851ba1fc01b0e73e5",
            document["envelope_hash"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun rawJsonRejectsDuplicateKeysBeforeJsonObjectConversion() {
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.buildFromRawJson("""[{"a":1,"a":2}]""")
        }
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.buildFromRawJson("""[{"a":1,"\\u0061":2}]""")
        }
        assertFailsWith<IllegalArgumentException> {
            NiaMemorySnapshotBuilder.buildFromRawJson("""[{"unknown":{"nested":1,"nested":2}}]""")
        }
    }

    @Test
    fun rawJsonRejectsUnsupportedNumbersAndLoneSurrogates() {
        for (raw in listOf(
            """[{"unknown":-0}]""",
            """[{"unknown":1.0}]""",
            """[{"unknown":1e2}]""",
            """[{"unknown":"\\ud800"}]""",
            """[{"unknown":"\\udc00"}]""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                NiaMemorySnapshotBuilder.buildFromRawJson(raw)
            }
        }
    }

    @Test
    fun rawSettingsSnapshotReadsMemoryOnceAndNeverWrites() {
        val backing = MapSettings()
        backing.putString("agent_memories", "[]")
        val tracking = object : Settings by backing {
            var memoryReads = 0
            var memoryWrites = 0

            override fun getStringOrNull(key: String): String? {
                if (key == "agent_memories") memoryReads++
                return backing.getStringOrNull(key)
            }

            override fun putString(key: String, value: String) {
                if (key == "agent_memories") memoryWrites++
                backing.putString(key, value)
            }
        }
        val appSettings = AppSettings(tracking)

        NiaMemorySnapshotBuilder.buildFromAppSettings(
            appSettings = appSettings,
            snapshotId = "empty-snapshot",
            createdAtEpochMs = 100,
        )

        assertEquals(1, tracking.memoryReads)
        assertEquals(0, tracking.memoryWrites)
        assertEquals("[]", backing.getStringOrNull("agent_memories"))
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
