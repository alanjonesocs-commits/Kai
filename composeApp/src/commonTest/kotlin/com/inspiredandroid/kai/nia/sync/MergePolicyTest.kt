package com.inspiredandroid.kai.nia.sync

import com.inspiredandroid.kai.nia.memory.MemBundle
import com.inspiredandroid.kai.nia.memory.PamEpisode
import com.inspiredandroid.kai.nia.memory.PamManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MergePolicyTest {
    private val now = "2026-10-23T21:00:00Z"

    private fun ep(
        id: String,
        timestamp: String = "2026-10-23T10:00:00Z",
        updatedAt: String? = null,
        content: String = "c",
        hitCount: Int = 0,
        deletedAt: String? = null,
    ) = PamEpisode(
        id = id,
        timestamp = timestamp,
        updatedAt = updatedAt,
        key = id,
        content = content,
        hitCount = hitCount,
        deletedAt = deletedAt,
    )

    private fun bundle(vararg eps: PamEpisode, version: Int = 1) = MemBundle(PamManifest("b", now, version, ""), eps.toList())

    private fun merged(a: MemBundle, b: MemBundle) = mergeBundles(a, b, now)

    @Test
    fun keepsEpisodesFoundOnOnlyOneSide() {
        val result = merged(bundle(ep("a")), bundle(ep("b")))
        assertEquals(listOf("a", "b"), result.episodes.map { it.id })
    }

    @Test
    fun newerUpdateWins() {
        val local = bundle(ep("1", updatedAt = "2026-10-23T11:00:00Z", content = "old"))
        val remote = bundle(ep("1", updatedAt = "2026-10-23T12:00:00Z", content = "new"))
        assertEquals("new", merged(local, remote).episodes.single().content)
    }

    @Test
    fun updatedAtBeatsOriginalTimestamp() {
        val local = bundle(ep("1", timestamp = "2026-10-23T09:00:00Z", updatedAt = "2026-10-23T15:00:00Z", content = "edited"))
        val remote = bundle(ep("1", timestamp = "2026-10-23T12:00:00Z", content = "created"))
        assertEquals("edited", merged(local, remote).episodes.single().content)
    }

    @Test
    fun mixedFractionalTimestampsCompareCorrectly() {
        val local = bundle(ep("1", updatedAt = "2026-10-23T10:00:00Z", content = "old"))
        val remote = bundle(ep("1", updatedAt = "2026-10-23T10:00:00.500Z", content = "new"))
        assertEquals("new", merged(local, remote).episodes.single().content)
    }

    @Test
    fun normaliseIsoMakesFormatsComparable() {
        assertTrue(normaliseIso("2026-10-23T10:00:00Z") < normaliseIso("2026-10-23T10:00:00.5Z"))
        assertEquals(normaliseIso("2026-10-23T10:00:00Z"), normaliseIso("2026-10-23T10:00:00.000Z"))
    }

    @Test
    fun higherHitCountWinsWhenTimesTie() {
        val local = bundle(ep("1", content = "low", hitCount = 1))
        val remote = bundle(ep("1", content = "high", hitCount = 5))
        assertEquals("high", merged(local, remote).episodes.single().content)
    }

    @Test
    fun mergeGivesSameResultEitherWayRound() {
        val a = bundle(ep("1", content = "x"), ep("2", content = "same time"))
        val b = bundle(ep("1", content = "y"), ep("3"))
        assertEquals(merged(a, b).episodes, merged(b, a).episodes)
    }

    @Test
    fun deletionStaysEvenIfOtherSideEditedLater() {
        val local = bundle(ep("1", updatedAt = "2026-10-23T12:00:00Z", content = "edited"))
        val remote = bundle(ep("1", deletedAt = "2026-10-23T11:00:00Z"))
        val result = merged(local, remote).episodes.single()
        assertEquals("2026-10-23T11:00:00Z", result.deletedAt)
        assertEquals("edited", result.content)
    }

    @Test
    fun earliestDeletionTimeIsKept() {
        val local = bundle(ep("1", deletedAt = "2026-10-23T11:00:00Z"))
        val remote = bundle(ep("1", deletedAt = "2026-10-23T09:00:00Z"))
        assertEquals("2026-10-23T09:00:00Z", merged(local, remote).episodes.single().deletedAt)
    }

    @Test
    fun resultIsSortedById() {
        val result = merged(bundle(ep("c"), ep("a")), bundle(ep("b")))
        assertEquals(listOf("a", "b", "c"), result.episodes.map { it.id })
    }

    @Test
    fun mergingTheSameBundleTwiceChangesNothing() {
        val a = bundle(ep("1", content = "x"), ep("2", deletedAt = "2026-10-23T11:00:00Z"))
        assertEquals(a.episodes, merged(a, a).episodes)
    }

    @Test
    fun highestSchemaVersionIsKept() {
        val result = merged(bundle(ep("1"), version = 1), bundle(ep("1"), version = 3))
        assertEquals(3, result.manifest.schemaVersion)
    }
}
