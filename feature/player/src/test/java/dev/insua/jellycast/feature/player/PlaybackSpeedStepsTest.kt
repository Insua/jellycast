package dev.insua.jellycast.feature.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 复审 Important 5:倍速档位表原来是 1.0–2.0,而设计文档 §3.5 写的是 **0.5x – 3.0x**。
 * 低于 1.0 的档位对"听剧"这个场景不是可选项——听不懂的段落要放慢,这是产品最基本的诉求之一。
 *
 * 循环逻辑抽成纯函数,离线可测(项目铁律 6)。
 */
class PlaybackSpeedStepsTest {

    @Test fun `档位覆盖设计文档要求的 0点5 到 3点0`() {
        assertEquals(0.5f, playbackSpeedSteps.first())
        assertEquals(3.0f, playbackSpeedSteps.last())
    }

    @Test fun `档位严格递增`() {
        assertTrue(playbackSpeedSteps.zipWithNext().all { (a, b) -> b > a }, playbackSpeedSteps.toString())
    }

    @Test fun `包含 1点0 这个默认档,用户能一路循环回正常速度`() {
        assertTrue(playbackSpeedSteps.contains(1.0f))
    }

    @Test fun `切换到严格大于当前值的下一档`() {
        assertEquals(0.75f, nextPlaybackSpeed(0.5f))
        assertEquals(1.0f, nextPlaybackSpeed(0.75f))
        assertEquals(1.25f, nextPlaybackSpeed(1.0f))
    }

    @Test fun `到顶后回到最慢档`() {
        assertEquals(0.5f, nextPlaybackSpeed(3.0f))
    }

    /** 浮点比较必须容差:偏好是 float 落盘再读回来的,1.25 可能变成 1.2500001。 */
    @Test fun `浮点误差不会让同一档被判成还没到达`() {
        assertEquals(1.5f, nextPlaybackSpeed(1.2500001f))
    }

    /** 落在档位之间的值(用户在设置页用滑块选了 1.3x)取下一个更大的档,不卡死。 */
    @Test fun `档位之间的值取下一个更大的档`() {
        assertEquals(1.5f, nextPlaybackSpeed(1.3f))
    }

    /** 超出档位表的值(历史遗留偏好)不崩,回到最慢档。 */
    @Test fun `超出范围的值回到最慢档`() {
        assertEquals(0.5f, nextPlaybackSpeed(9.0f))
    }
}
