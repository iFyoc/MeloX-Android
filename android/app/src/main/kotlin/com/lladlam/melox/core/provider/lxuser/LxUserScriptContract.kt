package com.lladlam.melox.core.provider.lxuser

import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import java.util.Locale

/**
 * Builds the flat musicInfo object that real LX hosts hand to user scripts.
 *
 * lx-music-mobile converts its internal nested model at the script boundary
 * (`toOldMusicInfo`, src/utils/index.ts): source song ids, album and artwork
 * fields sit at the TOP level — e.g. tx `strMediaMid`/`albumMid`/numeric
 * `songId`, kg `hash`/`albumId`, mg `copyrightId`, `songmid = meta.songId`,
 * `img`, `types`/`_types`, `typeUrl` — and there is no `meta` key. Official
 * sources destructure these directly (`musicUrl({ songmid, strMediaMid })`),
 * so every flat-contract field MUST be present here.
 *
 * MeloX-only extras (`meta`, `id`, `title`, `duration`, ...) are kept as an
 * additive superset but never replace the flat contract fields.
 */
internal fun buildLxMusicInfo(
    track: MusicTrack,
    sourceCode: String?,
    quality: String,
): Map<String, Any?> {
    val durationSeconds = track.durationMs?.coerceAtLeast(0L)?.div(1_000L)
    val interval = durationSeconds?.let { seconds ->
        String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
    val songId = track.id.value
    val albumId = track.album?.id?.value.orEmpty()
    val qualityEntry = listOf(mapOf("type" to quality, "size" to null))
    val qualityMap = mapOf(quality to mapOf<String, Any?>("size" to null))

    // Per-source flat fields, mirroring toOldMusicInfo's switch blocks.
    val sourceFields: Map<String, Any?> = when (val provider = track.providerMetadata) {
        is ProviderTrackMetadata.QQMusic -> mapOf(
            // tx scripts build the vkey filename from strMediaMid and some
            // read the numeric songId / albumMid separately.
            "songId" to (provider.numericSongId ?: songId),
            "strMediaMid" to (provider.mediaMid ?: provider.songMid),
            "albumMid" to (track.album?.id?.value ?: ""),
        )
        is ProviderTrackMetadata.Kugou -> mapOf(
            "hash" to provider.hash,
            "albumId" to (provider.albumId ?: albumId),
        )
        // mg's contract field is copyrightId; MeloX has no Migu provider yet,
        // so a mg probe only ever receives the generic songmid.
        is ProviderTrackMetadata.Kuwo -> mapOf("songId" to provider.mid)
        else -> emptyMap()
    }

    val nestedMeta = buildMap {
        put("songId", songId)
        put("albumName", track.album?.name ?: "")
        put("picUrl", track.artworkUrl)
        put("qualitys", qualityEntry)
        put("_qualitys", qualityMap)
        put("albumId", sourceFields["albumId"] ?: albumId)
        when (val provider = track.providerMetadata) {
            is ProviderTrackMetadata.QQMusic -> {
                put("strMediaMid", provider.mediaMid ?: provider.songMid)
                put("albumMid", track.album?.id?.value ?: "")
                put("id", provider.numericSongId)
            }
            is ProviderTrackMetadata.Kugou -> put("hash", provider.hash)
            is ProviderTrackMetadata.Kuwo -> put("songId", provider.mid)
            else -> Unit
        }
    }

    return mapOf(
        // Flat LX contract fields (toOldMusicInfo).
        "name" to track.title,
        "singer" to track.artistText,
        "source" to sourceCode,
        "songmid" to songId,
        "interval" to interval,
        "albumName" to (track.album?.name ?: ""),
        "img" to (track.artworkUrl ?: ""),
        "typeUrl" to emptyMap<String, Any?>(),
        "albumId" to (sourceFields["albumId"] ?: albumId),
        "types" to qualityEntry,
        "_types" to qualityMap,
    ) + sourceFields + mapOf(
        // MeloX/compat superset; not part of the flat LX contract.
        "id" to songId,
        "mid" to songId,
        "artist" to track.artistText,
        "title" to track.title,
        "duration" to durationSeconds,
        "durationMs" to track.durationMs,
        "quality" to quality,
        "meta" to nestedMeta,
    )
}

/**
 * Lower-cased header names with comma-joined values, matching the
 * `Record<string, string>` shape LX scripts receive from the fetch layer
 * (`resp.headers.cookie` must be a string, never a list).
 */
internal fun flattenLxResponseHeaders(headers: Map<String, List<String>>): Map<String, String> {
    val flattened = LinkedHashMap<String, String>()
    headers.forEach { (name, values) ->
        if (values.isEmpty()) return@forEach
        val key = name.lowercase(Locale.ROOT)
        val joined = values.joinToString(", ")
        flattened[key] = flattened[key]?.let { "$it, $joined" } ?: joined
    }
    return flattened
}
