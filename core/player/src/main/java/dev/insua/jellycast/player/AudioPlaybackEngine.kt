package dev.insua.jellycast.player

import dev.insua.jellycast.model.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "给个 itemId/userId/起始位置,换一个可播放源"这件事的最小抽象。[AudioPlaybackEngine] 依赖它,
 * 而不是具体的 [PlaybackSourceResolver],这样单测编排逻辑时可以完全绕开 JellyfinApi / StreamProbe。
 * 用 [PlaybackSourceResolver.asProvider] 把 Task 8 的 resolver 接到这里,不改动其公开签名/默认参数
 * (resolver 已有测试直接依赖那个默认参数,不能动)。
 */
fun interface PlaybackSourceProvider {
    suspend fun resolve(itemId: String, userId: String, startPositionMs: Long): PlaybackSource
}

fun PlaybackSourceResolver.asProvider(): PlaybackSourceProvider =
    PlaybackSourceProvider { itemId, userId, startPositionMs -> resolve(itemId, userId, startPositionMs) }

/**
 * ExoPlayer 操作的最小接口。只暴露"换一个 URL 重新准备播放"和"释放",刻意不提供 seekTo ——
 * 这样 [AudioPlaybackEngine] 在结构上就不可能调用 player.seekTo。生产实现见 [ExoPlayerControl]。
 */
interface PlayerControl {
    fun setMediaItemAndPrepare(url: String)
    fun release()
}

sealed interface PlaybackEngineState {
    data object Idle : PlaybackEngineState
    data class Ready(val source: PlaybackSource) : PlaybackEngineState

    /** 对应设计文档 §8:「该条目无法播放」。由 Task 8 契约里 resolve() 的异常转换而来,绝不上抛。 */
    data class Error(val itemId: String, val message: String = "该条目无法播放") : PlaybackEngineState
}

interface AudioPlaybackEngine {
    val state: StateFlow<PlaybackEngineState>
    suspend fun play(itemId: String, userId: String, startPositionMs: Long = 0L)
    suspend fun seekTo(positionMs: Long)
    fun release()
}

/**
 * ⚠️ 为什么 seek 不调 `player.seekTo`:
 *
 * Spike 实测(docs/superpowers/specs/2026-07-25-spike-results.md)Jellyfin 转码音频流响应头是
 * `Accept-Ranges: none`——带 Range 头的请求也返回 HTTP 200 而不是 206,服务端根本不支持按字节
 * 区间取流。`ExoPlayer.seekTo()` 依赖底层数据源能响应 Range 请求做字节级跳转,在这种流上不可靠
 * (会卡住,或者从头播放)。
 *
 * 因此这里把 seek 实现成:带着新的 `startPositionMs` 重新调用 [PlaybackSourceProvider.resolve]
 * 换一个新 URL(服务端用 `startTimeTicks` 参数从目标位置开始转码),再 setMediaItem + prepare。
 *
 * **不要把这里"优化"回 `player.seekTo` —— 那样在真实服务器上会直接踩坑。**
 * [PlayerControl] 接口本身就没有暴露 seekTo,结构上杜绝了误用。
 *
 * resolve() 抛出的异常(PlaybackInfo 无媒体源 / 网络或认证失败,见 Task 8 契约)在这里被捕获,
 * 转成 [PlaybackEngineState.Error],绝不向上抛出、绝不崩掉调用方(Service/播放会话)。
 */
class AudioPlaybackEngineImpl(
    private val sourceProvider: PlaybackSourceProvider,
    private val playerControl: PlayerControl,
) : AudioPlaybackEngine {

    private val _state = MutableStateFlow<PlaybackEngineState>(PlaybackEngineState.Idle)
    override val state: StateFlow<PlaybackEngineState> = _state.asStateFlow()

    private var currentItemId: String? = null
    private var currentUserId: String? = null

    override suspend fun play(itemId: String, userId: String, startPositionMs: Long) {
        currentItemId = itemId
        currentUserId = userId
        resolveAndPrepare(itemId, userId, startPositionMs)
    }

    override suspend fun seekTo(positionMs: Long) {
        val itemId = currentItemId ?: return
        val userId = currentUserId ?: return
        // 见类注释:这里刻意重新 resolve + prepare,不是 player.seekTo。
        resolveAndPrepare(itemId, userId, positionMs)
    }

    private suspend fun resolveAndPrepare(itemId: String, userId: String, startPositionMs: Long) {
        try {
            val source = sourceProvider.resolve(itemId, userId, startPositionMs)
            playerControl.setMediaItemAndPrepare(source.streamUrl)
            _state.value = PlaybackEngineState.Ready(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = PlaybackEngineState.Error(itemId)
        }
    }

    override fun release() {
        playerControl.release()
    }
}
