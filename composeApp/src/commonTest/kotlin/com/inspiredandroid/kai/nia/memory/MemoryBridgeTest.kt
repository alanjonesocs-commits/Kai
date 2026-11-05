package com.inspiredandroid.kai.nia.memory

import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import com.inspiredandroid.kai.nia.sync.mergeBundles
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

@OptIn(ExperimentalSerializationApi::class)
class MemoryBridgeTest {
    private val now = "2026-10-24T09:00:00Z"

    private fun entry(
        key: String,
        content: String = "c",
        createdAt: Long = 1_700_000_000_000,
        updatedAt: Long = createdAt,
    ) = MemoryEntry(key = key, content = content, createdAt = createdAt, updatedAt = updatedAt)

    private fun episode(key: String, timestamp: String = "2026-10-23T10:00:00Z", category: String? = null, deletedAt: String? = null) = PamEpisode(id = "mem_$key", timestamp = timestamp, key = key, content = "c", category = category, deletedAt = deletedAt)

    private fun bundle(vararg eps: PamEpisode) = MemBundle(PamManifest("b", now, 1, ""), eps.toList())

    @Test
    fun entryRoundTripsExactly() {
        val original = MemoryEntry("k", "text", 1_700_000_000_123, 1_700_000_500_456, MemoryCategory.PREFERENCE, 4, "chat")
        assertEquals(original, original.toPamEpisode().toMemoryEntry())
    }

    @Test
    fun providerSharingPermissionRoundTripsExactly() {
        val original = MemoryEntry(
            key = "shareable_preference",
            content = "A non-sensitive preference",
            createdAt = 1_700_000_000_123,
            updatedAt = 1_700_000_500_456,
            providerShareAllowed = true,
        )

        assertEquals(original, original.toPamEpisode().toMemoryEntry())
    }

    @Test
    fun legacyMemoriesWithoutSharingFlagRemainLocalOnly() {
        val imported = kaiJsonToMemBundle(
            """{"agent_memories":[{"key":"private_note","content":"local"}]}""",
            now,
        ).episodes.single()

        assertEquals(false, imported.providerShareAllowed)
        assertEquals(false, imported.toMemoryEntry().providerShareAllowed)
    }

    @Test
    fun conflictingClaimMetadataAndIdsRoundTrip() {
        val original = MemoryEntry(
            key = "residence",
            content = "London",
            createdAt = 1_700_000_000_123,
            updatedAt = 1_700_000_500_456,
            id = "residence:claim-2",
            source = "conversation",
            receiptStatus = com.inspiredandroid.kai.data.MemoryAssessment.CONFIRMED,
            claimStatus = com.inspiredandroid.kai.data.MemoryAssessment.REPORTED,
            factStatus = com.inspiredandroid.kai.data.MemoryAssessment.UNKNOWN,
            resolutionStatus = com.inspiredandroid.kai.data.MemoryAssessment.NEEDS_VERIFICATION,
            sourceConversationId = "conversation-2",
            provenance = listOf(
                com.inspiredandroid.kai.data.MemoryProvenance("chat", "conversation-2", 1_700_000_000_123),
            ),
        )

        assertEquals(original, original.toPamEpisode().toMemoryEntry())
    }

    @Test
    fun timestampsAreKeptNotReset() {
        val ep = entry("k", createdAt = 1_700_000_000_000, updatedAt = 1_700_000_500_000).toPamEpisode()
        assertEquals(1_700_000_000_000, isoToMillis(ep.timestamp))
        assertEquals(1_700_000_500_000, isoToMillis(ep.updatedAt!!))
    }

    @Test
    fun missingUpdatedAtFallsBackToCreated() {
        val result = episode("k").toMemoryEntry()
        assertEquals(result.createdAt, result.updatedAt)
    }

    @Test
    fun unknownCategoryFallsBackToGeneral() {
        assertEquals(MemoryCategory.GENERAL, episode("k", category = "SOMETHING_NEW").toMemoryEntry().category)
    }

    @Test
    fun deletedEpisodesAreNotReturned() {
        val result = bundleToMemories(bundle(episode("a"), episode("b", deletedAt = now)))
        assertEquals(listOf("a"), result.map { it.key })
    }

    @Test
    fun malformedEpisodeIsSkippedNotFatal() {
        val result = bundleToMemories(bundle(episode("good"), episode("bad", timestamp = "not a date")))
        assertEquals(listOf("good"), result.map { it.key })
    }

    @Test
    fun episodeIdsUseKeyPrefix() {
        assertEquals("mem_k", memoriesToBundle(listOf(entry("k")), now).episodes.single().id)
    }

