package dev.insua.jellycast.player

import androidx.media3.common.C
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 铁律验证:视频轨必须被全局禁用,且不需要真机/Context 就能断言 ——
 * `DefaultTrackSelector.Parameters.Builder()` 有无参构造函数,构建参数是纯数据操作,不触碰任何
 * Android 运行时 API(Locale/Display 等),可以直接在 JVM 单测里跑,不需要 Robolectric。
 *
 * `createAudioOnlyPlayer(Context): ExoPlayer` 本身需要真实 Android Context 才能实例化
 * ExoPlayer/DefaultTrackSelector,未做 JVM 单测——取舍说明见任务报告。
 */
class JellyCastPlayerFactoryTest {

    @Test fun `视频轨被全局禁用`() {
        val params = audioOnlyTrackSelectionParameters()
        assertTrue(params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO))
    }

    @Test fun `音频轨没有被禁用`() {
        val params = audioOnlyTrackSelectionParameters()
        assertFalse(params.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
    }
}
