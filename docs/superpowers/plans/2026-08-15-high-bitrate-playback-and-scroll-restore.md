# 高码率片源播不出来 & 列表滚动位置不保留 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 4K/高码率 `.ts` 片源(舌尖上的中国 第四季)能正常走 L1 播放,并让列表页返回时保住滚动位置。

**Architecture:** 三处独立缺陷各修一处 —— 探测的 OkHttp 超时(根因)、播放器的 media3 HTTP 超时(第二堵墙)、L3 兜底 URL 的码率上限(把错判放大成卡死的那一环)。滚动位置**先复现再定修法**,不预设结论。

**Tech Stack:** Kotlin · OkHttp 5.4.0 · Media3 1.10.1 · MockWebServer · JUnit5(JVM)/ JUnit4 + AndroidJUnit4(设备)· Compose UI Test · Navigation-Compose 2.9.8

设计文档:`docs/superpowers/specs/2026-08-15-high-bitrate-playback-and-scroll-restore-design.md`(**动手前必须完整读一遍**)

## Global Constraints

- **不渲染视频**(铁律 1):不绑 Surface、不建 PlayerView。本计划不触碰 `audioOnlyTrackSelectionParameters()`。
- **降级链必须保底可用**(铁律 3):L1/L3 任何失败都静默降级,不得让用户看到错误。
- **不允许全局关闭 TLS 校验**(铁律 5):所有 OkHttp 改动只能从既有的 `@TrustAwareHttpClient` 实例 `newBuilder()` 派生,**禁止 `OkHttpClient.Builder()` 新建**——新建会丢掉自签证书信任与连接池。
- **每条新测试必须先对着未修复代码变红。** 本仓库反复出现"看着对但测不出任何东西"的空测试。每个任务的报告里**必须**贴出变异验证的真实输出;变异后测试仍绿 = 这条测试是空的,**删掉它并如实报告**,不许留着凑数。
- **超时常量只有一份。** `STREAM_CONNECT_TIMEOUT_MS` / `STREAM_READ_TIMEOUT_MS` 定义在 `:core:player` 的 `StreamTimeouts.kt`,探测与播放器都引用它,不许各写各的。
- **Gradle 全部前台执行。** 不许 `run_in_background`、不许起监视循环、不许轮询。跑测试就等它跑完。
- **Gradle 任务名陷阱**:`:<module>:test` 是聚合任务,**不接受 `--tests`**,要用 `:<module>:testDebugUnitTest --tests '...'`;设备测试**不能**用 `--tests`,要用 `-Pandroid.testInstrumentationRunnerArguments.class=<全限定类名>`。
- **凭据纪律**:端到端凭据只来自根目录 `testing.properties`(已 gitignore)。服务器地址 / 账号 / 密码 / token **绝不允许**出现在代码、测试、断言消息、日志、任务报告、commit message 里。需要在失败信息里指代服务器就用 `TestCredentials.redact(...)`。
- **每个任务结束必须 commit**,消息格式 `<emoji> (<scope>): <subject>`,subject 用**英文**、祈使句、不加句号。类型→emoji:`feat`→✨ `fix`→🐛 `docs`→📝 `test`→✅ `refactor`→🔨 `chore`→🔧。

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `core/player/src/main/java/dev/insua/jellycast/player/StreamTimeouts.kt` | **新建** | 探测与播放共用的两个超时常量,唯一定义处 |
| `core/player/src/main/java/dev/insua/jellycast/player/HttpStreamProbe.kt` | 修改 | 从注入的 client 派生一份只改超时的副本 |
| `core/player/src/test/java/dev/insua/jellycast/player/HttpStreamProbeTimeoutTest.kt` | **新建** | 根因回归:慢响应头下探测必须仍有结论 |
| `core/player/src/main/java/dev/insua/jellycast/player/PlaybackSourceResolver.kt` | 修改 | L3 URL 加码率上限 |
| `core/player/src/test/java/dev/insua/jellycast/player/PlaybackSourceResolverTest.kt` | 修改 | L3 URL 参数断言 |
| `core/player/src/main/java/dev/insua/jellycast/player/JellyCastPlayerFactory.kt` | 修改 | 播放器显式配 `DefaultHttpDataSource.Factory` |
| `core/player/src/androidTest/java/dev/insua/jellycast/player/SlowResponsePlaybackDeviceTest.kt` | **新建** | 设备测试:响应头延迟 12 秒仍能开流 |
| `core/player/build.gradle.kts` | 修改 | androidTest 加 MockWebServer |
| `app/src/androidTest/java/dev/insua/jellycast/e2e/HighBitrateSourceE2eTest.kt` | **新建** | 端到端:高码率 `.ts` 片源真的走 L1 且出声 |
| `app/src/androidTest/java/dev/insua/jellycast/navigation/ListScrollRestoreTest.kt` | **新建** | 滚动位置复现测试(Task 5)与回归护栏 |
| (Task 6 的文件由 Task 5 的结论决定) | — | 见 Task 6 |

---

## Task 1: 探测的超时(R1 —— 根因)

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/StreamTimeouts.kt`
- Create: `core/player/src/test/java/dev/insua/jellycast/player/HttpStreamProbeTimeoutTest.kt`
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/HttpStreamProbe.kt`

**Interfaces:**
- Produces: `const val STREAM_CONNECT_TIMEOUT_MS: Int = 10_000`、`const val STREAM_READ_TIMEOUT_MS: Int = 60_000`(包级常量,`dev.insua.jellycast.player`);`HttpStreamProbe(client: OkHttpClient, connectTimeoutMs: Long = …, readTimeoutMs: Long = …)`。Task 3 会引用这两个常量。
- Consumes: 无。

