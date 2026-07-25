package dev.insua.jellycast

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
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
            .build()
}
