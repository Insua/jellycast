# JellyCast 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`(推荐)或 `superpowers:executing-plans` 逐任务实现本计划。步骤使用 checkbox(`- [ ]`)语法跟踪进度。

**Goal:** 构建一个 Android 应用,以播客/音乐播放器的形态收听自建 Jellyfin 服务器上的剧集与电影音频,全程不渲染视频,并把字幕渲染成歌词。

**Architecture:** 多模块 Kotlin 项目。`:core:network` 负责 Jellyfin API 与多接入地址自动选路;`:core:player` 封装 Media3 与三级音频降级链;`:core:subtitle` 独立拉取并解析文本字幕供歌词视图使用;`:feature:*` 为 Compose UI。核心逻辑(选路、降级、字幕解析)均为纯 Kotlin,可离线单测。

**Tech Stack:** Kotlin · Jetpack Compose · Material 3 · Media3(ExoPlayer + MediaSessionService)· Retrofit + OkHttp · kotlinx.serialization · Hilt · Room · DataStore · Coil · JUnit5 + MockK + Turbine

## Global Constraints

- 设计文档:`docs/superpowers/specs/2026-07-25-jellycast-design.md` — 所有需求以该文档为准。
- **minSdk = 26,targetSdk = 最新稳定版**,Kotlin JVM target 17。
- **永远不绑定 `Surface`,永远不渲染视频画面。**
- **禁止凭记忆编写 Jellyfin API 调用。** 每个接口在实现前必须核对目标服务器的 `{server}/api-docs/swagger.json`。
- 所有网络与解析逻辑必须可在无服务器环境下单测(用假数据 / MockWebServer)。
- 不允许全局关闭 TLS 校验;自签证书只能按 endpoint 指纹白名单信任。
- 每个 Task 结束必须 commit,commit message 用 Conventional Commits(`feat:` / `fix:` / `test:` / `chore:`)。
- 单元测试必须先写、先失败、再实现(TDD)。

---

## 文件结构总览

```
jellycast/
├── settings.gradle.kts                       模块注册
├── build.gradle.kts                          根构建脚本
├── gradle/libs.versions.toml                 版本目录(所有依赖版本集中于此)
├── app/
│   └── src/main/java/dev/insua/jellycast/
│       ├── JellyCastApp.kt                   Application(Hilt 入口)
│       ├── MainActivity.kt                   单 Activity
│       └── navigation/JellyCastNavHost.kt    导航图
├── core/
│   ├── model/       …/model/                 Server, Endpoint, MediaItem, Episode, SubtitleLine, PlaybackSource
│   ├── network/     …/network/               JellyfinApi, AuthInterceptor, EndpointSelector, CertificatePolicy
│   ├── database/    …/database/              Room: CachedItemDao, ProgressReportDao
│   ├── datastore/   …/datastore/             ServerStore, PreferencesStore
│   ├── player/      …/player/                PlaybackSourceResolver, JellyCastPlayer, PlaybackService, PlayQueue
│   ├── subtitle/    …/subtitle/              SubtitleParser(Srt/Ass/Vtt), SubtitleTimeline, SubtitleRepository
│   └── designsystem/…/designsystem/          Theme, MiniPlayerBar, PosterCard
└── feature/
    ├── server/      服务器列表 / 添加 / 登录
    ├── home/        "在听"页
    ├── library/     剧集 / 季 / 集 / 电影
    ├── player/      全屏播放页 + 歌词视图
    └── settings/    设置
```

---

# Phase 0:技术验证与骨架

### Task 0:Spike — 验证 Jellyfin 音频与字幕能力

**这是阻塞级任务。在得出结论前不得开始 Phase 2。**

**Files:**
- Create: `docs/superpowers/specs/2026-07-25-spike-results.md`

- [ ] **Step 1: 取得服务器 OpenAPI 文档**

```bash
# 把 SERVER 换成实际可达地址(Tailscale 或局域网)
export SERVER="http://100.x.x.x:8096"
curl -s "$SERVER/api-docs/swagger.json" -o /tmp/jellyfin-openapi.json || \
  curl -s "$SERVER/openapi.json" -o /tmp/jellyfin-openapi.json
jq '.paths | keys | length' /tmp/jellyfin-openapi.json
```
预期:输出接口数量(几百)。把该文件复制到 `docs/jellyfin-openapi.json` 作为实现时的权威参考。

- [ ] **Step 2: 登录取得 token**

```bash
curl -s -X POST "$SERVER/Users/AuthenticateByName" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: MediaBrowser Client="JellyCastSpike", Device="cli", DeviceId="spike-001", Version="0.0.1"' \
  -d '{"Username":"<用户名>","Pw":"<密码>"}' | jq '{AccessToken, UserId: .User.Id}'
```
预期:返回 `AccessToken` 与 `UserId`。导出为 `$TOKEN` 与 `$UID`。

- [ ] **Step 3: 找一集剧,取得 PlaybackInfo**

```bash
EP=$(curl -s "$SERVER/Users/$UID/Items?IncludeItemTypes=Episode&Recursive=true&Limit=1" \
  -H "Authorization: MediaBrowser Token=\"$TOKEN\"" | jq -r '.Items[0].Id')

curl -s -X POST "$SERVER/Items/$EP/PlaybackInfo?userId=$UID" \
  -H "Authorization: MediaBrowser Token=\"$TOKEN\"" \
  -H 'Content-Type: application/json' -d '{}' \
  | jq '.MediaSources[0] | {Id, Container, TranscodingUrl, SupportsTranscoding, MediaStreams: [.MediaStreams[] | {Type, Codec, Index, IsTextSubtitleStream, Language}]}'
```
记录:`MediaSources[0].Id`(下称 `$MSID`)、音频轨 codec、字幕轨的 `Index` 与 `IsTextSubtitleStream`。

- [ ] **Step 4: 逐一测试纯音频候选 URL(Spike-1 核心)**

对下列每个 URL 执行,记录 **HTTP 状态、Content-Type、实际码率、NAS CPU 占用**:

```bash
probe() {  # $1 = 描述, $2 = URL
  echo "=== $1"
  curl -s -o /tmp/out.bin -D /tmp/hdr.txt --max-time 20 "$2"
  head -1 /tmp/hdr.txt; grep -i content-type /tmp/hdr.txt
  ls -lh /tmp/out.bin
  ffprobe -v error -show_streams /tmp/out.bin 2>/dev/null | grep codec_type | sort | uniq -c
}

AUTH="api_key=$TOKEN"
probe "A. stream.mp3"        "$SERVER/Videos/$EP/stream.mp3?$AUTH&mediaSourceId=$MSID"
probe "B. audioCodec only"   "$SERVER/Videos/$EP/stream?$AUTH&mediaSourceId=$MSID&audioCodec=aac&static=false"
probe "C. hls master"        "$SERVER/Videos/$EP/master.m3u8?$AUTH&mediaSourceId=$MSID&audioCodec=aac"
probe "D. universal audio"   "$SERVER/Audio/$EP/universal?$AUTH&audioCodec=aac"
```

判定标准:`ffprobe` 输出中 **只有 `codec_type=audio`、没有 `video`** 即为 L1 成功。
对 C(HLS),额外 `cat /tmp/out.bin` 检查是否存在独立的 `EXT-X-MEDIA:TYPE=AUDIO` rendition(L2)。

测试期间在群晖上观察 CPU:
```bash
ssh <nas> "top -bn1 | grep -i ffmpeg"
```

- [ ] **Step 5: 测试字幕文本获取(Spike-2)**

```bash
SUBIDX=$(curl -s "$SERVER/Users/$UID/Items/$EP" -H "Authorization: MediaBrowser Token=\"$TOKEN\"" \
  | jq -r '[.MediaStreams[] | select(.Type=="Subtitle" and .IsTextSubtitleStream==true)][0].Index')
echo "text subtitle index = $SUBIDX"

for FMT in srt vtt js; do
  echo "=== $FMT"
  curl -s --max-time 15 "$SERVER/Videos/$EP/$MSID/Subtitles/$SUBIDX/Stream.$FMT?$AUTH" | head -20
done
```
记录:哪种格式可用、时间轴精度、`js`(JSON)格式的字段结构。

- [ ] **Step 6: 测试公网 IPv6 + HTTPS(Spike-3)**

在**关闭 WiFi、仅用移动网络**的手机热点环境下:
```bash
curl -sv --max-time 10 "https://<你的ddns域名>:8920/System/Info/Public" 2>&1 | tail -25
```
记录:是否连通、证书是否被信任、是否走了 IPv6。

- [ ] **Step 7: 写下结论**

创建 `docs/superpowers/specs/2026-07-25-spike-results.md`,必须包含:

```markdown
# Spike 结论(2026-07-25)

## Spike-1 纯音频流
- L1 可行性: [可行 / 不可行]
- 可用 URL 模板: `...`
- 实测码率: __ kbps    NAS ffmpeg CPU: __%
- L2 (HLS 音频 rendition) 可行性: [...]
- **结论:降级链起始级别 = L?**

## Spike-2 字幕
- 可用格式: [srt / vtt / js]
- 时间轴精度: __
- 位图字幕识别字段: `MediaStreams[].IsTextSubtitleStream`

## Spike-3 公网
- IPv6 直连: [通 / 不通]
- 证书: [受信 / 自签]
```

同时把结论回填到设计文档 §3.1 与 §9。

- [ ] **Step 8: Commit**

```bash
git add docs/
git commit -m "docs: record Jellyfin audio/subtitle/network spike results"
```

---