**背景(必读):** `TrustAwareHttpClientModule` 提供的 client 用的是 `OkHttpClient.Builder().build()` —— OkHttp 默认 `readTimeout` 是 **10 秒**。4K `.ts` 片源的 L1 首字节实测 6.3–46.1 秒,于是探测超时 → `isAudioOnly` 返回 `null` → 错误降级到 L3。**别去改 DI 模块**:那个 client 还给 Coil 封面图和字幕拉取用,把它的读超时拉到 60 秒会让挂掉的封面图也干等 60 秒。

- [ ] **Step 1: 建常量文件**

`core/player/src/main/java/dev/insua/jellycast/player/StreamTimeouts.kt`:

```kotlin
package dev.insua.jellycast.player

/**
 * L1/L3 流(探测与播放两处)共用的 HTTP 超时。见
 * `docs/superpowers/specs/2026-08-15-high-bitrate-playback-and-scroll-restore-design.md` §5.1/§5.2。
 *
 * ## 为什么必须是两个值而不是一个
 *
 * 连接超时和读取超时在这条链路上语义完全不同:
 * - **连不上** = 服务器不可达,应当**快速失败**,让降级/报错早点发生,不要让用户干等。
 * - **连上了但半天不吐字节** = 服务端正在为一个大文件起转码,这**不是**故障。
 *   MPEG-TS 没有全局索引,ffmpeg 必须先扫描;实测同一台服务器上
 *   1080p h264 mkv 的首字节 1.2–2.4 秒,而 4K HEVC 50fps 的 `.ts` 要 6.3–46.1 秒。
 *
 * 把两者混成一个值,要么在死服务器上白等一分钟,要么把慢转码误判成故障 —— 后者正是
 * 本次修复的那个 bug:OkHttp 默认两个超时都是 10 秒,恰好卡在实测分布中间,
 * 于是同一部剧的不同集时而走 L1 时而掉进 L3。
 *
 * 60 秒覆盖实测最坏值 46.1 秒并留余量。
 */
const val STREAM_CONNECT_TIMEOUT_MS: Int = 10_000

/** 见 [STREAM_CONNECT_TIMEOUT_MS]。 */
const val STREAM_READ_TIMEOUT_MS: Int = 60_000
```

- [ ] **Step 2: 写会失败的测试**

`core/player/src/test/java/dev/insua/jellycast/player/HttpStreamProbeTimeoutTest.kt`:

```kotlin
package dev.insua.jellycast.player

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 根因回归(设计文档 §3.1)。
 *
 * 生产上那个 bug 的形状:`/Audio/{id}/universal` 对 4K `.ts` 片源要 6.3–46.1 秒才吐响应头,
 * 而 [HttpStreamProbe] 拿到的 client 用的是 OkHttp 默认的 10 秒读超时,于是探测超时、
 * [StreamProbe.isAudioOnly] 返回 `null`,`PlaybackSourceResolver` 按"没结论"降级到 L3 ——
 * L3 是 54.5 Mbps 的视频流拷贝,必然卡死。
 *
 * ⚠️ 这条用例故意慢(15 秒)。它复现的就是"等得够不够久",缩短延迟就等于删掉它的鉴别力。
 */
class HttpStreamProbeTimeoutTest {

    /**
     * 服务端 15 秒后才吐响应头 —— 超过 OkHttp 默认的 10 秒读超时,但在生产值 60 秒之内。
     * 探测必须**等到**并给出 `true`,而不是超时后返回 `null`。
     */
    @Test
    fun `响应头慢到超过OkHttp默认读超时时探测仍然给得出结论`() = runTest {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/aac")
                    .setBody("fake-adts")
                    .setHeadersDelay(15, TimeUnit.SECONDS),
            )
            start()
        }
        try {
            // 生产装配:注入一个没设过任何超时的 client(= TrustAwareHttpClientModule 的形态)
            val probe = HttpStreamProbe(OkHttpClient())
            val verdict = probe.isAudioOnly(server.url("/Audio/x/universal").toString())
            assertEquals(true, verdict, "慢响应头被当成了「没结论」,会错误降级到 L3")
        } finally {
            server.shutdown()
        }
    }

    /**
     * 读超时确实生效、且**没有**被连接超时压过去:注入 connect=200ms / read=1500ms,
     * 服务端延迟 800ms —— 只有当两个超时是分开的时候这条才会通过。
     */
    @Test
    fun `连接超时短读取超时长时慢响应头仍能读到`() = runTest {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/aac")
                    .setBody("fake-adts")
                    .setHeadersDelay(800, TimeUnit.MILLISECONDS),
            )
            start()
        }
        try {
            val probe = HttpStreamProbe(OkHttpClient(), connectTimeoutMs = 200L, readTimeoutMs = 1500L)
            assertEquals(true, probe.isAudioOnly(server.url("/x").toString()))
        } finally {
            server.shutdown()
        }
    }

    /**
     * 连接超时**没有**被拉长到读超时那么久:指向一个不可路由的地址,read=15s 而 connect=200ms,
     * 必须远早于 15 秒失败,并且按 [StreamProbe] 的三态约定返回 `null`(没结论,不是"不是纯音频")。
     *
     * ⚠️ 变异验证时若把 connectTimeoutMs 改成 15_000L 这条**仍然绿**,说明本机网络栈是瞬间回
     * unreachable 而不是真的挂起 —— 那样它就是一条空测试,**删掉它**并在报告里写明,不要留着。
     */
    @Test
    fun `连不上的地址快速失败而不是等满读超时`() = runTest {
        val probe = HttpStreamProbe(OkHttpClient(), connectTimeoutMs = 200L, readTimeoutMs = 15_000L)
        val start = System.nanoTime()
        val verdict = probe.isAudioOnly("http://10.255.255.1:8096/Audio/x/universal")
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertNull(verdict, "连不上必须是「没结论」(null),不能是「不是纯音频」(false)")
        assertTrue(elapsedMs < 5_000, "连接失败用了 ${elapsedMs}ms,连接超时被读超时压过去了")
    }
}
```

