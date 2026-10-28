package com.lladlam.melox.core.provider.lxuser

import com.lladlam.melox.core.music.model.MusicAlbumRef
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the flat musicInfo contract to lx-music-mobile's `toOldMusicInfo`
 * shape: official sources destructure top-level `songmid`/`strMediaMid`/
 * `hash`/`albumId`/`img`/`types`, so a regression here breaks real scripts.
 */
class LxUserScriptContractTest {

    @Test
    fun `qq music info carries flat tx contract fields`() {
        val track = MusicTrack(
            id = MusicResourceId(MusicSource.QQMusic, "004CyPQR03h5vU"),
            title = "晴天",
            artists = listOf(MusicArtistRef(name = "周杰伦")),
            album = MusicAlbumRef(id = MusicResourceId(MusicSource.QQMusic, "002pUZT93VpqJm"), name = "叶惠美"),
            artworkUrl = "https://img.example/cover.jpg",
            durationMs = 269_000,
            providerMetadata = ProviderTrackMetadata.QQMusic(
                songMid = "004CyPQR03h5vU",
                mediaMid = "0035zfnZ1LwQPU",
                numericSongId = 97770L,
            ),
        )

        val info = buildLxMusicInfo(track, "tx", "flac")

        assertEquals("004CyPQR03h5vU", info["songmid"])
        assertEquals("0035zfnZ1LwQPU", info["strMediaMid"])
        assertEquals(97770L, info["songId"])
        assertEquals("002pUZT93VpqJm", info["albumMid"])
        assertEquals("002pUZT93VpqJm", info["albumId"])
        assertEquals("叶惠美", info["albumName"])
        assertEquals("https://img.example/cover.jpg", info["img"])
        assertEquals("04:29", info["interval"])
        assertEquals("tx", info["source"])
        assertEquals("晴天", info["name"])
        assertEquals("周杰伦", info["singer"])
        assertTrue(info["typeUrl"] is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val types = info["types"] as List<Map<String, Any?>>
        assertEquals(listOf(mapOf<String, Any?>("type" to "flac", "size" to null)), types)
        @Suppress("UNCHECKED_CAST")
        val qualityMap = info["_types"] as Map<String, *>
        assertTrue(qualityMap.containsKey("flac"))
        // The nested-model extras stay available as a superset.
        @Suppress("UNCHECKED_CAST")
        val meta = info["meta"] as Map<String, Any?>
        assertEquals("0035zfnZ1LwQPU", meta["strMediaMid"])
        assertEquals(97770L, meta["id"])
    }

    @Test
    fun `qq media mid falls back to song mid`() {
        val track = MusicTrack(
            id = MusicResourceId(MusicSource.QQMusic, "004CyPQR03h5vU"),
            title = "晴天",
            artists = listOf(MusicArtistRef(name = "周杰伦")),
            providerMetadata = ProviderTrackMetadata.QQMusic(songMid = "004CyPQR03h5vU"),
        )

        val info = buildLxMusicInfo(track, "tx", "320k")

        assertEquals("004CyPQR03h5vU", info["strMediaMid"])
        assertEquals("004CyPQR03h5vU", info["songId"])
        assertEquals("", info["albumMid"])
        assertEquals("", info["albumId"])
    }

    @Test
    fun `kugou music info exposes hash and albumId at top level`() {
        val hash = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b"
        val track = MusicTrack(
            id = MusicResourceId(MusicSource.Kugou, hash),
            title = "测试",
            artists = listOf(MusicArtistRef(name = "歌手")),
            album = MusicAlbumRef(id = null, name = "专辑"),
            durationMs = 65_000,
            providerMetadata = ProviderTrackMetadata.Kugou(hash = hash, albumId = "88877"),
        )

        val info = buildLxMusicInfo(track, "kg", "128k")

        assertEquals(hash, info["hash"])
        assertEquals("88877", info["albumId"])
        assertEquals(hash, info["songmid"])
        assertEquals("01:05", info["interval"])
        @Suppress("UNCHECKED_CAST")
        val meta = info["meta"] as Map<String, Any?>
        assertEquals(hash, meta["hash"])
        assertEquals("88877", meta["albumId"])
    }

    @Test
    fun `kuwo and netease keep source ids in songmid`() {
        val kuwo = MusicTrack(
            id = MusicResourceId(MusicSource.Kuwo, "91734960"),
            title = "kr",
            artists = listOf(MusicArtistRef(name = "ka")),
            providerMetadata = ProviderTrackMetadata.Kuwo(mid = 91734960L),
        )
        val kuwoInfo = buildLxMusicInfo(kuwo, "kw", "128k")
        assertEquals("91734960", kuwoInfo["songmid"])
        assertEquals(91734960L, kuwoInfo["songId"])
        assertEquals("kw", kuwoInfo["source"])

        val netease = MusicTrack(
            id = MusicResourceId(MusicSource.Netease, "186016"),
            title = "nr",
            artists = listOf(MusicArtistRef(name = "na")),
            providerMetadata = ProviderTrackMetadata.Netease(numericId = 186016L),
        )
        val neteaseInfo = buildLxMusicInfo(netease, "wy", "320k")
        assertEquals("186016", neteaseInfo["songmid"])
        assertEquals("wy", neteaseInfo["source"])
        assertEquals("", neteaseInfo["img"])
        assertFalse(neteaseInfo.containsKey("strMediaMid"))
    }

    @Test
    fun `response headers flatten to fetch style strings`() {
        val flattened = flattenLxResponseHeaders(
            mapOf(
                "Set-Cookie" to listOf("a=1", "b=2"),
                "Content-Type" to listOf("application/json"),
                "X-Empty" to emptyList(),
            ),
        )

        assertEquals(mapOf("set-cookie" to "a=1, b=2", "content-type" to "application/json"), flattened)
        assertFalse(flattened.containsKey("x-empty"))
    }
}
