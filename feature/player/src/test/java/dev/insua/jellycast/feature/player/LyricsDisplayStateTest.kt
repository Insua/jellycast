package dev.insua.jellycast.feature.player

import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Finding 2:「空 timeline 显示占位、加载中显示 loading,两者都不能变成错误」——字幕铁律
 * (字幕失败不得影响播放)在 UI 层的落地。[LyricsDisplayState] 结构上只有三种取值,没有 ERROR
 * 分支,所以"不允许出现错误态"是编译期就成立的,不需要额外断言"没有抛错"。
 */
class LyricsDisplayStateTest {

    private val nonEmptyTimeline = SubtitleTimeline(listOf(SubtitleLine(1000, 3000, "A")))
    private val emptyTimeline = SubtitleTimeline(emptyList())

    @Test fun `加载中始终显示 loading,不管 timeline 是否已有内容`() {
        assertEquals(LyricsDisplayState.LOADING, lyricsDisplayState(isLoading = true, timeline = emptyTimeline))
        assertEquals(LyricsDisplayState.LOADING, lyricsDisplayState(isLoading = true, timeline = nonEmptyTimeline))
    }

    @Test fun `非加载态且 timeline 为空显示占位,不是错误`() {
        assertEquals(LyricsDisplayState.PLACEHOLDER, lyricsDisplayState(isLoading = false, timeline = emptyTimeline))
    }

    @Test fun `非加载态且 timeline 有内容显示歌词内容`() {
        assertEquals(LyricsDisplayState.CONTENT, lyricsDisplayState(isLoading = false, timeline = nonEmptyTimeline))
    }
}