- [ ] **Step 3: 跑测试,确认第一条失败**

```bash
./gradlew :core:player:testDebugUnitTest --tests '*HttpStreamProbeTimeoutTest*'
```

预期:`响应头慢到超过OkHttp默认读超时时探测仍然给得出结论` **失败** —— 实际值 `null`(OkHttp 默认 10 秒读超时把它打断了)。另两条编译不过(`connectTimeoutMs` 参数还不存在)。**把真实输出记下来,报告里要贴。**

- [ ] **Step 4: 改 HttpStreamProbe**

把 `core/player/src/main/java/dev/insua/jellycast/player/HttpStreamProbe.kt` 的类声明改成:

```kotlin
class HttpStreamProbe(
    client: OkHttpClient,
    connectTimeoutMs: Long = STREAM_CONNECT_TIMEOUT_MS.toLong(),
    readTimeoutMs: Long = STREAM_READ_TIMEOUT_MS.toLong(),
) : StreamProbe {

    /**
     * ## 为什么在这里派生而不是在 DI 模块里配
     *
     * 注入进来的 `@TrustAwareHttpClient` 是**共享**实例 —— Coil 封面图、字幕拉取都用它。
     * 把读超时在那里拉到 60 秒,会让一张挂掉的封面图也干等一分钟。探测是唯一需要长读超时的
     * 用途,所以超时只属于探测。
     *
     * 用 `newBuilder()` 派生而**不是** `OkHttpClient.Builder()` 新建:派生保留连接池、
     * 以及按 host 白名单的自签证书信任(铁律 5)。新建一个裸 client 会在自签证书服务器上
     * 必定 `SSLHandshakeException`,而 [PlaybackSourceResolver] 会把它静默当成"没结论"降到 L3
     * —— 本产品定义的最坏静默降级。这个坑 `TrustAwareHttpClientModule` 的 KDoc 里踩过一次。
     *
     * 超时做成构造参数是为了能被测试注入小值(见 `HttpStreamProbeTimeoutTest`),
     * 生产默认值就是 [STREAM_CONNECT_TIMEOUT_MS] / [STREAM_READ_TIMEOUT_MS]。
     */
    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
```

加 import `java.util.concurrent.TimeUnit`。`isAudioOnly` 方法体一个字不改。

- [ ] **Step 5: 跑测试,确认全绿**

```bash
./gradlew :core:player:testDebugUnitTest --tests '*HttpStreamProbeTimeoutTest*'
```

预期:3 条全 PASS(第一条约 15 秒)。

- [ ] **Step 6: 变异验证(三条都要做,逐条记录输出)**

1. 把派生那段临时改成 `private val client: OkHttpClient = client`(退回修复前)→ 重跑 → 第一条必须 **FAIL**。
2. 把 `connectTimeoutMs` 默认值临时改成 `15_000L`,重跑第三条 → 必须 **FAIL**;**若仍绿,删掉第三条**并在报告里说明原因(见该用例 KDoc)。
3. 把 `readTimeoutMs` 在第二条里临时注入 `100L` → 第二条必须 **FAIL**。

三处**全部改回**,再跑一遍确认全绿。

- [ ] **Step 7: 跑整个模块,确认没打破既有测试**

```bash
./gradlew :core:player:testDebugUnitTest
```

`PlayerModuleTest` 那条"探测必须用 `@TrustAwareHttpClient`"的反射断言应当**照旧通过**(本任务没动 DI)。

- [ ] **Step 8: Commit**

```bash
git add core/player/src/main/java/dev/insua/jellycast/player/StreamTimeouts.kt \
        core/player/src/main/java/dev/insua/jellycast/player/HttpStreamProbe.kt \
        core/player/src/test/java/dev/insua/jellycast/player/HttpStreamProbeTimeoutTest.kt
git commit -m "🐛 (player): give the L1 probe its own long read timeout so slow transcodes are not misread as failures"
```

---

## Task 2: L3 兜底降到最低码率(R3)

