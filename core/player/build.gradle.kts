plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // v3 复审 Finding 2:给 Robolectric 挂 JUnit Platform Launcher Interceptor,只对本模块的
    // 单测生效——SeekInterceptingPlayerTest 里真实断言 Player.Commands 的内容需要它。
    alias(libs.plugins.robolectric.junit5)
}
// 注意:AGP 9 内置 Kotlin 支持,不 apply kotlin-android 插件。

android {
    namespace = "dev.insua.jellycast.player"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    // CacheAwareSourceProvider 消费 Task 3 交付的 AudioCacheStore。
    implementation(project(":core:cache"))

    implementation(libs.kotlinx.coroutines)
    // NotificationCompat / ServiceCompat.startForeground —— PlaybackService 自己进前台需要它们
    // (见 PlaybackService.onStartCommand 的说明)。
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // HttpStreamProbe(探测 L1 音频流是否真的不含视频轨)与 PlayerModule 的 OkHttpClient 装配。
    implementation(libs.okhttp)

    // PlayerModule 引用 JellyCastDatabase(继承自 RoomDatabase)——:core:database 对 room-runtime
    // 是 implementation 依赖不会传递,这里需要显式声明才能在编译期看到 RoomDatabase 这个父类型。
    implementation(libs.room.runtime)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric.junit5.extension)

    // LocalAdtsSeekableDeviceTest(C1 复审):裸 ADTS 文件是否真的可 seek 是平台行为,
    // JVM 单测结构上测不出(v4 铁律),需要真实 ExoPlayer + 真实 MediaCodec。
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}

// AGP library 模块下 tasks.test 访问器不可用(它是 DefaultTask 聚合器),
// 必须用 tasks.withType<Test> 显式启用 JUnit5。
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
