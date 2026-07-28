package dev.insua.jellycast.feature.home

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// ---- 修正:继续收听卡片的进度条 —— 纯逻辑部分 ----
// resumeProgressFraction 是"听了多少"的换算,输入输出全是毫秒/Float,不接触 ticks、
// 不接触 Android,可以完全离线单测(CLAUDE.md 铁律 6)。

class HomeScreenTest {

    private fun item(resumePositionMs: Long, runTimeMs: Long?) = MediaItem(
        id = "1",
        kind = MediaKind.EPISODE,
        name = "第一集",
        runTimeMs = runTimeMs,
        resumePositionMs = resumePositionMs,
    )

    @Test
    fun `正常进度返回听完比例`() {
        val fraction = item(resumePositionMs = 300_000, runTimeMs = 1_200_000).resumeProgressFraction()

        assertEquals(0.25f, fraction)
    }

    @Test
    fun `runTimeMs为null时不显示进度`() {
        val fraction = item(resumePositionMs = 300_000, runTimeMs = null).resumeProgressFraction()

        assertNull(fraction, "没有总时长就无法算比例,不能除以 null")
    }

    @Test
    fun `runTimeMs为0时不显示进度`() {
        val fraction = item(resumePositionMs = 300_000, runTimeMs = 0).resumeProgressFraction()

        assertNull(fraction, "总时长为 0 会除零,必须当作没有进度处理")
    }

    @Test
    fun `resumePositionMs为0时不显示进度`() {
        val fraction = item(resumePositionMs = 0, runTimeMs = 1_200_000).resumeProgressFraction()

        assertNull(fraction, "一秒都没听过的条目不算'进行中',不应该出现进度条")
    }

    @Test
    fun `resumePositionMs超过runTimeMs时钳制为1`() {
        val fraction = item(resumePositionMs = 1_500_000, runTimeMs = 1_200_000).resumeProgressFraction()

        assertEquals(1f, fraction, "服务端上报的位置理论上不应该超过总时长,但客户端必须钳制,不能画出超过 100% 的进度条")
    }

    // ---- Change 2:未看数角标 —— 纯逻辑部分 ----
    // unplayedBadgeCountOrNull 决定"要不要画角标",输入输出全是 Int?,不接触 Compose,可离线单测。

    private fun episodeWithUnplayed(unplayedItemCount: Int?) = MediaItem(
        id = "1",
        kind = MediaKind.EPISODE,
        name = "第一集",
        unplayedItemCount = unplayedItemCount,
    )

    @Test
    fun `未看数大于0时显示角标`() {
        assertEquals(5, episodeWithUnplayed(5).unplayedBadgeCountOrNull())
    }

    @Test
    fun `未看数为0时不显示角标`() {
        assertNull(episodeWithUnplayed(0).unplayedBadgeCountOrNull(), "写着'0'的角标毫无意义,不该画出来")
    }

    @Test
    fun `未看数为null时不显示角标`() {
        assertNull(episodeWithUnplayed(null).unplayedBadgeCountOrNull(), "服务端没给这个字段时不该编造一个角标")
    }

    // ---- 修正 §3.2:"没有上一集" —— 分区点击必须传出整个分区作为播放队列 ----
    // 根因:导航层以前固定传 listOf(item),PlayQueue 长度恒为 1,hasPrevious() 恒为 false
    // (core/player 的 PlayQueueTest 已经证明:队列长度够、起点不在队首,hasPrevious() 才会是
    // true——这里只需要证明"传出去的确实是整个分区",不必在 :feature:home 里重新引入
    // :core:player 依赖去重复验证 PlayQueue 本身的机制)。

    private fun item(id: String, name: String = id) = MediaItem(id = id, kind = MediaKind.EPISODE, name = name)

    @Test
    fun `点击继续收听分区第二项_队列是整个分区且起点是被点的那一项`() {
        val itemA = item("a"); val itemB = item("b"); val itemC = item("c")
        val section = HomeSection(HomeSectionKind.RESUME, "继续收听", listOf(itemA, itemB, itemC))
        val tapped = section.items[1]

        val queue = section.playQueueFor(tapped)

        assertEquals(
            listOf(itemA, itemB, itemC),
            queue,
            "队列必须是整个分区,不能退化成只有被点的这一项——那样上一集/下一集恒不可用",
        )
        val startIndex = queue.indexOfFirst { it.id == tapped.id }
        assertEquals(1, startIndex)
        assertTrue(startIndex > 0, "起点不在队首意味着 PlayQueue.hasPrevious() 可用(见 PlayQueueTest)")
    }

    @Test
    fun `点击下一集分区任意一项_队列同样是整个分区`() {
        val items = listOf(item("e1"), item("e2"), item("e3"), item("e4"))
        val section = HomeSection(HomeSectionKind.NEXT_UP, "下一集", items)

        val queue = section.playQueueFor(items[2])

        assertEquals(items, queue)
    }

    @Test
    fun `点击最近添加分组内的条目_队列是该分组的完整列表`() {
        val items = listOf(item("r1"), item("r2"), item("r3"))
        val group = RecentlyAddedGroup(libraryId = "lib-1", libraryName = "电视剧", items = items)

        val queue = group.playQueueFor(items[1])

        assertEquals(items, queue)
        assertEquals(1, queue.indexOfFirst { it.id == "r2" })
    }
}
