package dev.insua.jellycast.network.repository

import dev.insua.jellycast.model.Cached
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 把 [CachePolicy] 的判定接到真实的读缓存 / 请求网络 / 写回缓存三个动作上,产出一条
 * "先旧后新"的 [Flow]。这是唯一一处 stale-while-revalidate 的编排代码。
 *
 * 之所以独立成一个自由函数而不是塞进 [CachingMediaRepository] 里:这样"发射顺序"这件事
 * 只有一份实现,任何替身(比如 ViewModel 单测里那个不带 Room 的假仓储)都能复用同一条编排,
 * 不可能出现"假仓储的行为和真仓储不一样,ViewModel 测试全绿但线上照样白屏"。
 *
 * ## 约定
 * - [readCache] 返回 `null` 表示**没有可用缓存**(区别于"缓存里存的是一个空列表")。
 *   它自己负责兜底,失败就返回 `null`;抛出来的异常等同于"没有缓存"。
 * - [fetch] 的失败被收敛成 `Result.failure`,**永远不会向上抛** —— 断网不许变成崩溃。
 *   唯一的例外是 [CancellationException]:协程取消必须继续向上传播,否则
 *   `flatMapLatest` 之类的取消语义会被悄悄吞掉,一次取消被误记成一次"刷新失败"。
 * - 只有 [fetch] 成功才调用 [writeThrough];失败时**一个字节都不写库**,
 *   这样一次服务端抖动不会抹掉用户上次看到的内容。
 */
fun <T> staleWhileRevalidate(
    readCache: suspend () -> T?,
    fetch: suspend () -> T,
    writeThrough: suspend (T) -> Unit,
): Flow<Cached<T>> = flow {
    val cache = try {
        readCache()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null // 读缓存失败等同于没有缓存,绝不因此让整条流失败
    }

    // 第一阶段:能立刻发的先发出去(有缓存就是缓存,没缓存就什么都不发),不等网络。
    val immediate = CachePolicy.resolve(cache, network = null)
    immediate.forEach { emit(it) }

    val network = try {
        Result.success(fetch())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    network.getOrNull()?.let { fresh ->
        try {
            writeThrough(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 写缓存失败只影响"下次能不能离线看到",不影响这次已经拿到的新数据,静默降级。
        }
    }

    // 第二阶段:补发第一阶段之后多出来的部分。CachePolicy 保证了后者以前者为前缀,
    // 所以这里 drop 掉前缀就是"还没发过的",不会重发也不会漏发。
    CachePolicy.resolve(cache, network).drop(immediate.size).forEach { emit(it) }
}