    @Test
    fun missingEntryBecomesTombstone() {
        val previous = bundle(episode("gone"), episode("kept"))
        val result = memoriesToBundle(listOf(entry("kept")), now, previous)
        assertEquals(now, result.episodes.first { it.key == "gone" }.deletedAt)
        assertEquals(null, result.episodes.first { it.key == "kept" }.deletedAt)
    }

    @Test
    fun existingTombstoneKeepsItsOriginalTime() {
        val previous = bundle(episode("gone", deletedAt = "2026-10-20T08:00:00Z"))
        val result = memoriesToBundle(emptyList(), now, previous)
        assertEquals("2026-10-20T08:00:00Z", result.episodes.single().deletedAt)
    }

    @Test
    fun mergePicksNewerEditEitherWayRound() {
        val older = memoriesToBundle(listOf(entry("k", "old", updatedAt = 1_700_000_000_000)), now)
        val newer = memoriesToBundle(listOf(entry("k", "new", updatedAt = 1_700_000_000_500)), now)
        assertEquals("new", mergeBundles(older, newer, now).episodes.single().content)
        assertEquals("new", mergeBundles(newer, older, now).episodes.single().content)
    }

    @Test
    fun differentMemoriesStayDifferent() {
        val result = memoriesToBundle(listOf(entry("a", "one"), entry("b", "two")), now)
        assertNotEquals(result.episodes[0].content, result.episodes[1].content)
    }

    @Test
    fun kaiSettingsBridgePreservesCreatedAndUpdatedTimestamps() {
        val settingsJson =
            """{"agent_memories":[{"id":"claim-1","key":"residence","content":"London","createdAt":1700000000123,"updatedAt":1700000900456}]}"""

        val bundle = kaiJsonToMemBundle(settingsJson, now)
        val episode = bundle.episodes.single()

        assertEquals(millisToIso(1_700_000_000_123), episode.timestamp)
        assertEquals(millisToIso(1_700_000_900_456), episode.updatedAt)
        val imported = memBundleToKaiJson(bundle, """{"version":1}""")
        val roundTripped = kaiJsonToMemBundle(imported, now).episodes.single()
        assertEquals(episode.timestamp, roundTripped.timestamp)
        assertEquals(episode.updatedAt, roundTripped.updatedAt)
    }

    @Test
    fun kaiSettingsBridgePreservesProviderSharingPermission() {
        val settingsJson =
            """{"agent_memories":[{"id":"share-1","key":"shareable","content":"explicit","createdAt":1700000000123,"updatedAt":1700000900456,"providerShareAllowed":true}]}"""

        val bundle = kaiJsonToMemBundle(settingsJson, now)
        assertEquals(true, bundle.episodes.single().providerShareAllowed)

        val exported = memBundleToKaiJson(bundle, """{"version":1}""")
        val restored = kaiJsonToMemBundle(exported, now)
        assertEquals(true, restored.episodes.single().providerShareAllowed)
    }

    @Test
    fun malformedSettingsMemoryDoesNotBecomeAnEmptyBundle() {
        assertFailsWith<IllegalArgumentException> {
            kaiJsonToMemBundle("""{"agent_memories":[{"content":"missing key"}]}""", now)
        }
        assertFailsWith<kotlinx.serialization.json.JsonDecodingException> {
            kaiJsonToMemBundle("""{"agent_memories":""", now)
        }
    }

    @Test
    fun memBundleJsonLinesExportCanBeImportedWithoutLosingEpisodeData() {
        val original = bundle(
            PamEpisode(
                id = "claim-1",
                timestamp = "2026-10-23T10:00:00Z",
                updatedAt = "2026-10-23T11:00:00Z",
                key = "residence",
                content = "Manchester",
                category = "GENERAL",
                source = "conversation",
                claimStatus = "REPORTED",
                factStatus = "UNKNOWN",
                resolutionStatus = "NEEDS_VERIFICATION",
            ),
        )

        val restored = MemBundle.parseEpisodes(original.toJsonLines())

        assertEquals(original.episodes, restored)
    }

    @Test
    fun memoryBundleImportRejectsDuplicateIdsAndInvalidTimestamps() {
        val duplicateIdEpisodes = listOf(episode("same"), episode("same"))
            .joinToString("\n") { kotlinx.serialization.json.Json.encodeToString(PamEpisode.serializer(), it) }
        assertFailsWith<IllegalArgumentException> {
            MemBundle.parseEpisodes(duplicateIdEpisodes)
        }

        val malformed = episode("bad", timestamp = "not a date")
        assertFailsWith<IllegalArgumentException> {
            MemBundle.parseEpisodes(kotlinx.serialization.json.Json.encodeToString(PamEpisode.serializer(), malformed))
        }
    }
}
