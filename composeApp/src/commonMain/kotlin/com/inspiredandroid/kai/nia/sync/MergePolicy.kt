package com.inspiredandroid.kai.nia.sync

import com.inspiredandroid.kai.nia.memory.MemBundle
import com.inspiredandroid.kai.nia.memory.PamEpisode
import com.inspiredandroid.kai.nia.memory.PamManifest

// Pads fractional seconds so "…:00Z" and "…:00.5Z" compare correctly as text (UTC only).
internal fun normaliseIso(ts: String): String {
    val core = ts.removeSuffix("Z")
    val dot = core.indexOf('.')
    return if (dot < 0) {
        "$core.000000000Z"
    } else {
        core.substring(0, dot + 1) + core.substring(dot + 1).padEnd(9, '0') + "Z"
    }
}

private fun effectiveTime(ep: PamEpisode): String = normaliseIso(ep.updatedAt ?: ep.timestamp)

private fun pickWinner(a: PamEpisode, b: PamEpisode): PamEpisode {
    val ta = effectiveTime(a)
    val tb = effectiveTime(b)
    return when {
        ta != tb -> if (ta > tb) a else b
        a.hitCount != b.hitCount -> if (a.hitCount > b.hitCount) a else b
        else -> if (a.toString() >= b.toString()) a else b
    }
}

fun mergeBundles(local: MemBundle, remote: MemBundle, nowIso: String): MemBundle {
    val localById = local.episodes.associateBy { it.id }
    val remoteById = remote.episodes.associateBy { it.id }
    val merged = (localById.keys + remoteById.keys).sorted().map { id ->
        val a = localById[id]
        val b = remoteById[id]
        when {
            a == null -> b!!

            b == null -> a

            else -> pickWinner(a, b).copy(
                deletedAt = listOfNotNull(a.deletedAt, b.deletedAt).minOrNull(),
            )
        }
    }
    val manifest = PamManifest(
        bundleId = "merged_$nowIso",
        createdAt = nowIso,
        schemaVersion = maxOf(local.manifest.schemaVersion, remote.manifest.schemaVersion),
        checksum = "",
    )
    return MemBundle(manifest, merged)
}
