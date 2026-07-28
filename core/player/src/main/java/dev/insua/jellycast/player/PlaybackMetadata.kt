package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.displaySubtitle
import dev.insua.jellycast.network.mapper.posterUrl

/**
 * 播放器要交给锁屏/通知栏/蓝牙/OPPO 流体云展示的元数据快照。
 *
 * 全项目此前**从未**给交给播放器的 `MediaItem` 设过 `MediaMetadata`——唯一提到它的地方是一句
 * "为什么没用它"的注释,后果是锁屏、通知、Android Auto、流体云全部空白。
 *
 * 这个数据类刻意是纯 Kotlin,不认识 Media3 的 `MediaMetadata`/`android.net.Uri`——可以直接在
 * JVM 单测里断言,不需要 Robolectric/真机。真正转成 `androidx.media3.common.MediaMetadata`
 * (要调 `Uri.parse`)留给 [ExoPlayerControl],那一层未做 JVM 单测,取舍和 [createAudioOnlyPlayer]
 * 一样:`Uri.parse` 在 `testOptions.unitTests.isReturnDefaultValues = true` 下永远回 `null`,
 * 在纯 JVM 环境里测不出真实内容,测出来的只会是一种假阳性。
 *
 * [subtitle] 直接复用 `:core:model` 的 [displaySubtitle],不重新实现"剧名 · SxxExx"的拼接
 * 规则——那份格式化逻辑只有一处权威,重复一份迟早会跑偏。
 *
 * [artworkUri] 直接复用 `:core:network` 的 [posterUrl]——没有 `imageTag` 时它已经返回 `null`,
 * 这里不用再判断一次"没有封面怎么办"。
 */
data class PlaybackDisplayMetadata(
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
)

/**
 * [baseUrl] 为空(尚未选路成功/离线)时不拼封面 URL,直接给 `null`——避免拼出一个用相对路径
 * 拼接出来、必定 404 的字符串交给下游。
 */
fun MediaItem.toPlaybackDisplayMetadata(baseUrl: String): PlaybackDisplayMetadata =
    PlaybackDisplayMetadata(
        title = name,
        subtitle = displaySubtitle,
        artworkUri = baseUrl.takeIf { it.isNotBlank() }?.let { posterUrl(it) },
    )