**Files:**
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackSourceResolver.kt`(`buildVideoStreamUrl`,约 260-274 行)
- Modify: `core/player/src/test/java/dev/insua/jellycast/player/PlaybackSourceResolverTest.kt`

**Interfaces:**
- Consumes: 无(不依赖 Task 1)。
- Produces: L3 URL 形状变化,Task 4 的端到端会顺带看到。

**背景:** 现在的 L3 URL 不带任何码率/尺寸/音频参数,服务端对视频做流拷贝。对 4K 片源实测 **54.5 Mbps**,客户端虽然禁用了视频轨但混流字节必须整条下载 —— 卡死。加上限后实测 **0.51 Mbps、2.2 倍实时**。

- [ ] **Step 1: 写会失败的测试**

在 `PlaybackSourceResolverTest` 里新增(沿用文件里既有的 `newResolver` / `probe` 辅助函数):

```kotlin
    /**
     * L3 兜底必须把视频压到最低(设计文档 §3.3/§5.3)。
     *
     * 不加这些参数时服务端对视频做**流拷贝**:4K HEVC 片源实测 54.5 Mbps,客户端即使禁用了
     * 视频轨,混流的字节也必须整条下载才能取出音频 —— 家宽上行不可能,表现就是"一直卡死"。
     * 加上限后实测 0.51 Mbps、2.2 倍实时。
     *
     * 音频参数是顺带补的:L3 原来一个音频参数都不带,服务端给什么是什么。
     */
    @Test
    fun `L3兜底URL带上视频降码率与音频转码参数`() = runTest {
        val resolver = newResolver(probe(emptySet()))   // 探测说"不是纯音频" → 走 L3

        val source = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)

        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, source.level)
        listOf(
            "videoCodec=h264",
            "videoBitRate=100000",
            "maxWidth=320",
            "maxFramerate=15",
            "audioCodec=aac",
            "audioBitRate=128000",
            "maxAudioChannels=2",
        ).forEach { param ->
            assertTrue(source.streamUrl.contains(param), "L3 URL 缺少 $param:${source.streamUrl}")
        }
    }

    /** 这次改动不许污染 L1:L1 是纯音频接口,加视频参数没有意义还可能改变服务端行为。 */
    @Test
    fun `L1的URL不含任何视频参数`() = runTest {
        val resolver = newResolver(probe(setOf("/Audio/")))

        val source = resolver.resolve(TEST_ITEM_ID, TEST_USER_ID)

        assertEquals(AudioDeliveryLevel.SERVER_AUDIO_ONLY, source.level)
        listOf("videoCodec", "videoBitRate", "maxWidth", "maxFramerate").forEach { param ->
            assertFalse(source.streamUrl.contains(param), "L1 URL 不该出现 $param:${source.streamUrl}")
        }
    }
```

- [ ] **Step 2: 跑测试,确认失败**

```bash
./gradlew :core:player:testDebugUnitTest --tests '*PlaybackSourceResolverTest*'
```

预期:`L3兜底URL带上视频降码率与音频转码参数` **FAIL**(缺 `videoCodec=h264`);`L1的URL不含任何视频参数` PASS。

- [ ] **Step 3: 改 buildVideoStreamUrl**

在 `buildVideoStreamUrl` 函数体末尾、`startTimeTicks` 那一行**之后**追加:

```kotlin
        // 见本函数 KDoc「之四」:不加这些参数服务端对视频做流拷贝,4K 片源实测 54.5 Mbps。
        append("&videoCodec=").append(L3_VIDEO_CODEC)
        append("&videoBitRate=").append(L3_VIDEO_BIT_RATE_BPS)
        append("&maxWidth=").append(L3_MAX_WIDTH)
        append("&maxFramerate=").append(L3_MAX_FRAMERATE)
        append("&audioCodec=aac")
        append("&audioBitRate=").append(audioBitRateBps)
        append("&maxAudioChannels=2")
```

`buildVideoStreamUrl` 需要新增参数 `audioBitRateBps: Int`,在 `resolve()` 里调用处把 `audioBitRateBps` 传进去(该字段是构造参数,类内可直接引用;显式传参是为了让这个函数保持无隐式状态,和 `buildAudioUniversalUrl` 一致)。

在 `private companion object` 里加:

```kotlin
        /** 见 [buildVideoStreamUrl] 之四:L3 强制转出的视频编码,h264 是兼容性最好的。 */
        const val L3_VIDEO_CODEC = "h264"

        /** 见 [buildVideoStreamUrl] 之四:100 kbps —— 视频反正不渲染,只要流能成立即可。 */
        const val L3_VIDEO_BIT_RATE_BPS = 100_000

        /** 见 [buildVideoStreamUrl] 之四:320 px 宽,让服务端的缩放/编码代价尽量低。 */
        const val L3_MAX_WIDTH = 320

        /** 见 [buildVideoStreamUrl] 之四:15 fps,源可能是 50fps,不降帧率编码代价白白翻三倍。 */
        const val L3_MAX_FRAMERATE = 15
```

在 `buildVideoStreamUrl` 的 KDoc 末尾(「已知代价(接受)」那一段**之前**)插入一节:

```
     * ## 🔴 之四:为什么必须显式把视频压到最低
     *
     * 不带码率/尺寸/帧率参数时,服务端对视频做**流拷贝**。客户端虽然用
     * [audioOnlyTrackSelectionParameters] 禁用了视频轨(铁律 1 不受影响),但混流的字节
     * **必须整条下载**才能取出音频 —— 于是"只听音频"要付整条视频流的带宽。
     *
     * 实测(2026-08-15,4K HEVC 50fps 37.4 Mbps 的 `.ts` 片源,20/60 秒采样):
     *
     * | | 实际码率 | 相对实时 |
     * |---|---|---|
     * | 流拷贝(修复前) | **54.5 Mbps** | 播不了 |
     * | 加上限之后 | **0.51 Mbps** | **2.2 倍实时** |
     *
     * 代价是服务端要真转码,实测起播 25.7 秒(流拷贝时 5.8 秒)。接受:L3 是罕见兜底,
     * 而"起播慢一点"和"根本播不了"之间没什么好权衡的。
```

- [ ] **Step 4: 跑测试,确认通过**

```bash
./gradlew :core:player:testDebugUnitTest --tests '*PlaybackSourceResolverTest*'
```

预期:全 PASS。

- [ ] **Step 5: 变异验证**

逐个把新加的七个 `append` 注释掉再跑,每次都必须 FAIL(七次)。全部恢复后重跑确认绿。

- [ ] **Step 6: 跑整个模块**

```bash
./gradlew :core:player:testDebugUnitTest
```

- [ ] **Step 7: Commit**

```bash
git add core/player/src/main/java/dev/insua/jellycast/player/PlaybackSourceResolver.kt \
        core/player/src/test/java/dev/insua/jellycast/player/PlaybackSourceResolverTest.kt
