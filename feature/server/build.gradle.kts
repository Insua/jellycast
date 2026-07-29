plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
// 注意:AGP 9 内置 Kotlin 支持,不 apply kotlin-android 插件。

android {
    namespace = "dev.insua.jellycast.feature.server"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
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
    implementation(project(":core:datastore"))
    implementation(project(":core:designsystem"))
    // 删除服务器时要清掉该服务器的缓存条目(CachedItemDao.clearServer)——只用接口,不摸
    // RoomDatabase 基类,所以和 :core:network 一样是 implementation,不需要额外声明
    // room-runtime(参见 :core:network/build.gradle.kts 同一处注释)。
    implementation(project(":core:database"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.tooling.preview)
    debugImplementation(libs.compose.tooling)

    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // 组装每个 endpoint 各自的 JellyfinApi 客户端(需要按 endpoint 各自的 baseUrl / 证书信任
    // 配置分别构造 Retrofit 实例,详见 JellyfinApiFactory.kt)。
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// AGP library 模块下 tasks.test 访问器不可用(它是 DefaultTask 聚合器),
// 必须用 tasks.withType<Test> 显式启用 JUnit5。
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
