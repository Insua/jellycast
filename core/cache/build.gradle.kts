plugins {
    alias(libs.plugins.android.library)
}
// 注意:AGP 9 内置 Kotlin 支持,不 apply kotlin-android 插件。

android {
    namespace = "dev.insua.jellycast.cache"
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
    // AudioCacheStore 消费 Task 1 交付的 CachedAudioDao / CachedAudioEntity。
    implementation(project(":core:database"))

    // AudioCacheDownloader 用它做实际的 HTTP 下载;NetworkTypeMonitor 只用 android.net,
    // 不需要额外依赖。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // AudioCacheStoreTest 需要真实文件系统 + 真实 ConnectivityManager,属于 androidTest。
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    // 直接在测试里用 Room.inMemoryDatabaseBuilder 构造 JellyCastDatabase——:core:database 对
    // room-runtime 是 implementation 依赖不会传递,这里需要显式声明才能在编译期看到 Room 这个类型。
    androidTestImplementation(libs.room.runtime)
    // 下载测试用本机回环端口起一个假服务器,拿到"中途取消"这种真实网络传输才有的确定性控制。
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// AGP library 模块下 tasks.test 访问器不可用(它是 DefaultTask 聚合器),
// 必须用 tasks.withType<Test> 显式启用 JUnit5。
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