git commit -m "🐛 (player): cap L3 fallback video bitrate so 4K sources stop saturating the link"
```

---

## Task 3: 播放器自己的 HTTP 超时(R2)

**Files:**
- Modify: `core/player/src/main/java/dev/insua/jellycast/player/JellyCastPlayerFactory.kt`
- Modify: `core/player/build.gradle.kts`
- Create: `core/player/src/androidTest/java/dev/insua/jellycast/player/SlowResponsePlaybackDeviceTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `STREAM_CONNECT_TIMEOUT_MS` / `STREAM_READ_TIMEOUT_MS`。
- Produces: `fun audioOnlyDataSourceFactory(context: Context): DataSource.Factory`。

**背景:** `createAudioOnlyPlayer` 现在用 `DefaultMediaSourceFactory(context, extractorsFactory)`,底下是 media3 默认的 `DefaultHttpDataSource`——`DEFAULT_CONNECT_TIMEOUT_MILLIS = 8000`、`DEFAULT_READ_TIMEOUT_MILLIS = 8000`(从 media3 1.10.1 的 class 常量读出)。**比探测的 10 秒还短。** 只修 Task 1 不修这里,结果是探测说"能走 L1",播放器随即 `Source error`。

这是**平台行为**,JVM 结构上测不出(v4 铁律),必须设备测试。

- [ ] **Step 1: 给 androidTest 加 MockWebServer**

`core/player/build.gradle.kts` 的 `androidTestImplementation` 那一组末尾加:

```kotlin
    // SlowResponsePlaybackDeviceTest:用本地 HTTP server 模拟"服务端起转码要十几秒才吐响应头"。
    androidTestImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 2: 写会失败的设备测试**

`core/player/src/androidTest/java/dev/insua/jellycast/player/SlowResponsePlaybackDeviceTest.kt`:

```kotlin
package dev.insua.jellycast.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R2 回归(设计文档 §3.2)。
 *
 * media3 1.10.1 的 `DefaultHttpDataSource` 默认 connect/read 各 **8 秒**。而 Jellyfin 为 4K `.ts`
 * 片源起 L1 转码,实测 6.3–46.1 秒才吐响应头 —— 播放器因此在探测**已经**判定可以走 L1 之后
 * 仍然 `Source error`,一秒都没播出来。
 *
 * 这是纯平台行为(v4 铁律:JVM 结构上测不出),需要真实 [ExoPlayer] + 真实网络栈。
 *
 * 用真实的裸 ADTS 字节做响应体(和 `LocalAdtsSeekableDeviceTest` 同一份 fixture),
 * 让 `AdtsExtractor` 走的是和生产完全一样的解析路径。
 */
@RunWith(AndroidJUnit4::class)
class SlowResponsePlaybackDeviceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private lateinit var server: MockWebServer
    private var player: ExoPlayer? = null

    @Before
    fun setUp() {
        val adts = instrumentation.context.assets.open(FIXTURE_ASSET_NAME).use { it.readBytes() }
        server = MockWebServer().apply {
            enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/aac")
                    .setBody(Buffer().write(adts))
                    .setHeadersDelay(HEADERS_DELAY_SECONDS, TimeUnit.SECONDS),
            )
            start()
        }
    }

    @After
    fun tearDown() {
        runCatching { onMain { player?.release() } }
        runCatching { server.shutdown() }
    }

    /**
     * 响应头延迟 12 秒(> media3 默认 8 秒,< 生产值 60 秒),播放器必须仍能把流打开并进入
     * `STATE_READY`,而不是抛 `Source error`。
     *
     * 变异验证:把 [createAudioOnlyPlayer] 里 `setMediaSourceFactory` 的
     * [audioOnlyDataSourceFactory] 接线去掉(退回 media3 默认数据源),这条必须失败。
     */
    @Test
    fun `响应头延迟12秒时音频专用播放器仍能打开流`() {
        val exoPlayer = onMain { createAudioOnlyPlayer(context) }
        player = exoPlayer

        val readyLatch = CountDownLatch(1)
        val failure = AtomicReference<PlaybackException?>(null)
        onMain {
            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) readyLatch.countDown()
                }

                override fun onPlayerError(error: PlaybackException) {
                    failure.set(error)
                    readyLatch.countDown()
                }
            })
            exoPlayer.setMediaItem(MediaItem.fromUri(server.url("/Audio/x/universal").toString()))
            exoPlayer.prepare()
        }

        val settled = readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertNull("播放器在慢响应头上报错了:${failure.get()?.errorCodeName}", failure.get())
        assertTrue("等了 ${READY_TIMEOUT_SECONDS}s 播放器仍未进入 STATE_READY", settled)
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    private companion object {
        /** 大于 media3 默认的 8 秒,小于生产值 60 秒 —— 只有修好了才过得去。 */
        const val HEADERS_DELAY_SECONDS = 12L
        const val READY_TIMEOUT_SECONDS = 40L

        /** 与 `LocalAdtsSeekableDeviceTest` 共用同一份 fixture。 */
        const val FIXTURE_ASSET_NAME = "bare-adts-fixture.aac"
    }
}
```

> ⚠️ 先打开 `LocalAdtsSeekableDeviceTest.kt` 确认 `FIXTURE_ASSET_NAME` 的真实取值与 asset 路径,**以那个文件为准**,不要照抄本计划里的字面量。

- [ ] **Step 3: 起模拟器并跑测试,确认失败**

```bash
/home/insua/Android/Sdk/emulator/emulator -list-avds
/home/insua/Android/Sdk/emulator/emulator -avd <名字> -no-snapshot-load -no-audio &
adb wait-for-device
./gradlew :core:player:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.player.SlowResponsePlaybackDeviceTest
```

预期:**FAIL**,错误是 `PlaybackException` / `Source error`(media3 默认 8 秒读超时)。把真实输出记下来。

> 起模拟器那条命令用 `&` 放后台是允许的(它是长驻进程,不是构建任务);**Gradle 命令一律前台**。

- [ ] **Step 4: 改 JellyCastPlayerFactory**

新增函数(放在 `audioOnlyMediaSourceFactory` **之前**):

```kotlin
/**
 * L1/L3 远端流专用的 HTTP 数据源工厂。
 *
 * media3 1.10.1 的 `DefaultHttpDataSource` 默认 connect/read 各 8 秒
 * (`DEFAULT_CONNECT_TIMEOUT_MILLIS` / `DEFAULT_READ_TIMEOUT_MILLIS`)。而 Jellyfin 为大文件
 * 起转码要多久,取决于源容器 —— MPEG-TS 没有全局索引,ffmpeg 必须先扫描:实测同一台服务器上
 * 1080p h264 mkv 的首字节 1.2–2.4 秒,4K HEVC 50fps 的 `.ts` 要 6.3–46.1 秒。
 * **8 秒比 [HttpStreamProbe] 那边的坑还浅**:探测判定"可以走 L1"之后,播放器自己在同一条 URL
 * 上超时,表现是 `Source error`,一秒都没播出来。
 *
 * 超时与探测取**同一组常量**([STREAM_CONNECT_TIMEOUT_MS] / [STREAM_READ_TIMEOUT_MS]):
 * 两处面对的是同一个服务端行为,分别取值只会在下次调整时漏掉一处。
 *
 * 本地缓存文件不走 HTTP,不受这里影响。
 */
