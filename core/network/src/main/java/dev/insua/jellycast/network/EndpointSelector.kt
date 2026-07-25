package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
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
 */
class EndpointSelector(
    private val probe: EndpointProbe,
    private val timeoutMs: Long = 3000,
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
                        val health = probe.probe(endpoint)
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
        endpoints.map { async { probe.probe(it) } }.awaitAll()
    }
}
