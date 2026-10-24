package com.inspiredandroid.kai.nia.memory

import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import com.inspiredandroid.kai.nia.sync.mergeBundles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
}
