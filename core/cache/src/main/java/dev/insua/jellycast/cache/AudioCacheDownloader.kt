package dev.insua.jellycast.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 把一个 URL 的响应体整体下载写入 [into]。只管字节——[into] 由调用方([AudioCacheStore])传入
 * `.part` 临时路径,完成之后的 rename 与索引落地不归这里管。
 *
 * ## 没有断点续传
 *
 * 转码流响应头是 `Accept-Ranges: none`,服务端根本不支持 Range 请求。这里因此不做任何形式的
 * "接着上次下载"——每次调用都是一次全新的、从头到尾的整体下载,中断了就是中断了,调用方需要
 * 的话必须整个重新发起。
 *
 * ## 取消必须真正打断正在进行的传输
 *
 * `call.cancel()` 挂在 [suspendCancellableCoroutine] 的 `invokeOnCancellation` 上,这个注册覆盖
 * 从建连、等响应头,到整段响应体写完文件的全过程——调用方所在的协程被取消时,这一下 `cancel()`
 * 会直接关掉底层 socket,正在阻塞的读会立刻抛 `IOException` 解除阻塞,不需要等对端把剩下的数据
 * 发完、也不需要等一个轮询间隔才发现自己该停了。这是「中途取消要真的快」这件事在这一层的实现——
 * 参照 [dev.insua.jellycast.network.HttpEndpointProbe] 里 `Call.await()` 的同一个模式。
 *
 * 铁律:[CancellationException] 必须重抛,不能被这里的兜底 `catch (e: Exception)` 吞掉——否则
 * 协程取消信号在这一层就被吸收了,调用方永远等不到它的取消真正完成。
 *
 * ## I4 复审(Important):[shouldContinue] —— 断网/切到蜂窝时立即停止当前下载
 *
 * 设计文档 §3 明确要求「断网/切到蜂窝时:立即停止当前下载,丢弃未完成的部分」。这件事光靠
 * `CachePrefetchController` 在**发起下一个下载之前**查一次 WiFi 状态堵不住——一集可能是几十
 * 兆字节、传输要几十秒,用户完全可能在**下载进行到一半**的时候走出门。[shouldContinue] 因此
 * 下沉到字节拷贝循环内部,每读完一块([COPY_BUFFER_SIZE])就问一次,不再是 `copyTo()` 那种
 * 一次性、拷贝过程中无法插手的黑盒调用——网络类型一变,最多晚一个缓冲块(几十毫秒级)就会中止,
 * 不需要额外起一个轮询协程(那样在 `kotlinx-coroutines-test` 的虚拟时钟下会因为
 * "无限重新调度的 `delay()`" 让 `advanceUntilIdle()` 永远推进不完,把测试直接挂死)。
 *
 * 中止时抛 [IOException](不是 [CancellationException] ——**没有**协程被取消,只是这次传输
 * 判定为"不该继续"),原样落进下面 `download()` 已有的 `catch (e: IOException)` 分支:清掉半成品、
 * 返回 `false`。[AudioCacheStore.store] 对 `false` 的处理本来就是删 `.part` 文件、不落索引——
 * 和"丢弃未完成的部分"完全对得上,不需要新增一条错误路径。
 */
class AudioCacheDownloader(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        into: File,
        shouldContinue: () -> Boolean = { true },
    ): Boolean = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).get().build())
        try {
            awaitDownload(call, into, shouldContinue)
        } catch (e: CancellationException) {
            into.delete()
            throw e
        } catch (e: IOException) {
            into.delete()
            false
        }
    }

    private suspend fun awaitDownload(call: Call, into: File, shouldContinue: () -> Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { response ->
                    val result = writeIfSuccessful(response, into, shouldContinue)
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (e: IOException) {
                // call.cancel() 触发的中断、以及 shouldContinue() 变 false 时主动抛出的中止,
                // 都会从这里冒出来(都是 IOException),但此时 continuation 早已经被取消掉了——
                // isActive 为 false,不需要(也不能)再 resume 它,真正的 CancellationException
                // 由协程机制自己在 suspendCancellableCoroutine 返回时抛出。
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }

    private fun writeIfSuccessful(response: Response, into: File, shouldContinue: () -> Boolean): Boolean {
        if (!response.isSuccessful) return false
        into.parentFile?.mkdirs()
        into.outputStream().use { out ->
            response.body.byteStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    if (!shouldContinue()) {
                        throw IOException("下载中止:不再满足继续下载的条件(比如切出了 WiFi)")
                    }
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
            }
        }
        return true
    }

    private companion object {
        /** 见类注释「I4 复审」:每读完一块就检查一次 [shouldContinue],8 KB 是常见的 I/O 缓冲粒度。 */
        const val COPY_BUFFER_SIZE = 8 * 1024
    }
}
