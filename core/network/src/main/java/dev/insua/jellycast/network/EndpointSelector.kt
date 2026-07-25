package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

interface EndpointProbe {
    suspend fun probe(endpoint: Endpoint): EndpointHealth
}

/**
 * 对同一台服务器的多个接入地址并发探测。
 *
 * select():取第一个探测成功的(不是优先级最高的、也不是最先发起的),
 *          落选者一律取消,不留存活协程。
 * probeAll():等待全部探测完成,用于设置页展示每个地址的诊断状态。
 *
 * ### `timeoutMs` 的语义:整体预算,不是单个探测的超时
 *
 * [timeoutMs](默认 [HttpEndpointProbe.DEFAULT_TIMEOUT_MS])是 `select()` 这一整次选路调用的
 * **总预算**——不管并发了多少个 endpoint,超过这个时间 `select()` 就放弃并返回 null(或已经拿到
 * 的赢家)。它和 [HttpEndpointProbe] 构造时传入的 `timeoutMs`(单个探测请求自己的 connect/read
 * 超时)是两个独立的旋钮,两者今天默认值相同只是巧合:如果调用方把探测器的单探测超时调得比这里
 * 的整体预算更长,`select()` 依然会在自己的 `timeoutMs` 到期时整体收尾,不会被拖着等某个慢探测
 * 走完它自己更长的超时。反过来,把 `EndpointSelector` 的 `timeoutMs` 调短,也不会让单个探测提前
 * 失败——它只是让 `select()` 更早放弃等待。
 *
 * ### 容错:一个探测抛异常,不拖垮整体选路
 *
 * [EndpointProbe.probe] 的实现理应通过 `reachable = false` 表达失败,但如果它意外抛出异常,
 * `select()` / `probeAll()` 都会把那一个 endpoint 当作不可达处理,不会让异常经由结构化并发
 * 传播出去打断其它探测。[CancellationException] 例外——它必须正常传播,不能被当成"失败"吞掉。
 */
class EndpointSelector(
    private val probe: EndpointProbe,
    private val timeoutMs: Long = HttpEndpointProbe.DEFAULT_TIMEOUT_MS,
) {
    suspend fun select(endpoints: List<Endpoint>): EndpointHealth? {
        if (endpoints.isEmpty()) return null
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                // Unlimited buffer: every launched probe can always deliver its result without
                // suspending on send, so no probe coroutine can get stuck after we stop reading.
                val results = Channel<EndpointHealth>(Channel.UNLIMITED)
                val jobs = endpoints.map { endpoint ->
                    launch {
                        val health = safeProbe(endpoint)
                        if (health.reachable) results.send(health)
                    }
                }
                // Once every probe job has finished (success, failure, or cancellation) there is
                // nothing left to arrive, so close the channel to unblock a still-waiting receive.
                launch {
                    jobs.joinAll()
                    results.close()
                }

                val winner = results.receiveCatching().getOrNull()
                // Cancel whichever probes are still in flight — the losers of the race. Jobs that
                // already finished are unaffected; this just guarantees nothing keeps running (or
                // leaks) once we have (or will never have) a winner.
                jobs.forEach { it.cancel() }
                winner
            }
        }
    }

    suspend fun probeAll(endpoints: List<Endpoint>): List<EndpointHealth> = coroutineScope {
        endpoints.map { async { safeProbe(it) } }.awaitAll()
    }

    // A probe that throws instead of returning reachable = false must degrade to "this one
    // endpoint failed", not cancel its siblings / propagate out of select() or probeAll() via
    // structured concurrency. Cancellation is not a failure, though — let it propagate normally.
    private suspend fun safeProbe(endpoint: Endpoint): EndpointHealth = try {
        probe.probe(endpoint)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EndpointHealth(endpoint, false, null, e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
}
