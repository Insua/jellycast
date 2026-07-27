package dev.insua.jellycast.network.session

import dev.insua.jellycast.network.JellyfinApi
import dev.insua.jellycast.network.dto.AuthRequestDto
import dev.insua.jellycast.network.dto.AuthResultDto
import dev.insua.jellycast.network.dto.BaseItemDto
import dev.insua.jellycast.network.dto.ItemsResponseDto
import dev.insua.jellycast.network.dto.PlaybackInfoResponseDto
import dev.insua.jellycast.network.dto.PlaybackProgressInfoDto
import dev.insua.jellycast.network.dto.PlaybackStartInfoDto
import dev.insua.jellycast.network.dto.PlaybackStopInfoDto
import dev.insua.jellycast.network.dto.PublicSystemInfoDto
import kotlinx.coroutines.CancellationException

/**
 * 单一、稳定的 [JellyfinApi] 单例——这是本项目"运行时可变 baseUrl"的另外半个落地方案(配合
 * [ActiveServerSession] 的缓存/选路)。
 *
 * 全项目其它模块(ViewModel、`PlaybackSourceResolver`、`ProgressReporter`、
 * `AutoPlayNextController`……)构造时都要求一个具体的 `JellyfinApi` 参数,而不是"一个能拿到
 * JellyfinApi 的东西"——这是既有、已测试过的契约,不能为了适配运行时选路而在到处加 suspend/session
 * 包装。这个类把两者接起来:对外表现成一个普通、随时可注入的 [JellyfinApi] 单例,内部每一次方法
 * 调用才通过 [JellyfinSession.api] 去解析"此刻真正应该用哪一个 endpoint 的 Retrofit 实例",
 * 从而让 Hilt 图里只需要提供*一个* [JellyfinApi] 绑定,其余装配代码完全不用感知选路这件事。
 *
 * 失败驱动重新选路:任何一次委派调用抛出异常(网络失败、超时、认证失效……)都会先让
 * [JellyfinSession.invalidate] 清掉缓存的 endpoint,再把原始异常照原样重新抛出——调用方
 * (`ProgressReporter`/`PlaybackSourceResolver` 等)本来就已经把"请求可能失败"当成正常情况处理
 * (静默降级/入队补报),这里只是额外触发"下一次请求前重新选路",不改变异常本身、不吞掉它。
 */
class SessionJellyfinApi(private val session: JellyfinSession) : JellyfinApi {

    private suspend fun <T> delegate(call: suspend JellyfinApi.() -> T): T {
        try {
            return session.api().call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.invalidate()
            throw e
        }
    }

    override suspend fun publicInfo(): PublicSystemInfoDto = delegate { publicInfo() }

    override suspend fun authenticate(body: AuthRequestDto): AuthResultDto = delegate { authenticate(body) }

    override suspend fun items(
        userId: String,
        types: String,
        recursive: Boolean,
        sortBy: String,
        startIndex: Int?,
        limit: Int?,
        parentId: String?,
        searchTerm: String?,
    ): ItemsResponseDto =
        delegate { items(userId, types, recursive, sortBy, startIndex, limit, parentId, searchTerm) }

    override suspend fun resume(userId: String): ItemsResponseDto = delegate { resume(userId) }

    override suspend fun nextUp(userId: String, limit: Int): ItemsResponseDto = delegate { nextUp(userId, limit) }

    override suspend fun seasons(seriesId: String, userId: String): ItemsResponseDto =
        delegate { seasons(seriesId, userId) }

    override suspend fun episodes(seriesId: String, seasonId: String, userId: String): ItemsResponseDto =
        delegate { episodes(seriesId, seasonId, userId) }

    override suspend fun itemDetail(itemId: String, userId: String): BaseItemDto =
        delegate { itemDetail(itemId, userId) }

    override suspend fun playbackInfo(itemId: String, userId: String, body: Map<String, String>): PlaybackInfoResponseDto =
        delegate { playbackInfo(itemId, userId, body) }

    override suspend fun reportStart(body: PlaybackStartInfoDto) = delegate { reportStart(body) }

    override suspend fun reportProgress(body: PlaybackProgressInfoDto) = delegate { reportProgress(body) }

    override suspend fun reportStop(body: PlaybackStopInfoDto) = delegate { reportStop(body) }
}
