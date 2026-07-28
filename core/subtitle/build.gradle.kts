plugins {
    alias(libs.plugins.android.library)
}
// 注意:AGP 9 内置 Kotlin 支持,不 apply kotlin-android 插件。
//
// 本模块从纯 JVM(kotlin.jvm)改成 android.library,原因见
// docs/superpowers/specs/2026-07-28-crash-and-usability-design.md §2:
// 解析逻辑是纯 Kotlin,但它跑在 Android 上,而 Android(ICU)的正则引擎和 JVM(OpenJDK)不是
// 同一个实现——纯 JVM 单测在结构上不可能抓到这类平台差异,必须有一份在真机/模拟器上跑的
// 冒烟测试(见 src/androidTest)。主源码集本身不依赖任何 Android API,迁移对生产代码零影响。

android {
    namespace = "dev.insua.jellycast.subtitle"
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
}

// AGP library 模块下 tasks.test 访问器不可用(它是 DefaultTask 聚合器),
// 必须用 tasks.withType<Test> 显式启用 JUnit5。
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
