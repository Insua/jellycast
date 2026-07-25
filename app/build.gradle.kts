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
    // Task 21/22:装配全部模块——:app 是唯一的 Hilt 组合根,需要能直接引用每个模块的具体类型
    // 才能写 @Provides(即使某些绑定实际发生在 feature:server/core:player 自己的 DI 模块里)。
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:subtitle"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:player"))
    implementation(project(":feature:server"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.tooling)

    // MediaController 连接 PlaybackService(:feature:player 的 PlayerConnection 真实实现)。
    implementation(libs.media3.session)

    // 会话装配需要的 OkHttp/Retrofit——真正的类都在 :core:network,这里只是把它们组装成绑定。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines)

    // Coil 单例 ImageLoader:让封面图请求复用 :core:network 的证书信任策略(修正 §1c)。
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
