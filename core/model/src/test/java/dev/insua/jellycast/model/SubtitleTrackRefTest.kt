package dev.insua.jellycast.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 缺陷 1(设计文档 §3.3):外挂弹幕轨被当成默认字幕选中。识别信号(已用 jq 核对
 * docs/jellyfin-openapi.json 的 MediaStream.properties):`IsExternal` 为 true 且标题含
 * danmu/弹幕 —— `jq '.components.schemas.MediaStream.properties | keys[] | select(test("external";"i"))'`
 * 命中 `IsExternal`(boolean)。
 */
class SubtitleTrackRefTest {

    @Test fun `外挂且标题含中文弹幕字样判定为弹幕轨`() {
        val track = SubtitleTrackRef(index = 0, language = "chi", displayName = "弹幕[BiliBili]", isTextBased = true, isExternal = true)
        assertTrue(track.isLikelyDanmaku)
    }

    @Test fun `外挂且标题含英文danmaku字样判定为弹幕轨`() {
        val track = SubtitleTrackRef(index = 0, language = null, displayName = "danmaku.xml", isTextBased = true, isExternal = true)
        assertTrue(track.isLikelyDanmaku)
    }

    @Test fun `外挂但标题不含弹幕字样不是弹幕轨`() {
        val track = SubtitleTrackRef(index = 4, language = "chi", displayName = "简体中文", isTextBased = true, isExternal = true)
        assertFalse(track.isLikelyDanmaku)
    }

    @Test fun `内嵌轨即使标题含弹幕字样也不判定为弹幕轨`() {
        // IsExternal 是必要条件之一 —— 内嵌字幕轨不可能是外挂弹幕文件。
        val track = SubtitleTrackRef(index = 2, language = "chi", displayName = "弹幕体验版", isTextBased = true, isExternal = false)
        assertFalse(track.isLikelyDanmaku)
    }

    @Test fun `普通外挂字幕轨不是弹幕轨`() {
        val track = SubtitleTrackRef(index = 4, language = "chi", displayName = "简体中文.srt", isTextBased = true, isExternal = true)
        assertFalse(track.isLikelyDanmaku)
    }
}
