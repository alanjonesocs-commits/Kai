package com.inspiredandroid.kai.nia.memory

import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private const val ID_PREFIX = "mem_"

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

/**
 * Snapshots Kai's memories as a bundle. Kai has no tombstones, so pass the previous
 * bundle: anything that was in it but is gone now is recorded as deleted at [nowIso].
 */
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

/** Live memories from a bundle. Deleted and malformed episodes are skipped, never fatal. */
fun bundleToMemories(bundle: MemBundle): List<MemoryEntry> = bundle.episodes
    .filter { it.deletedAt == null }
    .mapNotNull { runCatching { it.toMemoryEntry() }.getOrNull() }
    .sortedWith(compareBy({ it.createdAt }, { it.key }))
