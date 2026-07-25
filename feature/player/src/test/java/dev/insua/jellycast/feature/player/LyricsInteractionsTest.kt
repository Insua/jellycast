package dev.insua.jellycast.feature.player

import dev.insua.jellycast.model.SubtitleLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Finding 2:点击歌词行应该把该行的 [SubtitleLine.startMs] 交给 onSeek。原实现把这行逻辑
 * 内联在 `LyricsLineRow` 的 `clickable` lambda 里,完全没有测试覆盖(Compose `clickable`
 * 手势本身要不要真的触发不属于纯逻辑,留给 androidTest/人工验收——见任务报告);这里抽出来的
 * 是"点击第几行应该 seek 到哪个时间点"这份映射逻辑本身,是这个模块真正新增、此前完全没被
 * 单测覆盖的部分。
 */
class LyricsInteractionsTest {

    @Test fun `点击歌词行时把该行 startMs 传给 onSeek`() {
        var seekTarget: Long? = null
        val line = SubtitleLine(startMs = 4000, endMs = 6000, text = "B")

        onLyricsLineClicked(line) { seekTarget = it }

        assertEquals(4000L, seekTarget)
    }

    @Test fun `不同行点击各自 seek 到自己的 startMs`() {
        val seeks = mutableListOf<Long>()
        val lineA = SubtitleLine(startMs = 1000, endMs = 3000, text = "A")
        val lineC = SubtitleLine(startMs = 10000, endMs = 12000, text = "C")

        onLyricsLineClicked(lineA) { seeks += it }
        onLyricsLineClicked(lineC) { seeks += it }

        assertEquals(listOf(1000L, 10000L), seeks)
    }
}
