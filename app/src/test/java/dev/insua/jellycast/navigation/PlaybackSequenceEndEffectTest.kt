package dev.insua.jellycast.navigation

import dev.insua.jellycast.player.PlaybackSequenceEnd
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 「播完了怎么办」这个决策的落点。
 *
 * `:core:player` **不认识导航**(设计文档 §5 的边界原则):它只发出"发生了什么"
 * ([PlaybackSequenceEnd]),"回哪个页面、弹什么提示"全部在导航层这一侧决定。
 * 决策本身是纯函数,离线可单测(项目铁律 6),ViewModel 只负责把它接到 [PlaybackSequenceEnd] 上。
 */
class PlaybackSequenceEndEffectTest {

    @Test fun `没有播放结束信号时什么都不做`() {
        assertNull(playbackSequenceEndEffect(null))
    }

    @Test fun `整部剧播完回首页并提示已播放完`() {
        val effect = playbackSequenceEndEffect(PlaybackSequenceEnd.SERIES_COMPLETED)
        assertEquals(true, effect?.returnToHome)
        assertEquals(SERIES_COMPLETED_MESSAGE, effect?.message)
    }

    /** 电影播完直接回首页,不必额外提示——用户自己点开的单部电影,播完了是意料之中。 */
    @Test fun `单条目播完回首页但不提示`() {
        val effect = playbackSequenceEndEffect(PlaybackSequenceEnd.ITEM_COMPLETED)
        assertEquals(true, effect?.returnToHome)
        assertNull(effect?.message)
    }
}
