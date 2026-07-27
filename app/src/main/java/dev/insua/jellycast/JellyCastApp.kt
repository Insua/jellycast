package dev.insua.jellycast

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import dev.insua.jellycast.network.di.TrustAwareHttpClient
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * 修正 §1(c):Coil 默认自建 `OkHttpClient`,不共享 `:core:network` 的证书信任配置——后果是自签
 * 证书的 HTTPS 服务器上封面图加载失败(API 调用正常,因为那条路走的是 `SessionApiFactory` 自己
 * 建的、按 endpoint 配置了信任白名单的 client),表现为"有数据没图"。
 *
 * 用 [SingletonImageLoader.Factory] 让 Coil 3 的单例 `ImageLoader` 用 [TrustAwareHttpClient]——
 * 和字幕拉取(见 `di/AppModule.kt`)复用同一个信任策略,不新建任何 TrustManager。
 *
 * `@Inject lateinit var` 字段在 `@HiltAndroidApp` 的 Application 上受支持,Hilt 生成的代码会在
 * `Application.onCreate()`(准确地说是 attachBaseContext 之后、onCreate 之前)完成注入,
 * 早于 [newImageLoader] 第一次被调用的时机(第一次真正加载图片,不会早于任何 Activity/Compose
 * 渲染)。
 */
@HiltAndroidApp
class JellyCastApp : Application(), SingletonImageLoader.Factory {

    @Inject
    @TrustAwareHttpClient
    lateinit var trustAwareHttpClient: OkHttpClient

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { trustAwareHttpClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .build()

    private companion object {
        const val IMAGE_CACHE_DIR = "image_cache"

        /**
         * 封面图磁盘缓存上限:**128 MiB**。
         *
         * 依据:
         * - Coil 3 **默认不开磁盘缓存**,只有内存缓存。后果是进程一重启封面就全没了,断网时
         *   列表内容(Room 缓存)在、封面却一张都加载不出来 —— 整页灰色占位,和白屏差不多。
         *   这是本次离线改造里必须补的一环。
         * - 单张封面走的是 Jellyfin 的 `Images/Primary`,按本项目的卡片尺寸(≈ 140dp 宽)
         *   实际落盘通常 30–80 KB。128 MiB 大约能装下 **2000 张以上**,而这台服务器整个库的
         *   剧集 + 电影海报数量远小于这个量级 —— 也就是说常用范围内基本不会发生淘汰,
         *   离线时该有的封面都在。
         * - 上限本身仍然必要:`cacheDir` 是系统在存储紧张时会整目录清掉的地方,不设上限等于
         *   把"什么时候清"完全交给系统。128 MiB 对一台现代 Android 手机是可以忽略的占用,
         *   却给了 LRU 一个明确的边界。
         *
         * 放在 `cacheDir` 而不是 `filesDir`:这是可再生数据,系统需要空间时清掉它是正确行为,
         * 清掉之后联网再拉一次就好,不该占用"用户数据"的配额。
         */
        const val IMAGE_DISK_CACHE_BYTES = 128L * 1024 * 1024
    }
}
