package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 全项目此前从未给交给播放器的 `MediaItem` 设过 `MediaMetadata`,锁屏/通知/蓝牙/流体云因此全部
 * 空白。这里覆盖真正会被单测到的那一层——[MediaItem.toPlaybackDisplayMetadata] 是纯 Kotlin,
 * 不认识 Media3 的 `MediaMetadata`/`Uri`(那一步在 [ExoPlayerControl],未做 JVM 单测,理由见
 * [PlaybackDisplayMetadata] 的类注释)。
 */
class PlaybackMetadataMapperTest {

    @Test fun `剧集拿到标题、副标题(复用 displaySubtitle)、封面`() {
        val item = MediaItem(
            id = "ep1",
            kind = MediaKind.EPISODE,
            name = "第一集",
            seriesName = "某剧",
            seasonNumber = 1,
            episodeNumber = 2,
            imageTag = "tag123",
        )

        val metadata = item.toPlaybackDisplayMetadata("https://nas.example.com")

        assertEquals("第一集", metadata.title)
        assertEquals("某剧 · S01E02", metadata.subtitle)
        assertEquals(
            "https://nas.example.com/Items/ep1/Images/Primary?maxWidth=400&tag=tag123",
            metadata.artworkUri,
        )
    }

    @Test fun `没有 imageTag 时 artworkUri 是 null,不是空字符串`() {
        val item = MediaItem(id = "ep1", kind = MediaKind.EPISODE, name = "第一集", imageTag = null)

        val metadata = item.toPlaybackDisplayMetadata("https://nas.example.com")

        assertNull(metadata.artworkUri)
    }

    @Test fun `baseUrl 为空(尚未选路成功离线)时不拼封面 URL,直接给 null`() {
        val item = MediaItem(id = "ep1", kind = MediaKind.EPISODE, name = "第一集", imageTag = "tag123")

        val metadata = item.toPlaybackDisplayMetadata("")

        assertNull(metadata.artworkUri)
    }

    @Test fun `电影没有剧名时副标题是空字符串,不留下悬空的分隔符`() {
        val item = MediaItem(id = "movie1", kind = MediaKind.MOVIE, name = "某电影", seriesName = null)

        val metadata = item.toPlaybackDisplayMetadata("https://nas.example.com")

        assertEquals("某电影", metadata.title)
        assertEquals("", metadata.subtitle)
        assertFalse(metadata.subtitle.contains("·"), "电影没有剧名不该留下 \" · \" 这种悬空分隔符")
    }
}
