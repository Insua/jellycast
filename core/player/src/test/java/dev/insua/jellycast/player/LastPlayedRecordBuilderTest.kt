package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [buildLastPlayedRecord] 是「从当前条目 + 位置构造 LastPlayed」这段决策的纯函数版本——
 * `PlaybackService` 在 JVM 单测里造不出来(v6 的经验),把决策抽到这里才测得到。
 */
class LastPlayedRecordBuilderTest {

    @Test fun `剧集条目 标题是集名,副标题含剧名与季集编号`() {
        val item = MediaItem(
            id = "ep1",
            kind = MediaKind.EPISODE,
            name = "第一集",
            seriesName = "某剧",
            seasonNumber = 1,
            episodeNumber = 2,
            runTimeMs = 1_200_000L,
            imageTag = "tag123",
        )

        val record = buildLastPlayedRecord(item, positionMs = 30_000L, now = 999L)

        assertEquals("ep1", record.itemId)
        assertEquals(30_000L, record.positionMs)
        assertEquals("第一集", record.title)
        assertEquals("某剧 · S01E02", record.subtitle)
        assertEquals("tag123", record.imageTag)
        assertEquals(1_200_000L, record.runTimeMs)
        assertEquals(999L, record.updatedAt)
        assertEquals(MediaKind.EPISODE.name, record.kind)
    }

    @Test fun `电影条目 标题是片名,副标题不含季集编号`() {
        val item = MediaItem(
            id = "movie1",
            kind = MediaKind.MOVIE,
            name = "某电影",
            runTimeMs = 5_400_000L,
        )

        val record = buildLastPlayedRecord(item, positionMs = 10_000L, now = 1000L)

        assertEquals("某电影", record.title)
        assertTrue(!record.subtitle.contains("S0"), "电影不该带季集编号,实际副标题是「${record.subtitle}」")
    }

    // ---- 复审 Task 5 Important 2:kind 必须原样透传,不能固定成某一个值 ----
    // 根因:恢复迷你条那一侧(AppSessionViewModel.restoreLastPlayed)以前把 kind 硬编码成
    // EPISODE,声称"播放开始后会被覆盖"——这句话是假的。真正的修复是从这里开始:记录本身
    // 必须带着真实的 kind,读的那一侧才有东西可读。

    @Test fun `电影条目 kind 记录为 MOVIE,不会被写成 EPISODE`() {
        val item = MediaItem(id = "movie1", kind = MediaKind.MOVIE, name = "某电影")

        val record = buildLastPlayedRecord(item, positionMs = 0L, now = 1L)

        assertEquals(MediaKind.MOVIE.name, record.kind)
    }

    @Test fun `runTimeMs 为 null 时记录里也是 null,不伪造`() {
        val item = MediaItem(id = "ep1", kind = MediaKind.EPISODE, name = "第一集", runTimeMs = null)

        val record = buildLastPlayedRecord(item, positionMs = 5_000L, now = 1L)

        assertNull(record.runTimeMs)
    }

    @Test fun `位置为 0 时仍然产出记录`() {
        val item = MediaItem(id = "ep1", kind = MediaKind.EPISODE, name = "第一集")

        val record = buildLastPlayedRecord(item, positionMs = 0L, now = 1L)

        assertEquals(0L, record.positionMs)
        assertEquals("ep1", record.itemId)
    }
}
