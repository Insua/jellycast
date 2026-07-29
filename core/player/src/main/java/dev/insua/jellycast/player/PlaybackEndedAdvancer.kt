package dev.insua.jellycast.player

/**
 * `Player.STATE_ENDED` 时决定"要不要自动连播、连播到哪一条",并驱动 [AudioPlaybackEngine] 播放
 * 下一条。
 *
 * ⚠️ Finding 1(已闭合):Task 21/22 里 `PlaybackService` 的 STATE_ENDED 监听器直接调
 * `ExoPlayerControl(exoPlayer).setMediaItemAndPrepare(next.streamUrl)`,绕开了
 * [AudioPlaybackEngine.play]——engine 内部私有的 `currentItemId`/`currentUserId` 从没更新过,
 * `state` 也停在旧一集的 `Ready(oldSource)`。两个用户可见后果:
 * 1. 自动连播之后锁屏拖进度条 → [AudioPlaybackEngine.seekTo] 拿着**上一集**的 itemId 重新
 *    resolve(这条流 `Accept-Ranges: none`,seek 靠重新 resolve,不是 `player.seekTo`,见
 *    [AudioPlaybackEngineImpl] 类注释),悄悄跳回上一集。
 * 2. `MediaControllerPlayerConnection.nowPlaying` 按 `queueItem.id == source.itemId` 匹配,
 *    自动连播后立刻对不上,迷你条/通知栏显示旧数据或者空。
 *
 * 修正:自动连播必须和 `MediaControllerPlayerConnection.skipToNext()` / `AppSessionViewModel.play()`
 * 走同一条路——[AudioPlaybackEngine.play],让 engine 内部状态和实际在放的条目保持同步。
 *
 * ⚠️ 稳定性根因 #4(已闭合):这里曾经是"[AutoPlayNextController] 先 resolve 一次拿来判断,
 * 结果丢掉,再经 `engine.play()` resolve 第二次"——被当成"多一次网络请求换一个不变量"的取舍记在案上。
 * 真机结论是这个取舍不成立:那次多余的 `POST PlaybackInfo` 往返正好顶在"上一集刚播完、下一集还没
 * 出声"的静默窗口上,还在 Jellyfin 上凭空多开一个等不到 stop 的播放会话。
 * 现在 [AutoPlayNextController] 只回答"下一条是哪个条目",解析只发生在 `engine.play()` 里一次,
 * 不变量原样保持。
 *
 * 重入保护:[AudioPlaybackEngine.play] 内部会触发 prepare,prepare 本身可能间接产生更多播放器
 * 状态回调。[isAdvancing] 保证同一时刻只有一次 advance 在跑——如果一次 advance 还没结束就再收到
 * 一次 STATE_ENDED(不管是真的重入,还是极端情况下的重复回调),后到的这次直接丢弃,不会重复摸
 * [AutoPlayNextController] 的队列、也不会重复调用 `engine.play`。
 */
class PlaybackEndedAdvancer(
    private val engine: AudioPlaybackEngine,
    private val autoPlayNextController: AutoPlayNextController,
    private val userIdProvider: suspend () -> String?,
) {
    @Volatile
    private var isAdvancing = false

    suspend fun onPlaybackEnded() {
        if (isAdvancing) return
        isAdvancing = true
        try {
            val userId = userIdProvider() ?: return
            val next = autoPlayNextController.onPlaybackEnded(userId) ?: return
            engine.play(next.id, userId, 0L)
        } finally {
            isAdvancing = false
        }
    }
}
