package dev.insua.jellycast.feature.server.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.insua.jellycast.datastore.ServerStore
import dev.insua.jellycast.feature.server.DefaultPeerCertificateFetcher
import dev.insua.jellycast.feature.server.JellyfinApiFactory
import dev.insua.jellycast.feature.server.PeerCertificateFetcher
import dev.insua.jellycast.feature.server.RetrofitJellyfinApiFactory
import dev.insua.jellycast.network.EndpointSelector
import dev.insua.jellycast.network.HttpEndpointProbe
import okhttp3.OkHttpClient

/**
 * :feature:server 目前是第一个把 :core:datastore / :core:network 接进 Hilt 的模块,
 * 所以这里现场提供它们的默认绑定。[EndpointSelector] 用的探测器不带任何证书信任白名单——
 * 它只探测"这个地址此刻通不通",真正需要按 endpoint 信任自签证书的是登录请求本身
 * (见 [dev.insua.jellycast.feature.server.RetrofitJellyfinApiFactory])。
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerModule {

    @Provides
    fun provideServerStore(@ApplicationContext context: Context): ServerStore = ServerStore(context)

    @Provides
    fun provideEndpointSelector(): EndpointSelector = EndpointSelector(HttpEndpointProbe(OkHttpClient()))

    @Provides
    fun provideJellyfinApiFactory(): JellyfinApiFactory = RetrofitJellyfinApiFactory()

    @Provides
    fun providePeerCertificateFetcher(): PeerCertificateFetcher = DefaultPeerCertificateFetcher
}
