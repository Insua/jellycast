package dev.insua.jellycast.network.mapper

import dev.insua.jellycast.model.MediaKind
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.UserDataDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MediaItemMapperTest {

    // ---- ticks 换算:1 tick = 100ns,毫秒 = ticks / 10_000,只在这一层做 ----

    @Test
    fun `runTimeTicks 换算为毫秒`() {
        val dto = BaseItemDto(id = "e1", name = "E1", type = "Episode", runTimeTicks = 123_456_780L)
        assertEquals(12_345L, dto.toMediaItem()?.runTimeMs)
    }

    @Test
    fun `runTimeTicks 缺失时 runTimeMs 为 null`() {
        val dto = BaseItemDto(id = "e1", name = "E1", type = "Episode", runTimeTicks = null)
        assertNull(dto.toMediaItem()?.runTimeMs)
    }

    @Test
    fun `PlaybackPositionTicks 换算为 resumePositionMs`() {
        val dto = BaseItemDto(
            id = "e1", name = "E1", type = "Episode",
            userData = UserDataDto(positionTicks = 987_650_000L),
        )
        assertEquals(98_765L, dto.toMediaItem()?.resumePositionMs)
    }

    @Test
    fun `没有 UserData 时 resumePositionMs 为 0`() {
        val dto = BaseItemDto(id = "e1", name = "E1", type = "Episode", userData = null)
        assertEquals(0L, dto.toMediaItem()?.resumePositionMs)
    }

    // ---- 季号/集号按 kind 分流:Season 用 IndexNumber(episodeNumber 字段),
    //      Episode 用 ParentIndexNumber(seasonNumber 字段)+ IndexNumber(episodeNumber 字段) ----

    @Test
    fun `Season 条目的季号来自 IndexNumber`() {
        val dto = BaseItemDto(id = "s2", name = "Season 2", type = "Season", episodeNumber = 2)
        val item = dto.toMediaItem()
        assertEquals(MediaKind.SEASON, item?.kind)
        assertEquals(2, item?.seasonNumber)
        assertNull(item?.episodeNumber)
    }

    @Test
    fun `Episode 条目的季号与集号分别来自 ParentIndexNumber 和 IndexNumber`() {
        val dto = BaseItemDto(
            id = "e5", name = "E5", type = "Episode",
            seasonNumber = 1, episodeNumber = 5, seriesName = "剧集",
        )
        val item = dto.toMediaItem()
        assertEquals(MediaKind.EPISODE, item?.kind)
        assertEquals(1, item?.seasonNumber)
        assertEquals(5, item?.episodeNumber)
        assertEquals("剧集", item?.seriesName)
    }

    // ---- 未知类型不得让整页崩溃,交给调用方 mapNotNull 跳过 ----

    @Test
    fun `未知 Type 返回 null 而不是抛异常`() {
        // Playlist 是 Jellyfin 真实存在但本项目浏览页不认识的类型,用来代表"陌生类型"这一档——
        // 不能再用 BoxSet 举例,BoxSet 从本次改动起是已知类型(合集),见下面的专项测试。
        val dto = BaseItemDto(id = "x1", name = "X", type = "Playlist")
        assertNull(dto.toMediaItem())
    }

    // ---- 合集(BoxSet):jq 核对 docs/jellyfin-openapi.json 的 BaseItemKind 枚举确认存在 ----

    @Test
    fun `BoxSet 映射为 MediaKind_COLLECTION`() {
        val dto = BaseItemDto(id = "box1", name = "神作合集", type = "BoxSet")
        val item = dto.toMediaItem()
        assertEquals(MediaKind.COLLECTION, item?.kind)
        assertEquals("神作合集", item?.name)
    }

    // ---- 封面 URL:GET Items 路径 id in path,maxWidth/tag query,大小写核对自 openapi.json ----

    @Test
    fun `有 imageTag 时拼出封面 URL`() {
        val dto = BaseItemDto(
            id = "m1", name = "Movie", type = "Movie",
            imageTags = mapOf("Primary" to "abc123"),
        )
        val url = dto.toMediaItem()!!.posterUrl("https://jelly.example.com:8920/")
        assertEquals("https://jelly.example.com:8920/Items/m1/Images/Primary?maxWidth=400&tag=abc123", url)
    }

    @Test
    fun `没有 imageTag 时封面 URL 为 null`() {
        val dto = BaseItemDto(id = "m1", name = "Movie", type = "Movie", imageTags = null)
        assertNull(dto.toMediaItem()!!.posterUrl("https://jelly.example.com"))
    }
}
