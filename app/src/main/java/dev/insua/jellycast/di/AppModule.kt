package dev.insua.jellycast.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.feature.player.PlayerConnection
import dev.insua.jellycast.network.session.JellyfinSession
import dev.insua.jellycast.player.MediaControllerPlayerConnection
import dev.insua.jellycast.subtitle.SubtitleRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * 修正 §8(e):`:feature:player` 的 `PlayerConnection`/`NowPlayingInfo` 只是接口,真实实现在
 * [MediaControllerPlayerConnection](见该类 KDoc)——这里只是把它绑定成 Hilt 图里的
 * [PlayerConnection]。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePlayerConnection(impl: MediaControllerPlayerConnection): PlayerConnection = impl

    /**
     * 字幕拉取复用 [TrustAwareHttpClient](修正 §1c 的同一个理由:字幕文件和封面图一样走
     * HTTP(S) 直连当前 endpoint,不应该用一个不认识自签证书白名单的默认 client)。
     */
    @Provides
    @Singleton
    fun provideSubtitleRepository(
        @TrustAwareHttpClient client: OkHttpClient,
        session: JellyfinSession,
    ): SubtitleRepository = SubtitleRepository(
        client = client,
        baseUrlProvider = { session.cachedBaseUrlOrNull().orEmpty() },
        tokenProvider = { session.cachedTokenOrNull().orEmpty() },
    )
}
