plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
// 注意:AGP 9 内置 Kotlin 支持,不 apply kotlin-android 插件。

android {
    namespace = "dev.insua.jellycast.player"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
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

    implementation(libs.kotlinx.coroutines)
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
}

// AGP library 模块下 tasks.test 访问器不可用(它是 DefaultTask 聚合器),
// 必须用 tasks.withType<Test> 显式启用 JUnit5。
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