### Task 1:Android 项目骨架

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/dev/insua/jellycast/JellyCastApp.kt`, `MainActivity.kt`

**Interfaces:**
- Produces: 可编译运行的空壳应用;`libs.versions.toml` 中的依赖别名供后续所有模块引用。

- [ ] **Step 1: 写 `.gitignore`**

```gitignore
*.iml
.gradle/
local.properties
.idea/
build/
captures/
.externalNativeBuild/
.cxx/
*.apk
*.keystore
```

- [ ] **Step 2: 写 `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.0"
kotlin = "2.0.20"
compose-bom = "2024.09.03"
media3 = "1.4.1"
hilt = "2.52"
retrofit = "2.11.0"
okhttp = "4.12.0"
serialization = "1.7.3"
room = "2.6.1"
datastore = "1.1.1"
coil = "2.7.0"
junit5 = "5.11.0"
mockk = "1.13.12"
turbine = "1.1.0"
coroutines = "1.9.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }
androidx-lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version = "2.8.6" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version = "1.9.2" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
navigation-compose = { module = "androidx.navigation:navigation-compose", version = "2.8.2" }
media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
media3-exoplayer-hls = { module = "androidx.media3:media3-exoplayer-hls", version.ref = "media3" }
media3-session = { module = "androidx.media3:media3-session", version.ref = "media3" }
media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { module = "androidx.hilt:hilt-navigation-compose", version = "1.2.0" }
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-serialization = { module = "com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter", version = "1.0.0" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
coil-compose = { module = "io.coil-kt:coil-compose", version.ref = "coil" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit5" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version = "2.0.20-1.0.25" }
```

- [ ] **Step 3: 写 `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "JellyCast"
include(":app")
include(":core:model", ":core:network", ":core:database", ":core:datastore",
        ":core:player", ":core:subtitle", ":core:designsystem")
include(":feature:server", ":feature:home", ":feature:library",
        ":feature:player", ":feature:settings")
```

- [ ] **Step 4: 写根 `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 5: 写 `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.insua.jellycast"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.insua.jellycast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:player"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:server"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.tooling)
}
```

- [ ] **Step 6: 写 `AndroidManifest.xml`**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:name=".JellyCastApp"
        android:label="JellyCast"
        android:usesCleartextTraffic="true"
        android:theme="@style/Theme.JellyCast">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

> `usesCleartextTraffic="true"` 是必需的:局域网与 Tailscale 走 HTTP。

- [ ] **Step 7: 写 Application 与 MainActivity**

```kotlin
// JellyCastApp.kt
package dev.insua.jellycast

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class JellyCastApp : Application()
```

```kotlin
// MainActivity.kt
package dev.insua.jellycast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import dev.insua.jellycast.navigation.JellyCastNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JellyCastNavHost() }
    }
}
```

- [ ] **Step 8: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL(此时 NavHost 尚未创建,先写一个空的占位 Composable 让其通过编译)

- [ ] **Step 9: Commit**

```bash
git add .
git commit -m "chore: scaffold multi-module Android project"
```

---

# Phase 1:数据与网络基础

### Task 2:core:model — 领域模型

**Files:**
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/main/java/dev/insua/jellycast/model/Server.kt`
- Create: `core/model/src/main/java/dev/insua/jellycast/model/Media.kt`
- Create: `core/model/src/main/java/dev/insua/jellycast/model/Playback.kt`
- Create: `core/model/src/main/java/dev/insua/jellycast/model/Subtitle.kt`

**Interfaces:**
- Produces: 全项目共享的数据类型。后续所有模块依赖此模块。

- [ ] **Step 1: 写 `Server.kt`**

```kotlin
package dev.insua.jellycast.model

data class Server(
    val id: String,
    val name: String,
    val endpoints: List<Endpoint>,
    val userId: String? = null,
    val accessToken: String? = null,
)

data class Endpoint(
    val url: String,          // 形如 http://192.168.1.10:8096 或 https://[240e::1]:8920
    val label: String,        // "局域网" / "Tailscale" / "公网"
    val priority: Int,        // 数字越小越优先
    val trustedCertSha256: String? = null,  // 自签证书指纹白名单
)

data class EndpointHealth(
    val endpoint: Endpoint,
    val reachable: Boolean,
    val latencyMs: Long?,
    val failureReason: String? = null,
)
```

- [ ] **Step 2: 写 `Media.kt`**

```kotlin
package dev.insua.jellycast.model

enum class MediaKind { SERIES, SEASON, EPISODE, MOVIE }

data class MediaItem(
    val id: String,
    val kind: MediaKind,
    val name: String,
    val seriesName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val runTimeMs: Long? = null,
    val resumePositionMs: Long = 0L,
    val imageTag: String? = null,
)

/** 播放页展示用的标题组合 */
val MediaItem.displaySubtitle: String
    get() = when (kind) {
        MediaKind.EPISODE -> buildString {
            seriesName?.let { append(it) }
            if (seasonNumber != null && episodeNumber != null) {
                append(" · S%02dE%02d".format(seasonNumber, episodeNumber))
            }
        }
        else -> seriesName.orEmpty()
    }
```

- [ ] **Step 3: 写 `Playback.kt`**

```kotlin
package dev.insua.jellycast.model

/** 音频降级链的级别 */
enum class AudioDeliveryLevel {
    /** L1 服务端直出纯音频 */ SERVER_AUDIO_ONLY,
    /** L2 HLS 独立音频 rendition */ HLS_AUDIO_RENDITION,
    /** L3 拉完整流,客户端禁用视频轨 */ CLIENT_VIDEO_DISABLED,
}

data class PlaybackSource(
    val itemId: String,
    val mediaSourceId: String,
    val streamUrl: String,
    val level: AudioDeliveryLevel,
    val isHls: Boolean,
    val playSessionId: String?,
    val audioTracks: List<AudioTrack>,
    val textSubtitles: List<SubtitleTrackRef>,
)

data class AudioTrack(val index: Int, val language: String?, val displayName: String)

data class SubtitleTrackRef(
    val index: Int,
    val language: String?,
    val displayName: String,
    val isTextBased: Boolean,   // 位图字幕(PGS/VobSub)为 false,不展示
)
```

- [ ] **Step 4: 写 `Subtitle.kt`**

```kotlin
package dev.insua.jellycast.model

data class SubtitleLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/** 已排序的字幕时间轴,支持按播放位置二分查找当前行 */
class SubtitleTimeline(val lines: List<SubtitleLine>) {

    /** 返回当前应高亮的行索引;无匹配返回 -1 */
    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.lastIndex
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val line = lines[mid]
            when {
                positionMs < line.startMs -> hi = mid - 1
                positionMs > line.endMs -> { candidate = mid; lo = mid + 1 }
                else -> return mid
            }
        }
        // 落在两行之间时,不高亮任何行
        return if (candidate >= 0 && positionMs <= lines[candidate].endMs) candidate else -1
    }
}
```

- [ ] **Step 5: 写单测(先失败)**

`core/model/src/test/java/dev/insua/jellycast/model/SubtitleTimelineTest.kt`

```kotlin
package dev.insua.jellycast.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleTimelineTest {
    private val timeline = SubtitleTimeline(listOf(
        SubtitleLine(1000, 3000, "第一句"),
        SubtitleLine(4000, 6000, "第二句"),
        SubtitleLine(10000, 12000, "第三句"),
    ))

    @Test fun `位置落在某行区间内返回该行`() {
        assertEquals(0, timeline.indexAt(2000))
        assertEquals(1, timeline.indexAt(5000))
        assertEquals(2, timeline.indexAt(11000))
    }

    @Test fun `边界值包含在内`() {
        assertEquals(0, timeline.indexAt(1000))
        assertEquals(0, timeline.indexAt(3000))
    }

    @Test fun `落在两行之间不高亮`() {
        assertEquals(-1, timeline.indexAt(3500))
        assertEquals(-1, timeline.indexAt(8000))
    }

    @Test fun `第一行之前不高亮`() {
        assertEquals(-1, timeline.indexAt(0))
    }

    @Test fun `空时间轴不崩溃`() {
        assertEquals(-1, SubtitleTimeline(emptyList()).indexAt(1000))
    }
}
```

- [ ] **Step 6: 运行测试**

Run: `./gradlew :core:model:test`
Expected: PASS(5 个测试全绿)

- [ ] **Step 7: Commit**

```bash
git add core/model
git commit -m "feat(model): add domain models and subtitle timeline lookup"
```

---

### Task 3:core:network — Jellyfin API 接口与认证

**Files:**
- Create: `core/network/build.gradle.kts`
- Create: `core/network/src/main/java/dev/insua/jellycast/network/JellyfinApi.kt`
- Create: `core/network/src/main/java/dev/insua/jellycast/network/dto/JellyfinDto.kt`
- Create: `core/network/src/main/java/dev/insua/jellycast/network/AuthInterceptor.kt`
- Test: `core/network/src/test/java/dev/insua/jellycast/network/AuthInterceptorTest.kt`

**Interfaces:**
- Consumes: `:core:model`
- Produces: `JellyfinApi`(Retrofit 接口)、`AuthInterceptor(tokenProvider: () -> String?, deviceId: String)`

- [ ] **Step 1: 核对 OpenAPI(强制)**

```bash
jq '.paths | keys[] | select(test("AuthenticateByName|PlaybackInfo|NextUp|Subtitles"))' docs/jellyfin-openapi.json
```
逐一比对下方接口签名与真实文档,**不一致以 OpenAPI 为准并修正本计划**。

- [ ] **Step 2: 写认证拦截器测试(先失败)**

```kotlin
package dev.insua.jellycast.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthInterceptorTest {
    @Test fun `未登录时也带上客户端标识但无 Token`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("{}")); start() }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ null }, "device-123")).build()
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        val header = server.takeRequest().getHeader("Authorization")!!
        assertTrue(header.contains("""Client="JellyCast""""))
        assertTrue(header.contains("""DeviceId="device-123""""))
        assertTrue(!header.contains("Token="))
        server.shutdown()
    }

    @Test fun `已登录时附带 Token`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody("{}")); start() }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ "tok-abc" }, "device-123")).build()
        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()
        assertTrue(server.takeRequest().getHeader("Authorization")!!.contains("""Token="tok-abc""""))
        server.shutdown()
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :core:network:test --tests '*AuthInterceptorTest*'`
Expected: FAIL — `Unresolved reference: AuthInterceptor`

- [ ] **Step 4: 实现 AuthInterceptor**

```kotlin
package dev.insua.jellycast.network

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val deviceId: String,
    private val deviceName: String = android.os.Build.MODEL ?: "Android",
    private val version: String = "0.1.0",
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val auth = buildString {
            append("""MediaBrowser Client="JellyCast", Device="$deviceName", """)
            append("""DeviceId="$deviceId", Version="$version"""")
            if (token != null) append(""", Token="$token"""")
        }
        return chain.proceed(
            chain.request().newBuilder().header("Authorization", auth).build()
        )
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :core:network:test --tests '*AuthInterceptorTest*'`
Expected: PASS

- [ ] **Step 6: 写 DTO 与 Retrofit 接口**

```kotlin
// dto/JellyfinDto.kt
package dev.insua.jellycast.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class PublicSystemInfoDto(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable data class AuthRequestDto(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

@Serializable data class AuthResultDto(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)
@Serializable data class UserDto(@SerialName("Id") val id: String, @SerialName("Name") val name: String)

@Serializable data class ItemsResponseDto(
    @SerialName("Items") val items: List<BaseItemDto> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("Type") val type: String,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("IndexNumber") val episodeNumber: Int? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("UserData") val userData: UserDataDto? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

@Serializable data class UserDataDto(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("Played") val played: Boolean = false,
)

@Serializable data class MediaStreamDto(
    @SerialName("Type") val type: String,               // Audio / Video / Subtitle
    @SerialName("Index") val index: Int,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("IsTextSubtitleStream") val isTextSubtitle: Boolean = false,
)

@Serializable data class PlaybackInfoResponseDto(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable data class MediaSourceDto(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)
```

**Ticks 换算:** Jellyfin 用 100 纳秒为单位。`毫秒 = ticks / 10_000`。全项目统一在 DTO→Model 映射层换算,禁止在 UI 层出现 ticks。

```kotlin
// JellyfinApi.kt
package dev.insua.jellycast.network

import dev.insua.jellycast.network.dto.*
import retrofit2.http.*

interface JellyfinApi {
    @GET("System/Info/Public")
    suspend fun publicInfo(): PublicSystemInfoDto

    @POST("Users/AuthenticateByName")
    suspend fun authenticate(@Body body: AuthRequestDto): AuthResultDto

    @GET("Users/{userId}/Items")
    suspend fun items(
        @Path("userId") userId: String,
        @Query("IncludeItemTypes") types: String,
        @Query("Recursive") recursive: Boolean = true,
        @Query("SortBy") sortBy: String = "SortName",
        @Query("Limit") limit: Int? = null,
        @Query("ParentId") parentId: String? = null,
    ): ItemsResponseDto

    @GET("Users/{userId}/Items/Resume")
    suspend fun resume(@Path("userId") userId: String): ItemsResponseDto

    @GET("Shows/NextUp")
    suspend fun nextUp(@Query("userId") userId: String, @Query("Limit") limit: Int = 20): ItemsResponseDto

    @GET("Shows/{seriesId}/Seasons")
    suspend fun seasons(@Path("seriesId") seriesId: String, @Query("userId") userId: String): ItemsResponseDto

    @GET("Shows/{seriesId}/Episodes")
    suspend fun episodes(
        @Path("seriesId") seriesId: String,
        @Query("seasonId") seasonId: String,
        @Query("userId") userId: String,
    ): ItemsResponseDto

    @GET("Users/{userId}/Items/{itemId}")
    suspend fun itemDetail(@Path("userId") userId: String, @Path("itemId") itemId: String): BaseItemDto

    @POST("Items/{itemId}/PlaybackInfo")
    suspend fun playbackInfo(
        @Path("itemId") itemId: String,
        @Query("userId") userId: String,
        @Body body: Map<String, String> = emptyMap(),
    ): PlaybackInfoResponseDto

    @POST("Sessions/Playing")
    suspend fun reportStart(@Body body: Map<String, @JvmSuppressWildcards Any>)

    @POST("Sessions/Playing/Progress")
    suspend fun reportProgress(@Body body: Map<String, @JvmSuppressWildcards Any>)

    @POST("Sessions/Playing/Stopped")
    suspend fun reportStop(@Body body: Map<String, @JvmSuppressWildcards Any>)
}
```

- [ ] **Step 7: 运行全部网络模块测试**

Run: `./gradlew :core:network:test`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add core/network
git commit -m "feat(network): add Jellyfin API interface, DTOs and auth interceptor"
```

---

### Task 4:core:network — 多接入地址自动选路

这是本项目**最有价值的差异化功能**:同一台服务器配多个地址,连接时自动选可达且最快的那个。

**Files:**
- Create: `core/network/src/main/java/dev/insua/jellycast/network/EndpointSelector.kt`
- Test: `core/network/src/test/java/dev/insua/jellycast/network/EndpointSelectorTest.kt`

**Interfaces:**
- Consumes: `:core:model` 的 `Endpoint` / `EndpointHealth`
- Produces:
  ```kotlin
  interface EndpointProbe { suspend fun probe(endpoint: Endpoint): EndpointHealth }
  class EndpointSelector(private val probe: EndpointProbe, private val timeoutMs: Long = 3000)
  suspend fun EndpointSelector.select(endpoints: List<Endpoint>): EndpointHealth?
  suspend fun EndpointSelector.probeAll(endpoints: List<Endpoint>): List<EndpointHealth>
  ```

- [ ] **Step 1: 写测试(先失败)**

```kotlin
package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private fun ep(url: String, p: Int) = Endpoint(url, "L$p", p)

class EndpointSelectorTest {

    private fun probeOf(vararg results: Pair<String, Pair<Boolean, Long>>) = object : EndpointProbe {
        private val map = results.toMap()
        override suspend fun probe(endpoint: Endpoint): EndpointHealth {
            val (ok, latency) = map[endpoint.url] ?: (false to 0L)
            delay(latency)
            return EndpointHealth(endpoint, ok, if (ok) latency else null,
                if (ok) null else "unreachable")
        }
    }

    @Test fun `返回最先成功响应的 endpoint`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://slow" to (true to 900L),
            "http://fast" to (true to 100L),
        ))
        val chosen = selector.select(listOf(ep("http://slow", 1), ep("http://fast", 2)))
        assertEquals("http://fast", chosen?.endpoint?.url)
    }

    @Test fun `跳过不可达的 endpoint`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://dead" to (false to 0L),
            "http://alive" to (true to 200L),
        ))
        val chosen = selector.select(listOf(ep("http://dead", 1), ep("http://alive", 2)))
        assertEquals("http://alive", chosen?.endpoint?.url)
    }

    @Test fun `全部不可达返回 null`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://a" to (false to 0L), "http://b" to (false to 0L),
        ))
        assertNull(selector.select(listOf(ep("http://a", 1), ep("http://b", 2))))
    }

    @Test fun `probeAll 返回全部结果用于诊断展示`() = runTest {
        val selector = EndpointSelector(probeOf(
            "http://a" to (false to 0L), "http://b" to (true to 50L),
        ))
        val all = selector.probeAll(listOf(ep("http://a", 1), ep("http://b", 2)))
        assertEquals(2, all.size)
        assertEquals(1, all.count { it.reachable })
        assertEquals("unreachable", all.first { !it.reachable }.failureReason)
    }

    @Test fun `空列表返回 null`() = runTest {
        assertNull(EndpointSelector(probeOf()).select(emptyList()))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:network:test --tests '*EndpointSelectorTest*'`
Expected: FAIL — `Unresolved reference: EndpointSelector`

- [ ] **Step 3: 实现**

```kotlin
package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import kotlinx.coroutines.*

interface EndpointProbe {
    suspend fun probe(endpoint: Endpoint): EndpointHealth
}

/**
 * 对同一台服务器的多个接入地址并发探测。
 * select():取第一个成功返回的(即最快可达的)。
 * probeAll():等全部完成,用于设置页展示各地址状态。
 */
class EndpointSelector(
    private val probe: EndpointProbe,
    private val timeoutMs: Long = 3000,
) {
    suspend fun select(endpoints: List<Endpoint>): EndpointHealth? {
        if (endpoints.isEmpty()) return null
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val results = endpoints
                    .sortedBy { it.priority }
                    .map { async { probe.probe(it) } }
                // 依次等待,谁先成功用谁;失败的继续等下一个
                var winner: EndpointHealth? = null
                while (results.any { it.isActive } || winner == null) {
                    val done = results.firstOrNull { it.isCompleted && it.getCompleted().reachable }
                    if (done != null) { winner = done.getCompleted(); break }
                    if (results.all { it.isCompleted }) break
                    delay(20)
                }
                results.forEach { it.cancel() }
                winner
            }
        }
    }

    suspend fun probeAll(endpoints: List<Endpoint>): List<EndpointHealth> = coroutineScope {
        endpoints.map { async { probe.probe(it) } }.awaitAll()
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:network:test --tests '*EndpointSelectorTest*'`
Expected: PASS(5 个测试)

- [ ] **Step 5: 实现真实探测器**

```kotlin
package dev.insua.jellycast.network

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.EndpointHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HttpEndpointProbe(baseClient: OkHttpClient) : EndpointProbe {
    private val client = baseClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    override suspend fun probe(endpoint: Endpoint): EndpointHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(endpoint.url.trimEnd('/') + "/System/Info/Public").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    EndpointHealth(endpoint, true, System.currentTimeMillis() - start)
                } else {
                    EndpointHealth(endpoint, false, null, "HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            EndpointHealth(endpoint, false, null, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add core/network
git commit -m "feat(network): add concurrent multi-endpoint selection with health probe"
```

---

### Task 5:core:network — 自签证书按指纹信任

**Files:**
- Create: `core/network/src/main/java/dev/insua/jellycast/network/CertificatePolicy.kt`
- Test: `core/network/src/test/java/dev/insua/jellycast/network/CertificatePolicyTest.kt`

**Interfaces:**
- Produces: `fun OkHttpClient.Builder.trustPinnedSelfSigned(sha256ByHost: Map<String, String>): OkHttpClient.Builder`
- Produces: `fun X509Certificate.sha256Fingerprint(): String`

- [ ] **Step 1: 写指纹计算测试(先失败)**

```kotlin
package dev.insua.jellycast.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.cert.CertificateFactory

class CertificatePolicyTest {
    @Test fun `指纹格式为大写十六进制冒号分隔`() {
        // 用一段固定的自签证书 PEM 作为夹具
        val pem = """
            -----BEGIN CERTIFICATE-----
            MIIBkTCB+wIJAJ7v6oQ7Zk3GMA0GCSqGSIb3DQEBCwUAMBExDzANBgNVBAMMBnRl
            c3RjYTAeFw0yMDAxMDEwMDAwMDBaFw0zMDAxMDEwMDAwMDBaMBExDzANBgNVBAMM
            BnRlc3RjYTBcMA0GCSqGSIb3DQEBAQUAA0sAMEgCQQDLd1oXo6nQ5vJ0dK2FQ1eS
            8sYqKQnJ8p5dJ3Kk3xk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3AgMBAAEw
            DQYJKoZIhvcNAQELBQADQQBk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3
            Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3Zk3
            -----END CERTIFICATE-----
        """.trimIndent()
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as java.security.cert.X509Certificate
        val fp = cert.sha256Fingerprint()
        assertEquals(95, fp.length)              // 32 字节 * 2 + 31 个冒号
        assertEquals(fp, fp.uppercase())
        assert(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }
}
```

> ⚠️ 上面 PEM 是占位。**实现时用 `keytool -genkeypair` 现场生成一个自签证书并把真实 PEM 填进来**,或改用 `openssl req -x509 -newkey rsa:2048 -nodes -keyout /dev/null -out /tmp/t.pem -days 1 -subj "/CN=test"` 生成后粘贴。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:network:test --tests '*CertificatePolicyTest*'`
Expected: FAIL

- [ ] **Step 3: 实现**

```kotlin
package dev.insua.jellycast.network

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

fun X509Certificate.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString(":") { "%02X".format(it) }

/**
 * 在系统信任链之外,额外信任用户显式确认过的自签证书指纹。
 * 绝不无条件放行 —— 未在白名单中的证书仍按系统规则校验。
 */
fun OkHttpClient.Builder.trustPinnedSelfSigned(
    sha256ByHost: Map<String, String>,
): OkHttpClient.Builder {
    if (sha256ByHost.isEmpty()) return this

    val systemTm = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as java.security.KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    val delegating = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            systemTm.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                systemTm.checkServerTrusted(chain, authType)
            } catch (e: Exception) {
                val fp = chain.first().sha256Fingerprint()
                if (sha256ByHost.values.none { it.equals(fp, ignoreCase = true) }) throw e
            }
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers
    }

    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(delegating), null) }
    return sslSocketFactory(ctx.socketFactory, delegating)
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:network:test --tests '*CertificatePolicyTest*'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/network
git commit -m "feat(network): trust user-confirmed self-signed certs by SHA-256 pin"
```

---

### Task 6:core:datastore — 服务器与偏好持久化

**Files:**
- Create: `core/datastore/build.gradle.kts`
- Create: `core/datastore/src/main/java/dev/insua/jellycast/datastore/ServerStore.kt`
- Create: `core/datastore/src/main/java/dev/insua/jellycast/datastore/PreferencesStore.kt`
- Test: `core/datastore/src/test/java/dev/insua/jellycast/datastore/ServerSerializationTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class ServerStore(context: Context) {
      val servers: Flow<List<Server>>
      val activeServerId: Flow<String?>
      suspend fun upsert(server: Server)
      suspend fun delete(id: String)
      suspend fun setActive(id: String)
  }
  class PreferencesStore(context: Context) {
      val playbackSpeed: Flow<Float>          // 默认 1.0
      val rewindSeconds: Flow<Int>            // 默认 15
      val forwardSeconds: Flow<Int>           // 默认 30
      val autoPlayNext: Flow<Boolean>         // 默认 true
      val lyricsEnabled: Flow<Boolean>        // 默认 true
      val preferredSubtitleLanguage: Flow<String?>
      suspend fun setPlaybackSpeed(v: Float)
      suspend fun setRewindSeconds(v: Int)
      suspend fun setForwardSeconds(v: Int)
      suspend fun setAutoPlayNext(v: Boolean)
      suspend fun setLyricsEnabled(v: Boolean)
      suspend fun setPreferredSubtitleLanguage(v: String?)
  }
  ```

- [ ] **Step 1: 写序列化往返测试(先失败)**

```kotlin
package dev.insua.jellycast.datastore

import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.Server
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerSerializationTest {
    @Test fun `服务器多地址序列化往返一致`() {
        val original = listOf(Server(
            id = "s1", name = "家里 NAS",
            endpoints = listOf(
                Endpoint("http://192.168.1.10:8096", "局域网", 1),
                Endpoint("http://100.64.0.5:8096", "Tailscale", 2),
                Endpoint("https://[240e::1]:8920", "公网", 3, trustedCertSha256 = "AA:BB"),
            ),
            userId = "u1", accessToken = "tok",
        ))
        val json = Json.encodeToString(ServerListSurrogate.serializer(), ServerListSurrogate.from(original))
        val back = ServerListSurrogate.from(json.let {
            Json.decodeFromString(ServerListSurrogate.serializer(), it)
        }.toDomain())
        assertEquals(ServerListSurrogate.from(original), back)
    }

    @Test fun `IPv6 方括号地址不被破坏`() {
        val s = Server("s", "n", listOf(Endpoint("https://[240e::1]:8920", "公网", 1)))
        val round = ServerListSurrogate.from(listOf(s)).toDomain().first()
        assertEquals("https://[240e::1]:8920", round.endpoints.first().url)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:datastore:test`
Expected: FAIL — `Unresolved reference: ServerListSurrogate`

- [ ] **Step 3: 实现 surrogate 与 ServerStore**

```kotlin
package dev.insua.jellycast.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dev.insua.jellycast.model.Endpoint
import dev.insua.jellycast.model.Server
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ServerListSurrogate(val servers: List<ServerSurrogate>) {
    @Serializable data class ServerSurrogate(
        val id: String, val name: String,
        val endpoints: List<EndpointSurrogate>,
        val userId: String? = null, val accessToken: String? = null,
    )
    @Serializable data class EndpointSurrogate(
        val url: String, val label: String, val priority: Int,
        val trustedCertSha256: String? = null,
    )

    fun toDomain(): List<Server> = servers.map { s ->
        Server(s.id, s.name,
            s.endpoints.map { Endpoint(it.url, it.label, it.priority, it.trustedCertSha256) },
            s.userId, s.accessToken)
    }

    companion object {
        fun from(list: List<Server>) = ServerListSurrogate(list.map { s ->
            ServerSurrogate(s.id, s.name,
                s.endpoints.map { EndpointSurrogate(it.url, it.label, it.priority, it.trustedCertSha256) },
                s.userId, s.accessToken)
        })
    }
}

private val Context.serverDataStore by preferencesDataStore("servers")
private val KEY_SERVERS = stringPreferencesKey("servers_json")
private val KEY_ACTIVE = stringPreferencesKey("active_server_id")

class ServerStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val servers: Flow<List<Server>> = context.serverDataStore.data.map { prefs ->
        prefs[KEY_SERVERS]?.let {
            json.decodeFromString(ServerListSurrogate.serializer(), it).toDomain()
        } ?: emptyList()
    }

    val activeServerId: Flow<String?> = context.serverDataStore.data.map { it[KEY_ACTIVE] }

    suspend fun upsert(server: Server) = write { list ->
        list.filterNot { it.id == server.id } + server
    }

    suspend fun delete(id: String) = write { list -> list.filterNot { it.id == id } }

    suspend fun setActive(id: String) {
        context.serverDataStore.edit { it[KEY_ACTIVE] = id }
    }

    private suspend fun write(transform: (List<Server>) -> List<Server>) {
        context.serverDataStore.edit { prefs ->
            val current = prefs[KEY_SERVERS]
                ?.let { json.decodeFromString(ServerListSurrogate.serializer(), it).toDomain() }
                ?: emptyList()
            prefs[KEY_SERVERS] = json.encodeToString(
                ServerListSurrogate.serializer(), ServerListSurrogate.from(transform(current))
            )
        }
    }
}
```

- [ ] **Step 4: 实现 PreferencesStore**

按 §Interfaces 中列出的属性逐一实现,每项一个 `Preferences.Key` 与默认值(速度 1.0、后退 15、前进 30、自动连播 true、歌词 true、字幕语言 null)。

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :core:datastore:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add core/datastore
git commit -m "feat(datastore): persist servers with multiple endpoints and user preferences"
```

---

### Task 7:core:database — 进度补报队列

**Files:**
- Create: `core/database/build.gradle.kts`
- Create: `core/database/src/main/java/dev/insua/jellycast/database/JellyCastDatabase.kt`
- Create: `core/database/src/main/java/dev/insua/jellycast/database/ProgressReportEntity.kt`
- Test: `core/database/src/androidTest/java/dev/insua/jellycast/database/ProgressReportDaoTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Entity data class ProgressReportEntity(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val serverId: String, val itemId: String, val playSessionId: String?,
      val positionMs: Long, val kind: String,  // "start" | "progress" | "stop"
      val createdAt: Long,
  )
  @Dao interface ProgressReportDao {
      suspend fun enqueue(e: ProgressReportEntity)
      suspend fun pending(limit: Int = 100): List<ProgressReportEntity>
      suspend fun delete(ids: List<Long>)
  }
  ```

- [ ] **Step 1: 写 Room 测试(androidTest,需模拟器)**

```kotlin
package dev.insua.jellycast.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressReportDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), JellyCastDatabase::class.java
    ).build()

    @Test fun `入队与出队保持先进先出`() = runBlocking {
        val dao = db.progressReportDao()
        dao.enqueue(ProgressReportEntity(serverId="s", itemId="a", playSessionId=null,
            positionMs=1000, kind="progress", createdAt=1))
        dao.enqueue(ProgressReportEntity(serverId="s", itemId="b", playSessionId=null,
            positionMs=2000, kind="progress", createdAt=2))
        val pending = dao.pending()
        assertEquals(listOf("a", "b"), pending.map { it.itemId })
        dao.delete(pending.map { it.id })
        assertEquals(0, dao.pending().size)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: FAIL(类不存在)

- [ ] **Step 3: 实现 Entity / Dao / Database**

```kotlin
package dev.insua.jellycast.database

import androidx.room.*

@Entity(tableName = "progress_report")
data class ProgressReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val itemId: String,
    val playSessionId: String?,
    val positionMs: Long,
    val kind: String,
    val createdAt: Long,
)

@Dao
interface ProgressReportDao {
    @Insert suspend fun enqueue(e: ProgressReportEntity)
    @Query("SELECT * FROM progress_report ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pending(limit: Int = 100): List<ProgressReportEntity>
    @Query("DELETE FROM progress_report WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Database(entities = [ProgressReportEntity::class], version = 1, exportSchema = false)
abstract class JellyCastDatabase : RoomDatabase() {
    abstract fun progressReportDao(): ProgressReportDao
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:database:connectedDebugAndroidTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/database
git commit -m "feat(database): add offline progress report queue"
```

---

# Phase 2:播放内核

### Task 8:core:player — 音频降级链决策器(纯逻辑)

**这是本项目的技术核心。必须先完成 Task 0 Spike 并读取其结论再实现。**

**Files:**
- Create: `core/player/build.gradle.kts`
- Create: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackSourceResolver.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/PlaybackSourceResolverTest.kt`

**Interfaces:**
- Consumes: `:core:model` 的 `PlaybackSource` / `AudioDeliveryLevel`,`:core:network` 的 `JellyfinApi`
- Produces:
  ```kotlin
  interface StreamProbe { suspend fun isAudioOnly(url: String): Boolean }

  class PlaybackSourceResolver(
      private val api: JellyfinApi,
      private val streamProbe: StreamProbe,
      private val baseUrlProvider: () -> String,
      private val tokenProvider: () -> String,
  ) {
      suspend fun resolve(itemId: String, userId: String): PlaybackSource
  }
  ```

- [ ] **Step 1: 写降级链测试(先失败)**

```kotlin
package dev.insua.jellycast.player

import dev.insua.jellycast.model.AudioDeliveryLevel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackSourceResolverTest {

    /** 探测器:按 URL 关键字决定是否"纯音频" */
    private fun probe(audioOnlyUrls: Set<String>) = object : StreamProbe {
        override suspend fun isAudioOnly(url: String) = audioOnlyUrls.any { url.contains(it) }
    }

    @Test fun `L1 可用时选择服务端纯音频`() = runTest {
        val resolver = newResolver(probe(setOf("stream.mp3")))
        assertEquals(AudioDeliveryLevel.SERVER_AUDIO_ONLY,
            resolver.resolve("ep1", "u1").level)
    }

    @Test fun `L1 不可用时降级到 L2 HLS 音频轨`() = runTest {
        val resolver = newResolver(probe(setOf("master.m3u8")))
        assertEquals(AudioDeliveryLevel.HLS_AUDIO_RENDITION,
            resolver.resolve("ep1", "u1").level)
    }

    @Test fun `L1 L2 都不可用时降级到 L3 客户端禁用视频轨`() = runTest {
        val resolver = newResolver(probe(emptySet()))
        val src = resolver.resolve("ep1", "u1")
        assertEquals(AudioDeliveryLevel.CLIENT_VIDEO_DISABLED, src.level)
        assert(src.streamUrl.isNotBlank())   // L3 必须永远给出可播 URL
    }

    @Test fun `位图字幕被过滤掉不出现在可选字幕中`() = runTest {
        val src = newResolver(probe(emptySet())).resolve("ep1", "u1")
        assert(src.textSubtitles.all { it.isTextBased })
    }
}
```

> `newResolver(...)` 在测试文件中用假的 `JellyfinApi`(MockK)构造,其 `playbackInfo` 返回一个含 1 条音频轨、1 条文本字幕轨、1 条 PGS 位图字幕轨的 `PlaybackInfoResponseDto`。

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:player:test --tests '*PlaybackSourceResolverTest*'`
Expected: FAIL

- [ ] **Step 3: 实现降级链**

```kotlin
package dev.insua.jellycast.player

import dev.insua.jellycast.model.*
import dev.insua.jellycast.network.JellyfinApi

interface StreamProbe {
    /** 发一个 Range 请求嗅探前若干字节,判断该流是否只含音频 */
    suspend fun isAudioOnly(url: String): Boolean
}

class PlaybackSourceResolver(
    private val api: JellyfinApi,
    private val streamProbe: StreamProbe,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
) {
    suspend fun resolve(itemId: String, userId: String): PlaybackSource {
        val info = api.playbackInfo(itemId, userId)
        val ms = info.mediaSources.firstOrNull()
            ?: error("item $itemId has no media source")
        val base = baseUrlProvider().trimEnd('/')
        val auth = "api_key=${tokenProvider()}"

        val audioTracks = ms.mediaStreams.filter { it.type == "Audio" }.map {
            AudioTrack(it.index, it.language, it.displayTitle ?: it.language ?: "音轨 ${it.index}")
        }
        // 位图字幕(PGS/VobSub)取不到文本,直接过滤
        val subtitles = ms.mediaStreams
            .filter { it.type == "Subtitle" && it.isTextSubtitle }
            .map { SubtitleTrackRef(it.index, it.language,
                it.displayTitle ?: it.language ?: "字幕 ${it.index}", isTextBased = true) }

        // ---- L1:服务端纯音频 ----
        val l1 = "$base/Videos/$itemId/stream.mp3?$auth&mediaSourceId=${ms.id}"
        if (runCatching { streamProbe.isAudioOnly(l1) }.getOrDefault(false)) {
            return PlaybackSource(itemId, ms.id, l1, AudioDeliveryLevel.SERVER_AUDIO_ONLY,
                isHls = false, info.playSessionId, audioTracks, subtitles)
        }

        // ---- L2:HLS 独立音频 rendition ----
        val l2 = "$base/Videos/$itemId/master.m3u8?$auth&mediaSourceId=${ms.id}&audioCodec=aac"
        if (runCatching { streamProbe.isAudioOnly(l2) }.getOrDefault(false)) {
            return PlaybackSource(itemId, ms.id, l2, AudioDeliveryLevel.HLS_AUDIO_RENDITION,
                isHls = true, info.playSessionId, audioTracks, subtitles)
        }

        // ---- L3:兜底,拉完整流由客户端禁用视频轨 ----
        val l3 = "$base/Videos/$itemId/stream?$auth&mediaSourceId=${ms.id}&static=true"
        return PlaybackSource(itemId, ms.id, l3, AudioDeliveryLevel.CLIENT_VIDEO_DISABLED,
            isHls = false, info.playSessionId, audioTracks, subtitles)
    }
}
```

> ⚠️ **L1/L2 的 URL 模板必须替换为 Task 0 Spike 实测通过的形式。** 上面是待验证的占位形态,Spike 结论若不同,以结论为准。

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:player:test --tests '*PlaybackSourceResolverTest*'`
Expected: PASS(4 个测试)

- [ ] **Step 5: Commit**

```bash
git add core/player
git commit -m "feat(player): add three-tier audio delivery degradation chain"
```

---

### Task 9:core:player — Media3 引擎与禁用视频轨

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/JellyCastPlayerFactory.kt`

**Interfaces:**
- Produces: `fun createAudioOnlyPlayer(context: Context): ExoPlayer`

- [ ] **Step 1: 实现**

```kotlin
package dev.insua.jellycast.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * 构建一个永不渲染视频的播放器:
 * 1. 通过 TrackSelector 全局禁用视频轨 —— 即使拿到的是完整流(L3),
 *    视频也不会被解码,省 CPU 与电量。
 * 2. 永不绑定 Surface。
 * 3. 设置为 MUSIC 用途,让系统按音乐处理(耳机拔出暂停、音频焦点)。
 */
fun createAudioOnlyPlayer(context: Context): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
    }
    return ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)   // 拔耳机自动暂停
        .build()
}
```

- [ ] **Step 2: 手动验证(需真机或模拟器)**

播放任意一个 L3 级别的流,用 `adb shell dumpsys media.metrics` 或 Android Studio Profiler 确认**没有视频解码器被创建**。

- [ ] **Step 3: Commit**

```bash
git add core/player
git commit -m "feat(player): create audio-only ExoPlayer with video track disabled"
```

---

### Task 10:core:player — MediaSessionService(后台/锁屏)

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/PlaybackService.kt`
- Modify: `app/src/main/AndroidManifest.xml`(注册 Service)

**Interfaces:**
- Produces: `class PlaybackService : MediaSessionService`

- [ ] **Step 1: 实现 Service**

```kotlin
package dev.insua.jellycast.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = createAudioOnlyPlayer(this)
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
```

- [ ] **Step 2: 在 Manifest 注册**

在 `<application>` 内加入:

```xml
<service
    android:name="dev.insua.jellycast.player.PlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

- [ ] **Step 3: 手动验收**

- [ ] 播放后按 Home 键,音频继续
- [ ] 锁屏出现媒体控制卡片,可暂停/切集
- [ ] 蓝牙耳机播放/暂停键有效
- [ ] 拔出有线耳机自动暂停

- [ ] **Step 4: Commit**

```bash
git add core/player app/src/main/AndroidManifest.xml
git commit -m "feat(player): add MediaSessionService for background and lockscreen control"
```

---

### Task 11:core:player — 播放队列与自动连播

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/PlayQueue.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/PlayQueueTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class PlayQueue {
      val current: StateFlow<MediaItem?>
      fun setQueue(items: List<MediaItem>, startIndex: Int)
      fun next(): MediaItem?      // 无下一项返回 null
      fun previous(): MediaItem?
      fun hasNext(): Boolean
  }
  ```

- [ ] **Step 1: 写测试(先失败)**

```kotlin
package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import dev.insua.jellycast.model.MediaKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private fun ep(id: String) = MediaItem(id, MediaKind.EPISODE, "第 $id 集")

class PlayQueueTest {
    @Test fun `next 按顺序推进`() {
        val q = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2"), ep("3")), 0) }
        assertEquals("1", q.current.value?.id)
        assertEquals("2", q.next()?.id)
        assertEquals("3", q.next()?.id)
        assertNull(q.next())
        assertEquals("3", q.current.value?.id)   // 到底后 current 不变
    }

    @Test fun `previous 回退且不越界`() {
        val q = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 1) }
        assertEquals("1", q.previous()?.id)
        assertNull(q.previous())
    }

    @Test fun `hasNext 在最后一项返回 false`() {
        val q = PlayQueue().apply { setQueue(listOf(ep("1"), ep("2")), 0) }
        assertTrue(q.hasNext())
        q.next()
        assertFalse(q.hasNext())
    }

    @Test fun `空队列不崩溃`() {
        val q = PlayQueue().apply { setQueue(emptyList(), 0) }
        assertNull(q.current.value)
        assertNull(q.next())
        assertFalse(q.hasNext())
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:player:test --tests '*PlayQueueTest*'`
Expected: FAIL

- [ ] **Step 3: 实现**

```kotlin
package dev.insua.jellycast.player

import dev.insua.jellycast.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayQueue {
    private var items: List<MediaItem> = emptyList()
    private var index: Int = -1
    private val _current = MutableStateFlow<MediaItem?>(null)
    val current: StateFlow<MediaItem?> = _current.asStateFlow()

    fun setQueue(items: List<MediaItem>, startIndex: Int) {
        this.items = items
        this.index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.lastIndex)
        _current.value = items.getOrNull(index)
    }

    fun hasNext(): Boolean = index >= 0 && index < items.lastIndex

    fun next(): MediaItem? {
        if (!hasNext()) return null
        index++
        _current.value = items[index]
        return _current.value
    }

    fun previous(): MediaItem? {
        if (index <= 0) return null
        index--
        _current.value = items[index]
        return _current.value
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:player:test --tests '*PlayQueueTest*'`
Expected: PASS(4 个测试)

- [ ] **Step 5: 接入自动连播**

在播放控制器中监听 `Player.STATE_ENDED`:若 `PreferencesStore.autoPlayNext` 为 true 且 `queue.hasNext()`,则 `queue.next()` 并解析新的 `PlaybackSource` 继续播放;若队列已空,调用 `JellyfinApi.nextUp()` 取下一集补充队列。

- [ ] **Step 6: Commit**

```bash
git add core/player
git commit -m "feat(player): add play queue with auto-play-next"
```

---

### Task 12:core:player — 进度双向同步

**Files:**
- Create: `core/player/src/main/java/dev/insua/jellycast/player/ProgressReporter.kt`
- Test: `core/player/src/test/java/dev/insua/jellycast/player/ProgressReporterTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class ProgressReporter(
      private val api: JellyfinApi,
      private val dao: ProgressReportDao,
      private val serverId: String,
  ) {
      suspend fun start(itemId: String, sessionId: String?, positionMs: Long)
      suspend fun progress(itemId: String, sessionId: String?, positionMs: Long)
      suspend fun stop(itemId: String, sessionId: String?, positionMs: Long)
      suspend fun flushPending()   // 联网后补报
  }
  ```

- [ ] **Step 1: 写测试(先失败)**

```kotlin
package dev.insua.jellycast.player

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProgressReporterTest {
    @Test fun `上报失败时入队等待补报`() = runTest {
        val api = mockk<JellyfinApi>()
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { api.reportProgress(any()) } throws java.io.IOException("offline")
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5000)
        coVerify(exactly = 1) { dao.enqueue(match { it.itemId == "ep1" && it.positionMs == 5000L }) }
    }

    @Test fun `上报成功时不入队`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        ProgressReporter(api, dao, "s1").progress("ep1", "sess", 5000)
        coVerify(exactly = 0) { dao.enqueue(any()) }
    }

    @Test fun `flushPending 成功后删除已补报记录`() = runTest {
        val api = mockk<JellyfinApi>(relaxed = true)
        val dao = mockk<ProgressReportDao>(relaxed = true)
        coEvery { dao.pending(any()) } returns listOf(
            ProgressReportEntity(1, "s1", "ep1", null, 1000, "progress", 1)
        )
        ProgressReporter(api, dao, "s1").flushPending()
        coVerify { dao.delete(listOf(1L)) }
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现 → 运行确认通过**

Run: `./gradlew :core:player:test --tests '*ProgressReporterTest*'`

实现要点:
- 每 **10 秒**上报一次 `progress`,seek 时立即上报;
- `PositionTicks = positionMs * 10_000`;
- 任何 `IOException` / 非 2xx 都入队,**不向上抛错、不打断播放**;
- 恢复网络时(监听 `ConnectivityManager`)调用 `flushPending()`。

- [ ] **Step 3: Commit**

```bash
git add core/player
git commit -m "feat(player): sync playback progress with offline retry queue"
```

---

# Phase 3:字幕即歌词

### Task 13:core:subtitle — SRT 解析

**Files:**
- Create: `core/subtitle/build.gradle.kts`
- Create: `core/subtitle/src/main/java/dev/insua/jellycast/subtitle/SrtParser.kt`
- Test: `core/subtitle/src/test/java/dev/insua/jellycast/subtitle/SrtParserTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface SubtitleParser { fun parse(content: String): List<SubtitleLine> }
  object SrtParser : SubtitleParser
  ```

- [ ] **Step 1: 写测试(先失败)**

```kotlin
package dev.insua.jellycast.subtitle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SrtParserTest {
    private val sample = """
        1
        00:00:01,000 --> 00:00:03,500
        你好，世界

        2
        00:01:02,250 --> 00:01:05,000
        第二行字幕
        跨两行显示
    """.trimIndent()

    @Test fun `解析出正确的条数`() {
        assertEquals(2, SrtParser.parse(sample).size)
    }

    @Test fun `时间戳换算为毫秒`() {
        val lines = SrtParser.parse(sample)
        assertEquals(1000L, lines[0].startMs)
        assertEquals(3500L, lines[0].endMs)
        assertEquals(62250L, lines[1].startMs)
        assertEquals(65000L, lines[1].endMs)
    }

    @Test fun `多行文本用换行合并`() {
        assertEquals("第二行字幕\n跨两行显示", SrtParser.parse(sample)[1].text)
    }

    @Test fun `剥离 HTML 样式标签`() {
        val s = "1\n00:00:01,000 --> 00:00:02,000\n<i>斜体</i><b>粗体</b>"
        assertEquals("斜体粗体", SrtParser.parse(s)[0].text)
    }

    @Test fun `畸形输入不抛异常并跳过坏块`() {
        val bad = "1\n乱七八糟\n文本\n\n2\n00:00:05,000 --> 00:00:06,000\n正常"
        val lines = SrtParser.parse(bad)
        assertEquals(1, lines.size)
        assertEquals("正常", lines[0].text)
    }

    @Test fun `空输入返回空列表`() {
        assertTrue(SrtParser.parse("").isEmpty())
    }

    @Test fun `输出按开始时间升序`() {
        val out = "2\n00:00:10,000 --> 00:00:11,000\nB\n\n1\n00:00:01,000 --> 00:00:02,000\nA"
        assertEquals(listOf("A", "B"), SrtParser.parse(out).map { it.text })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :core:subtitle:test --tests '*SrtParserTest*'`
Expected: FAIL

- [ ] **Step 3: 实现**

```kotlin
package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleLine

interface SubtitleParser {
    fun parse(content: String): List<SubtitleLine>
}

object SrtParser : SubtitleParser {

    private val TIME = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val TAG = Regex("""</?[a-zA-Z][^>]*>""")

    override fun parse(content: String): List<SubtitleLine> =
        content.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .mapNotNull { parseBlock(it.trim()) }
            .sortedBy { it.startMs }

    private fun parseBlock(block: String): SubtitleLine? {
        if (block.isBlank()) return null
        val lines = block.lines()
        val timeIdx = lines.indexOfFirst { TIME.containsMatchIn(it) }
        if (timeIdx < 0) return null
        val m = TIME.find(lines[timeIdx]) ?: return null
        val g = m.groupValues
        val start = toMs(g[1], g[2], g[3], g[4])
        val end = toMs(g[5], g[6], g[7], g[8])
        val text = lines.drop(timeIdx + 1)
            .joinToString("\n").let { TAG.replace(it, "") }.trim()
        if (text.isEmpty()) return null
        return SubtitleLine(start, end, text)
    }

    private fun toMs(h: String, m: String, s: String, ms: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 +
            ms.padEnd(3, '0').toLong()
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :core:subtitle:test --tests '*SrtParserTest*'`
Expected: PASS(7 个测试)

- [ ] **Step 5: Commit**

```bash
git add core/subtitle
git commit -m "feat(subtitle): add SRT parser with malformed-input tolerance"
```

---

### Task 14:core:subtitle — VTT 与 ASS 解析

**Files:**
- Create: `core/subtitle/src/main/java/dev/insua/jellycast/subtitle/VttParser.kt`
- Create: `core/subtitle/src/main/java/dev/insua/jellycast/subtitle/AssParser.kt`
- Test: `core/subtitle/src/test/java/dev/insua/jellycast/subtitle/VttParserTest.kt`
- Test: `core/subtitle/src/test/java/dev/insua/jellycast/subtitle/AssParserTest.kt`

**Interfaces:**
- Produces: `object VttParser : SubtitleParser`,`object AssParser : SubtitleParser`
- Produces: `fun parserFor(format: String): SubtitleParser`(`"srt"`→Srt,`"vtt"`→Vtt,`"ass"`/`"ssa"`→Ass)

- [ ] **Step 1: 写 VTT 测试(先失败)**

```kotlin
package dev.insua.jellycast.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VttParserTest {
    private val sample = """
        WEBVTT

        00:00:01.000 --> 00:00:03.500
        你好，世界

        00:01:02.250 --> 00:01:05.000 align:start position:10%
        带样式参数的一行
    """.trimIndent()

    @Test fun `跳过 WEBVTT 头`() = assertEquals(2, VttParser.parse(sample).size)

    @Test fun `点号毫秒分隔符正确解析`() {
        assertEquals(3500L, VttParser.parse(sample)[0].endMs)
    }

    @Test fun `忽略时间行后的样式参数`() {
        assertEquals("带样式参数的一行", VttParser.parse(sample)[1].text)
    }
}
```

- [ ] **Step 2: 写 ASS 测试(先失败)**

```kotlin
package dev.insua.jellycast.subtitle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AssParserTest {
    private val sample = """
        [Script Info]
        Title: test

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,你好，世界
        Dialogue: 0,0:01:02.25,0:01:05.00,Default,,0,0,0,,{\pos(100,200)}带特效标签
    """.trimIndent()

    @Test fun `只解析 Dialogue 行`() = assertEquals(2, AssParser.parse(sample).size)

    @Test fun `厘秒换算为毫秒`() {
        val l = AssParser.parse(sample)[0]
        assertEquals(1000L, l.startMs)
        assertEquals(3500L, l.endMs)
    }

    @Test fun `剥离花括号特效标签`() {
        assertEquals("带特效标签", AssParser.parse(sample)[1].text)
    }

    @Test fun `文本中的逗号不被截断`() {
        val s = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,前半句，后半句"
        assertEquals("前半句，后半句", AssParser.parse(s)[0].text)
    }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `./gradlew :core:subtitle:test`
Expected: FAIL

- [ ] **Step 4: 实现 VttParser**

```kotlin
package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleLine

object VttParser : SubtitleParser {
    private val TIME = Regex(
        """(\d{2,}):(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(\d{2,}):(\d{2}):(\d{2})\.(\d{3})"""
    )
    private val TAG = Regex("""</?[a-zA-Z][^>]*>""")

    override fun parse(content: String): List<SubtitleLine> =
        content.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .filterNot { it.trimStart().startsWith("WEBVTT") || it.trimStart().startsWith("NOTE") }
            .mapNotNull { block ->
                val lines = block.trim().lines()
                val idx = lines.indexOfFirst { TIME.containsMatchIn(it) }
                if (idx < 0) return@mapNotNull null
                val g = (TIME.find(lines[idx]) ?: return@mapNotNull null).groupValues
                val text = lines.drop(idx + 1).joinToString("\n")
                    .let { TAG.replace(it, "") }.trim()
                if (text.isEmpty()) null
                else SubtitleLine(toMs(g[1], g[2], g[3], g[4]), toMs(g[5], g[6], g[7], g[8]), text)
            }
            .sortedBy { it.startMs }

    private fun toMs(h: String, m: String, s: String, ms: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + ms.toLong()
}
```

- [ ] **Step 5: 实现 AssParser**

```kotlin
package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleLine

object AssParser : SubtitleParser {
    private val EFFECT = Regex("""\{[^}]*}""")

    override fun parse(content: String): List<SubtitleLine> =
        content.replace("\r\n", "\n").lines()
            .filter { it.startsWith("Dialogue:") }
            .mapNotNull { line ->
                // Dialogue 前 9 个字段固定,第 10 个字段(Text)之后的逗号属于文本本身
                val body = line.removePrefix("Dialogue:").trim()
                val parts = body.split(",", limit = 10)
                if (parts.size < 10) return@mapNotNull null
                val start = parseAssTime(parts[1].trim()) ?: return@mapNotNull null
                val end = parseAssTime(parts[2].trim()) ?: return@mapNotNull null
                val text = EFFECT.replace(parts[9], "").replace("\\N", "\n").trim()
                if (text.isEmpty()) null else SubtitleLine(start, end, text)
            }
            .sortedBy { it.startMs }

    /** ASS 时间格式:H:MM:SS.cc(厘秒) */
    private fun parseAssTime(t: String): Long? {
        val m = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""").find(t) ?: return null
        val g = m.groupValues
        return g[1].toLong() * 3_600_000 + g[2].toLong() * 60_000 +
            g[3].toLong() * 1_000 + g[4].toLong() * 10
    }
}

fun parserFor(format: String): SubtitleParser = when (format.lowercase()) {
    "srt", "subrip" -> SrtParser
    "vtt", "webvtt" -> VttParser
    "ass", "ssa" -> AssParser
    else -> SrtParser
}
```

- [ ] **Step 6: 运行确认通过**

Run: `./gradlew :core:subtitle:test`
Expected: PASS(SRT 7 + VTT 3 + ASS 4 = 14 个测试)

- [ ] **Step 7: Commit**

```bash
git add core/subtitle
git commit -m "feat(subtitle): add VTT and ASS parsers with format dispatcher"
```

---

### Task 15:core:subtitle — 字幕拉取仓库

**Files:**
- Create: `core/subtitle/src/main/java/dev/insua/jellycast/subtitle/SubtitleRepository.kt`
- Test: `core/subtitle/src/test/java/dev/insua/jellycast/subtitle/SubtitleRepositoryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class SubtitleRepository(
      private val client: OkHttpClient,
      private val baseUrlProvider: () -> String,
      private val tokenProvider: () -> String,
  ) {
      /** 失败时返回空 timeline,绝不抛异常 —— 字幕问题不得影响播放 */
      suspend fun load(itemId: String, mediaSourceId: String, subtitleIndex: Int): SubtitleTimeline
  }
  ```

- [ ] **Step 1: 写测试(先失败)**

```kotlin
package dev.insua.jellycast.subtitle

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SubtitleRepositoryTest {
    @Test fun `成功拉取并解析 SRT`() = runTest {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("1\n00:00:01,000 --> 00:00:02,000\n测试"))
            start()
        }
        val repo = SubtitleRepository(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "tok" })
        val timeline = repo.load("ep1", "ms1", 2)
        assertEquals(1, timeline.lines.size)
        assertEquals("测试", timeline.lines[0].text)
        server.shutdown()
    }

    @Test fun `HTTP 失败时返回空 timeline 而不抛异常`() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(404)); start() }
        val repo = SubtitleRepository(OkHttpClient(), { server.url("/").toString().trimEnd('/') }, { "tok" })
        assertEquals(0, repo.load("ep1", "ms1", 2).lines.size)
        server.shutdown()
    }

    @Test fun `网络异常时返回空 timeline`() = runTest {
        val repo = SubtitleRepository(OkHttpClient(), { "http://127.0.0.1:1" }, { "tok" })
        assertEquals(0, repo.load("ep1", "ms1", 2).lines.size)
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现**

```kotlin
package dev.insua.jellycast.subtitle

import dev.insua.jellycast.model.SubtitleTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class SubtitleRepository(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String,
) {
    /**
     * 独立于播放流拉取字幕文本。
     * 因为播放走的是纯音频流,字幕轨不在其中,必须单独取。
     * 任何失败都降级为"无字幕",不得影响播放。
     */
    suspend fun load(itemId: String, mediaSourceId: String, subtitleIndex: Int): SubtitleTimeline =
        withContext(Dispatchers.IO) {
            val format = "srt"
            val url = "${baseUrlProvider().trimEnd('/')}/Videos/$itemId/$mediaSourceId/" +
                "Subtitles/$subtitleIndex/Stream.$format?api_key=${tokenProvider()}"
            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext SubtitleTimeline(emptyList())
                    val body = resp.body?.string().orEmpty()
                    SubtitleTimeline(parserFor(format).parse(body))
                }
            } catch (e: Exception) {
                SubtitleTimeline(emptyList())
            }
        }
}
```

- [ ] **Step 3: 运行确认通过**

Run: `./gradlew :core:subtitle:test --tests '*SubtitleRepositoryTest*'`
Expected: PASS(3 个测试)

- [ ] **Step 4: Commit**

```bash
git add core/subtitle
git commit -m "feat(subtitle): fetch subtitle text independently of audio stream"
```

---

# Phase 4:界面

### Task 16:core:designsystem — 主题与迷你播放条

**Files:**
- Create: `core/designsystem/build.gradle.kts`
- Create: `core/designsystem/src/main/java/dev/insua/jellycast/designsystem/Theme.kt`
- Create: `core/designsystem/src/main/java/dev/insua/jellycast/designsystem/MiniPlayerBar.kt`
- Create: `core/designsystem/src/main/java/dev/insua/jellycast/designsystem/PosterCard.kt`

**Interfaces:**
- Produces:
  ```kotlin
  @Composable fun JellyCastTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
  @Composable fun MiniPlayerBar(
      title: String, subtitle: String, posterUrl: String?,
      isPlaying: Boolean, progress: Float,
      onPlayPause: () -> Unit, onExpand: () -> Unit, modifier: Modifier = Modifier,
  )
  @Composable fun PosterCard(title: String, subtitle: String?, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier)
  ```

- [ ] **Step 1: 实现主题**

Material 3 动态取色 + 深色模式。播放页背景从封面主色渐变(用 Coil 取色),这是让它"像音乐播放器"的关键视觉。

- [ ] **Step 2: 实现 MiniPlayerBar**

高度 64dp,左侧 48dp 方形封面,中间两行文字(标题 + 剧名·集号),右侧播放/暂停按钮,底部 2dp 进度条。整条可点击展开全屏播放页。

- [ ] **Step 3: 写 Compose UI 测试**

`core/designsystem/src/androidTest/.../MiniPlayerBarTest.kt`:
- 点击播放按钮触发 `onPlayPause`
- 点击整条触发 `onExpand`
- `isPlaying = true` 时显示暂停图标

- [ ] **Step 4: 运行 UI 测试**

Run: `./gradlew :core:designsystem:connectedDebugAndroidTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/designsystem
git commit -m "feat(designsystem): add theme, mini player bar and poster card"
```

---

### Task 17:feature:server — 服务器管理与登录

**Files:**
- Create: `feature/server/build.gradle.kts`
- Create: `feature/server/src/main/java/dev/insua/jellycast/feature/server/ServerListScreen.kt`
- Create: `feature/server/src/main/java/dev/insua/jellycast/feature/server/AddServerScreen.kt`
- Create: `feature/server/src/main/java/dev/insua/jellycast/feature/server/ServerViewModel.kt`
- Test: `feature/server/src/test/java/dev/insua/jellycast/feature/server/ServerViewModelTest.kt`

**Interfaces:**
- Consumes: `ServerStore`、`EndpointSelector`、`JellyfinApi`
- Produces: `@Composable fun ServerListScreen(onServerReady: (String) -> Unit)`、`@Composable fun AddServerScreen(onDone: () -> Unit)`

**UI 要求:**
- 服务器列表:每台显示名称 + **当前选中的 endpoint 与延迟**(如 `Tailscale · 42ms`)
- 添加服务器表单:名称 + **可添加多个接入地址**(URL + 标签)+ 用户名 + 密码
- 点击「测试连接」→ 调用 `EndpointSelector.probeAll()` → 逐条显示 ✅/❌ 与失败原因
- 遇自签证书:弹窗显示指纹,用户确认后写入该 endpoint 的 `trustedCertSha256`

- [ ] **Step 1: 写 ViewModel 测试(先失败)**

```kotlin
@Test fun `全部地址不可达时给出可诊断的错误`() = runTest {
    // probeAll 返回 3 个 reachable=false 的结果
    // 断言 uiState.error 中包含每个地址的失败原因
}

@Test fun `登录成功后保存 token 与 userId`() = runTest {
    // 断言 ServerStore.upsert 被调用且 accessToken 非空
}

@Test fun `Tailscale 地址超时且无其他可用地址时提示检查 Tailscale`() = runTest {
    // 断言错误文案包含 "Tailscale"
}
```

- [ ] **Step 2: 运行确认失败 → 实现 → 运行确认通过**

Run: `./gradlew :feature:server:test`

- [ ] **Step 3: Commit**

```bash
git add feature/server
git commit -m "feat(server): add multi-server management with endpoint diagnostics"
```

---

### Task 18:feature:home — "在听"首页

**Files:**
- Create: `feature/home/build.gradle.kts`
- Create: `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeScreen.kt`
- Create: `feature/home/src/main/java/dev/insua/jellycast/feature/home/HomeViewModel.kt`
- Test: `feature/home/src/test/java/dev/insua/jellycast/feature/home/HomeViewModelTest.kt`

**Interfaces:**
- Produces: `@Composable fun HomeScreen(onItemClick: (MediaItem) -> Unit)`

**三个分区,自上而下:**
1. **继续收听** — `api.resume(userId)`,横向滑动卡片,显示已听进度条
2. **下一集** — `api.nextUp(userId)`,追剧主入口
3. **最近添加** — `api.items(userId, types="Episode,Movie", sortBy="DateCreated")`

- [ ] **Step 1: 写 ViewModel 测试(先失败)**

```kotlin
@Test fun `三个分区并发加载互不阻塞`() = runTest { }
@Test fun `某个分区失败时其余仍正常展示`() = runTest { }
@Test fun `分区为空时不显示该分区标题`() = runTest { }
```

- [ ] **Step 2: 运行确认失败 → 实现 → 运行确认通过**

Run: `./gradlew :feature:home:test`

- [ ] **Step 3: Commit**

```bash
git add feature/home
git commit -m "feat(home): add listening home with resume, next-up and recent sections"
```

---

### Task 19:feature:library — 剧集与电影浏览

**Files:**
- Create: `feature/library/build.gradle.kts`
- Create: `feature/library/src/main/java/dev/insua/jellycast/feature/library/LibraryScreen.kt`
- Create: `feature/library/src/main/java/dev/insua/jellycast/feature/library/SeriesDetailScreen.kt`
- Create: `feature/library/src/main/java/dev/insua/jellycast/feature/library/LibraryViewModel.kt`
- Test: `feature/library/src/test/java/dev/insua/jellycast/feature/library/LibraryViewModelTest.kt`

**Interfaces:**
- Produces: `@Composable fun LibraryScreen(onSeriesClick: (String) -> Unit, onPlay: (MediaItem) -> Unit)`
- Produces: `@Composable fun SeriesDetailScreen(seriesId: String, onPlay: (MediaItem, List<MediaItem>) -> Unit)`

**关键交互:** 在剧集详情点某一集播放时,**必须把该季的完整集列表作为播放队列传出去**(`onPlay(item, allEpisodesInSeason)`),这样自动连播才能工作。

- [ ] **Step 1: 写测试 → 实现 → 验证**

```kotlin
@Test fun `点击某集时把整季作为队列传出`() = runTest { }
@Test fun `季列表按季号排序`() = runTest { }
@Test fun `剧集和电影分为两个 Tab`() = runTest { }
```

Run: `./gradlew :feature:library:test`

- [ ] **Step 2: Commit**

```bash
git add feature/library
git commit -m "feat(library): browse series, seasons, episodes and movies"
```

---

### Task 20:feature:player — 全屏播放页与歌词视图

**这是产品的门面,也是「字幕即歌词」落地的地方。**

**Files:**
- Create: `feature/player/build.gradle.kts`
- Create: `feature/player/src/main/java/dev/insua/jellycast/feature/player/PlayerScreen.kt`
- Create: `feature/player/src/main/java/dev/insua/jellycast/feature/player/LyricsView.kt`
- Create: `feature/player/src/main/java/dev/insua/jellycast/feature/player/PlayerViewModel.kt`
- Test: `feature/player/src/test/java/dev/insua/jellycast/feature/player/LyricsStateTest.kt`

**Interfaces:**
- Produces: `@Composable fun PlayerScreen(onCollapse: () -> Unit)`
- Produces: `@Composable fun LyricsView(timeline: SubtitleTimeline, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier)`

- [ ] **Step 1: 写歌词状态测试(先失败)**

```kotlin
package dev.insua.jellycast.feature.player

import dev.insua.jellycast.model.SubtitleLine
import dev.insua.jellycast.model.SubtitleTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LyricsStateTest {
    private val timeline = SubtitleTimeline(listOf(
        SubtitleLine(1000, 3000, "A"),
        SubtitleLine(4000, 6000, "B"),
        SubtitleLine(10000, 12000, "C"),
    ))

    @Test fun `当前行索引随播放位置变化`() {
        assertEquals(0, timeline.indexAt(2000))
        assertEquals(1, timeline.indexAt(5000))
    }

    @Test fun `间隙期保持上一行高亮以免闪烁`() {
        // 设计决策:落在两行之间时,UI 保持上一行为"已读"态,不高亮任何行为"当前"
        assertEquals(-1, timeline.indexAt(3500))
    }

    @Test fun `点击第 N 行返回该行的起始时间用于 seek`() {
        assertEquals(4000L, timeline.lines[1].startMs)
    }
}
```

- [ ] **Step 2: 运行确认失败 → 实现 LyricsView**

**LyricsView 实现要求:**
- 用 `LazyColumn` + `rememberLazyListState()`
- 当前行:字号更大、全不透明、主题主色;其他行:半透明灰
- 当前行变化时 `animateScrollToItem` 平滑滚到**屏幕垂直中央**
- 用户手动滚动时暂停自动跟随 **3 秒**,之后恢复(用 `interactionSource` 检测拖拽)
- 点击任意行 → `onSeek(line.startMs)`
- 无字幕时显示占位文案:「此内容无文本字幕」
- 字幕加载中显示 loading

**PlayerScreen 布局(自上而下):**
1. 顶部:收起按钮 + 剧名
2. 大封面(方形,圆角 12dp,带阴影),背景为封面主色渐变
3. 标题(集名)+ 副标题(剧名 · SxxExx)
4. **歌词区**(占据中部主要空间,可上下滚动)
5. 进度条 + `已听 12:34 / 45:00`
6. 控制行:`⏪15s` `▶/⏸`(大按钮) `⏩30s`
7. 底部工具行:倍速 · 睡眠定时 · 音轨 · 字幕语言 · 下一集

- [ ] **Step 3: 运行测试**

Run: `./gradlew :feature:player:test`
Expected: PASS

- [ ] **Step 4: 手动验收**

- [ ] 歌词随播放滚动,当前行居中高亮
- [ ] 点击歌词行跳转到对应时间
- [ ] 手动滚动后 3 秒恢复自动跟随
- [ ] 切换字幕语言后歌词重新加载
- [ ] 无字幕的条目正常播放且显示占位文案

- [ ] **Step 5: Commit**

```bash
git add feature/player
git commit -m "feat(player): add full-screen player with lyrics-style subtitle view"
```

---

### Task 21:feature:settings — 设置页

**Files:**
- Create: `feature/settings/build.gradle.kts`
- Create: `feature/settings/src/main/java/dev/insua/jellycast/feature/settings/SettingsScreen.kt`
- Create: `feature/settings/src/main/java/dev/insua/jellycast/feature/settings/SettingsViewModel.kt`

**Interfaces:**
- Produces: `@Composable fun SettingsScreen(onManageServers: () -> Unit)`

**条目清单:**
- 服务器管理(跳转到 `ServerListScreen`)
- 默认播放倍速(0.5–3.0,步进 0.1)
- 快退秒数(5 / 10 / 15 / 30)
- 快进秒数(10 / 15 / 30 / 60)
- 自动连播下一集(开关)
- 歌词式字幕(开关)
- 首选字幕语言
- **开发者信息**(折叠):当前 endpoint、当前音频降级级别、本次会话已传输字节数

- [ ] **Step 1: 实现 → 编译验证 → Commit**

```bash
./gradlew :feature:settings:assembleDebug
git add feature/settings
git commit -m "feat(settings): add preferences screen with developer diagnostics"
```

---

### Task 22:导航装配与迷你播放条常驻

**Files:**
- Create: `app/src/main/java/dev/insua/jellycast/navigation/JellyCastNavHost.kt`
- Modify: `app/src/main/java/dev/insua/jellycast/MainActivity.kt`

**Interfaces:**
- Consumes: 全部 `feature:*` 的入口 Composable
- Produces: `@Composable fun JellyCastNavHost()`

**路由:**

| route | 屏幕 |
|---|---|
| `servers` | 服务器列表(无已登录服务器时为起点) |
| `servers/add` | 添加服务器 |
| `home` | 在听(有已登录服务器时为起点) |
| `library` | 媒体库 |
| `library/series/{seriesId}` | 剧集详情 |
| `settings` | 设置 |
| `player`(bottom sheet) | 全屏播放页 |

**关键:** 迷你播放条**不在 NavHost 内部**,而是在 `Scaffold` 的 `bottomBar` 之上常驻,切 Tab 不消失。有正在播放的内容时才显示。

- [ ] **Step 1: 实现 → 编译并真机跑通完整流程**

- [ ] 冷启动 → 添加服务器 → 登录 → 进入首页
- [ ] 首页点「下一集」→ 开始播放 → 迷你条出现
- [ ] 切到「媒体库」Tab,迷你条仍在且继续播放
- [ ] 点迷你条 → 展开全屏播放页 → 收起

- [ ] **Step 2: Commit**

```bash
git add app
git commit -m "feat(app): wire navigation with persistent mini player bar"
```

---

# Phase 5:收尾

### Task 23:端到端验收

**Files:**
- Create: `docs/superpowers/acceptance-checklist.md`

- [ ] **Step 1: 对真实 DS920+ 跑通全流程**

- [ ] 用 Tailscale 地址登录成功
- [ ] 添加公网 HTTPS 地址作为第二 endpoint
- [ ] 关闭 WiFi 切到移动网络,App 自动切到公网 endpoint 并继续播放
- [ ] 播放一集,记录**实际流量消耗**并与预期比对(L1 应 ≈ 音频码率)
- [ ] 在群晖上确认 ffmpeg CPU 占用可接受

- [ ] **Step 2: 完成手动验收清单**

对照设计文档 §10 的清单逐项打勾,不通过的记录为 issue。

- [ ] **Step 3: 记录最终降级级别**

在 `docs/superpowers/acceptance-checklist.md` 中写明:实际命中的音频降级级别、实测码率、每小时流量。

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: record end-to-end acceptance results"
```

---

## Self-Review 记录

- **Spec 覆盖:** §3.1 降级链→Task 0/8/9;§3.2 字幕即歌词→Task 13/14/15/20;§3.3 多服务器多地址→Task 4/6/17;§3.4 HTTPS 证书→Task 5;§3.5 播放行为→Task 9/10/11/12/21;§6 界面→Task 16–22;§8 错误处理→分散于 Task 4/12/15/17;§10 测试→各 Task 内含 + Task 23。
- **占位符:** Task 5 的证书 PEM 为夹具占位,已在步骤中写明生成方式;Task 8 的 L1/L2 URL 模板依赖 Task 0 结论,已标注为阻塞依赖。
- **类型一致性:** `PlaybackSource` / `AudioDeliveryLevel` / `SubtitleTimeline` / `SubtitleLine` 在 Task 2 定义,后续 Task 8/13/15/20 引用一致;`EndpointHealth` 在 Task 2 定义,Task 4/17 使用一致。
