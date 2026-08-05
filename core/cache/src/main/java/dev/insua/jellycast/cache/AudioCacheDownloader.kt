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
 */
class AudioCacheDownloader(private val client: OkHttpClient) {

    suspend fun download(url: String, into: File): Boolean = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).get().build())
        try {
            awaitDownload(call, into)
        } catch (e: CancellationException) {
            into.delete()
            throw e
        } catch (e: IOException) {
            into.delete()
            false
        }
    }

    private suspend fun awaitDownload(call: Call, into: File): Boolean =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { response ->
                    val result = writeIfSuccessful(response, into)
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (e: IOException) {
                // call.cancel() 触发的中断也会从这里冒出来(通常是一个 IOException),但此时
                // continuation 早已经被取消掉了——isActive 为 false,不需要(也不能)再 resume 它,
                // 真正的 CancellationException 由协程机制自己在 suspendCancellableCoroutine
                // 返回时抛出。
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }

    private fun writeIfSuccessful(response: Response, into: File): Boolean {
        if (!response.isSuccessful) return false
        into.parentFile?.mkdirs()
        into.outputStream().use { out -> response.body.byteStream().use { it.copyTo(out) } }
        return true
    }
}