fun audioOnlyDataSourceFactory(context: Context): DataSource.Factory =
    DefaultDataSource.Factory(
        context,
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(STREAM_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(STREAM_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true),
    )
```

把 `audioOnlyMediaSourceFactory` 改成:

```kotlin
fun audioOnlyMediaSourceFactory(context: Context): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(
        audioOnlyDataSourceFactory(context),
        DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
    )
```

新增 import:

```kotlin
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
```

同时在 `audioOnlyMediaSourceFactory` 的 KDoc 末尾补一句:「数据源换成了 [audioOnlyDataSourceFactory](见该函数 KDoc:media3 默认 8 秒超时扛不住大文件转码的首字节延迟),恒定码率 seek 的行为不受影响。」

> `DefaultMediaSourceFactory(DataSource.Factory, ExtractorsFactory)` 这个重载在 media3 1.10.1 存在。若编译不过,改用
> `DefaultMediaSourceFactory(context, extractorsFactory).setDataSourceFactory(audioOnlyDataSourceFactory(context))`,并在报告里说明用了哪个。

- [ ] **Step 5: 重跑设备测试,确认通过**

```bash
./gradlew :core:player:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.player.SlowResponsePlaybackDeviceTest
```

- [ ] **Step 6: 变异验证**

把 `createAudioOnlyPlayer` 里的 `.setMediaSourceFactory(audioOnlyMediaSourceFactory(context))` 临时删掉 → 重跑 → 必须 FAIL。改回。

- [ ] **Step 7: 跑 `:core:player` 全部设备测试,确认没打破 `LocalAdtsSeekableDeviceTest`**

```bash
./gradlew :core:player:connectedDebugAndroidTest
```

- [ ] **Step 8: Commit**

```bash
git add core/player/src/main/java/dev/insua/jellycast/player/JellyCastPlayerFactory.kt \
        core/player/build.gradle.kts \
        core/player/src/androidTest/java/dev/insua/jellycast/player/SlowResponsePlaybackDeviceTest.kt
git commit -m "🐛 (player): raise ExoPlayer http timeouts so slow transcode starts do not fail playback"
```

---

## Task 4: 端到端 —— 高码率片源真的走 L1 且出声

**Files:**
- Create: `app/src/androidTest/java/dev/insua/jellycast/e2e/HighBitrateSourceE2eTest.kt`

**Interfaces:**
- Consumes: Task 1/2/3 的全部修复;既有的 `TestCredentials`、`PlaybackE2eTest` 的装配套路。
- Produces: 无。

**背景:** 用户已多次指出端到端一直是空白,而本仓库最严重的一个缺陷(缓存文件不可 seek)正是靠一次真机探针才发现的。**这一条不做,这次修复就没有被验证过。**

**动手前必读** `app/src/androidTest/java/dev/insua/jellycast/e2e/PlaybackE2eTest.kt` 的整个装配部分(`@Before`、`startPlayback`、`onMain`、条件等待的写法),本任务**严格复刻**它,不要另起一套。

- [ ] **Step 1: 写测试**

要点(不要偏离):

1. 开头调 `TestCredentials.assumeConfigured()`。
2. **动态挑片源,不写死任何条目 id 或剧名。** 遍历 `api.items(userId, types = "Episode", limit = 200).items`,对每个候选调 `api.playbackInfo(id, userId)`,挑第一个 `mediaSources.first().container?.contains("ts", ignoreCase = true) == true` 的。
   - 挑不到就 `throw AssertionError("服务器上找不到 MPEG-TS 容器的剧集,无法验证高码率片源回归。")` —— 按本仓库既有做法(见 `pickPlayableItems`),凑不出条件是**需要人看一眼的事实**,不是"环境没配"。
3. 用 `PlaybackSourceResolver` 直接 `resolve(itemId, userId, 0L)`,断言 `source.level == AudioDeliveryLevel.SERVER_AUDIO_ONLY`。**这一条就是 R1 的回归**:修复前这里会是 `CLIENT_VIDEO_DISABLED`。
4. 然后走 `startPlayback(...)` 的同一条生产路径,轮询 `engine.absolutePositionMs` **确实在前进**(至少推进 3000 ms),超时 90 秒。
   - **断行为不断"没抛异常"**:`play()` 正常返回什么也证明不了,用户的抱怨是"没有声音"。
5. 记录并 `android.util.Log` 输出首次出声耗时(毫秒),消息里**不许**出现 URL/token —— 要提到就过 `TestCredentials.redact(...)`。
6. `@After` 里 stop 播放并释放,避免在 NAS 上留下转码任务。

- [ ] **Step 2: 跑,确认通过**

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.e2e.HighBitrateSourceE2eTest
```

- [ ] **Step 3: 变异验证 —— 这一步是本任务的重点**

把 Task 1 的超时派生临时改回 `private val client: OkHttpClient = client`,重跑本测试。

**必须 FAIL**(`level` 是 `CLIENT_VIDEO_DISABLED`,或者播放位置推不动)。

若**仍然绿**,说明当时那台服务器的这一集恰好在 10 秒内起了转码 —— 这条端到端就没有稳定的鉴别力。**如实报告**,并在测试的 KDoc 里写明它是冒烟而非回归护栏(照抄 `PlaybackE2eTest` 里已有的那种诚实声明),不要假装它挡得住回归。

改回,重跑确认绿。

- [ ] **Step 4: 报告里必须写清楚**

- 挑中的是哪一类片源(容器/编码/分辨率,**不带条目 id**)
- 修复前 / 修复后的 `level`
- 首次出声耗时
- 变异验证的真实输出

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/dev/insua/jellycast/e2e/HighBitrateSourceE2eTest.kt
git commit -m "✅ (e2e): verify MPEG-TS high bitrate episodes resolve to L1 and actually play"
```

---

## Task 5: 列表滚动位置 —— 复现并定位(**只复现,不修**)

**Files:**
- Create: `app/src/androidTest/java/dev/insua/jellycast/navigation/ListScrollRestoreTest.kt`

**Interfaces:**
- Consumes: `LibraryScreenContent`(`:feature:library` 的纯函数入口)、`LibraryUiState`。
- Produces: 一条**红**测试 + 一份根因报告,供 Task 6 使用。

**这个任务不许改任何生产代码。** 交付物是"能失败的测试 + 说清楚为什么失败"。设计文档 §7.4 明确要求先复现再定修法。

**已经排除的原因(别再查一遍):** 不是"忘了用 `rememberSaveable`"。四个列表用的都是 saveable 支撑的滚动状态:
`LibraryScreen.kt:227` 是 `key(tab, isSearching) { rememberLazyGridState() }`,`LibraryContentsScreen.kt:124` 是 `rememberLazyGridState()`,`CollectionDetailScreen.kt:87` 与 `SeriesDetailScreen.kt:81` 用组件内部默认的 saveable 状态。

**首要嫌疑(要证实或证伪,不要当结论):** `LazyGrid` 恢复索引之后,**第一次测量时如果列表是空的,索引会被夹到 0 并覆盖掉恢复值**。而返回时列表可能为空,有两条路径:
- **(a) 从底部导航栏返回。** `JellyCastNavHost.kt:266` 用 `popUpTo(Routes.HOME) { saveState = true }` + `restoreState = true`。`saveState` 保的是 `SavedStateHandle`,但被弹出的 `NavBackStackEntry` 会销毁,**它的 `ViewModelStore` 随之清空** → `LibraryViewModel` 重建 → 分页数据全丢 → 重新拉第一页。
- **(b) 用返回手势。** `LIBRARY` 条目留在返回栈上,ViewModel 存活,理论上不该丢。实测若也丢,另有机制。

- [ ] **Step 1: 搭测试骨架**

用 `createComposeRule()`(不需要 Activity、不需要 Hilt、不需要服务器),在测试里手搭一个 `NavHost`,**逐字复刻** `JellyCastNavHost` 的导航选项:

- 目的地 `"library"`:渲染 `LibraryScreenContent`,喂一个手工构造的 `LibraryUiState`(至少 60 条 item,保证能滚动)。列表数据由一个**挂在该 `NavBackStackEntry` 的 `ViewModel`** 持有 —— 这是复现 (a) 的关键,数据直接写死在 composable 里就复现不出来了。
- 目的地 `"detail/{id}"`:随便渲染一个 `Text`。
- 一个"底部导航栏"按钮,`onClick` 里用和 `JellyCastNavHost.kt:266` **完全一样**的 `navigate` 选项。

`:app` 的 androidTest 已有 Compose test 与 navigation 依赖;若缺 `androidTestImplementation(libs.navigation.compose)` 之类的,补上并在报告里说明。

- [ ] **Step 2: 写两条用例**

```
1. `返回手势回到列表时滚动位置保持`
   滚到 index 40 → 点某个 item 进 detail → Espresso.pressBack() →
   断言列表的 firstVisibleItemIndex 仍 >= 35

2. `从底部导航栏回到列表时滚动位置保持`
   滚到 index 40 → 进 detail → 点"底部导航栏"的列表按钮 →
   断言 firstVisibleItemIndex 仍 >= 35
```

用 `>= 35` 而不是 `== 40`:测量差异、item 高度取整都会让它偏一两格,断死具体值是脆测试。但 `>= 35` 和 `0` 之间差距足够大,鉴别力不受影响。

拿 `firstVisibleItemIndex` 的办法:把 `rememberLazyGridState()` 提到测试自己手里传进去,**但必须仍然用 `rememberSaveable` 的那个 `rememberLazyGridState()`**,不能换成裸 `remember` —— 换了就把被测行为改掉了。

- [ ] **Step 3: 跑,记录哪条红哪条绿**

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.insua.jellycast.navigation.ListScrollRestoreTest
```

**两条都绿 = 复现失败**,这时**不要**去改生产代码。回来报告"手搭的模型复现不出来",并说明模型和真实 `JellyCastNavHost` 还差在哪(例如真实 ViewModel 有网络加载、首帧列表为空,而模型里数据是同步就绪的)。这种情况下下一步是把模型改得更像生产(比如让 ViewModel 的数据在重建后**异步**才到货),而不是猜着改代码。

- [ ] **Step 4: 报告**

必须写清楚:

- 哪条红、哪条绿、真实断言输出
- 红的那条,`firstVisibleItemIndex` 实际是多少
- 你验证出的机制是什么,以及**你是怎么验证的**(比如加日志确认了 ViewModel 被重建、确认了首次测量时 item 数为 0)
- 如果机制和上面 (a)/(b) 的猜测不同,以你的实测为准

- [ ] **Step 5: Commit(红测试也要提交)**

```bash
git add app/src/androidTest/java/dev/insua/jellycast/navigation/ListScrollRestoreTest.kt
git commit -m "✅ (navigation): add failing test reproducing list scroll reset after returning from detail"
```

---

## Task 6: 列表滚动位置 —— 修复

**⚠️ 这个任务的内容由编排者在 Task 5 交付之后补全。**

理由:设计文档 §7.4 明确要求"拿到红色测试与真实机制之后再定修法,不允许照着猜测直接改代码"。在这里先写一份具体修法,就是把猜测伪装成计划 —— 本仓库已经因为"计划里写错的一条指令"返过一次工(音频缓存 Task 4)。

**已经定下、Task 5 的结论不会改变的约束:**

- 修法方向是**让列表在返回时不为空**(保住数据),不是"把滚动索引再存一份"。后者是绕过症状:数据还没回来时把索引强行设回去,只会滚到一个尚未加载的位置。
- 范围:媒体库(剧集/电影/合集三个 Tab 各自独立的滚动位置)、按库浏览、合集详情、剧集详情。**搜索结果不算** —— 搜索结果本来就不缓存(见 `LibraryViewModel` 类 KDoc)。
- Task 5 那两条用例必须由红转绿,且要做变异验证。
- 既有的 `LibraryScreenTest` / `LibraryContentsScreenTest` / `LibraryPullToRefreshTest` / `LibraryViewModelTest` 必须保持绿。

---

## Task 7: 全量回归与收尾

**Files:** 无新增。

- [ ] **Step 1: 跑全部 JVM 单测**

```bash
./gradlew test
```

预期:全绿(基线 648 条 + 本次新增)。

- [ ] **Step 2: 跑全部设备测试**

```bash
./gradlew connectedDebugAndroidTest
```

- [ ] **Step 3: 编译 release 确认没破坏打包**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 4: 复核铁律**

- 全项目搜 `OkHttpClient.Builder()` —— 本次改动**不许**新增任何一处(铁律 5)
- 全项目搜 `PlayerView` / `setVideoSurface` —— 必须仍然是零(铁律 1)
- 确认 `testing.properties` 仍未被 git 跟踪:`git check-ignore -v testing.properties`
- 确认新增代码与报告里没有任何服务器地址 / 账号 / 密码 / token

- [ ] **Step 5: 更新设计文档的实测记录(如果 Task 5/6 的结论与 §7.3 的猜测不同)**

改 `docs/superpowers/specs/2026-08-15-high-bitrate-playback-and-scroll-restore-design.md` §7.3,把猜测替换成实测结论。

- [ ] **Step 6: Commit**

```bash
git commit -m "📝 (docs): record measured root cause for the list scroll reset"
```

---

## Self-Review(编排者已执行)

**Spec coverage:**

| spec 条目 | 任务 |
|---|---|
| §3.1 R1 探测超时 | Task 1 |
| §3.2 R2 播放器超时 | Task 3 |
| §3.3 R3 L3 降码率 | Task 2 |
| §4 HEAD/元数据已排除 | 不需要任务(计划里已写明"探测必须保持 GET") |
| §5.1 连接/读取两个超时分开 | Task 1 Step 2 第二、三条用例 |
| §5.2 两处共用同一组常量 | Task 1 Step 1 + Task 3 Step 4 + Global Constraints |
| §6 探测白起一次转码(不做) | 无任务 —— 已明确列为不做 |
| §7 滚动位置 | Task 5(复现)+ Task 6(修复) |
| §8.1 JVM 测试 | Task 1 Step 2、Task 2 Step 1 |
| §8.2 设备测试 | Task 3、Task 5 |
| §8.3 端到端 | Task 4 |
| §9 验收标准 | Task 7 |

**Type consistency:** `STREAM_CONNECT_TIMEOUT_MS` / `STREAM_READ_TIMEOUT_MS` 为 `Int`(media3 的 `setConnectTimeoutMs` 收 `Int`),OkHttp 侧用 `.toLong()` 转换 —— Task 1 与 Task 3 一致。`audioOnlyDataSourceFactory` 返回 `DataSource.Factory`,`audioOnlyMediaSourceFactory` 返回 `DefaultMediaSourceFactory`,两处签名前后一致。

**已知的计划缺口(有意为之):** Task 6 的步骤留待 Task 5 结论补全,理由见该任务。
