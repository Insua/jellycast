plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
// 注意:AGP 9 内置 Kotlin 支持,不 apply kotlin-android 插件。

android {
    namespace = "dev.insua.jellycast"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.insua.jellycast"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 注意:没有 kotlinOptions { },jvmTarget 通过下面的 kotlin { } 块配置。
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // 后续 Task 引入的模块(:core:model / :core:network / :core:designsystem /
    // :feature:server / :feature:home / :feature:library / :feature:player / :feature:settings)
    // 尚未创建,故不在此声明。:core:player 在 Task 9/10 引入,因为 PlaybackService 需要注册到本模块的 Manifest。
    implementation(project(":core:player"))

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

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
