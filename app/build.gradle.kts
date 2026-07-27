import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}
// 注意:AGP 9 内置 Kotlin 支持,不 apply kotlin-android 插件。

/**
 * 发布签名材料的来源,按优先级:
 *   1. 环境变量(CI 用:secret 注入,keystore 由 base64 还原到 JELLYCAST_STORE_FILE 指向的路径)
 *   2. 项目根的 keystore.properties(本机用;已被 .gitignore 排除)
 *
 * **密码与 .jks 绝不进版本库。** 两者都拿不到时,release 产出未签名包而不是构建失败 ——
 * 这样别人 clone 下来仍能跑 assembleDebug 与全部测试。
 */
val releaseSigning: Properties? = run {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }

    val fromEnv = env("JELLYCAST_STORE_FILE")?.let { storeFile ->
        Properties().apply {
            setProperty("storeFile", storeFile)
            setProperty("storePassword", env("JELLYCAST_STORE_PASSWORD").orEmpty())
            setProperty("keyAlias", env("JELLYCAST_KEY_ALIAS") ?: "jellycast")
            setProperty("keyPassword", env("JELLYCAST_KEY_PASSWORD").orEmpty())
        }
    }
    if (fromEnv != null) return@run fromEnv

    val file = rootProject.file("keystore.properties")
    if (!file.exists()) return@run null
    Properties().apply { file.inputStream().use { load(it) } }
}

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

    signingConfigs {
        // 只有拿到了签名材料才注册 release 配置;拿不到就完全不注册,
        // 让 release 走"未签名"路径,而不是抱着一个半配置的 signingConfig 在构建期炸掉。
        releaseSigning?.let { props ->
            val store = file(props.getProperty("storeFile"))
            if (store.exists()) {
                create("release") {
                    storeFile = store
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                    // v1 是给 API<24 用的,本应用 minSdk 26,关掉可减小包体也更快。
                    enableV1Signing = false
                    enableV2Signing = true
                    enableV3Signing = true
                    // v4 只服务于 `adb install --incremental`(开发期快速安装大包),
                    // 它会额外产出一个 .idsig 文件。侧载安装用不到,关掉少一个文件。
                    enableV4Signing = false
                }
            } else {
                logger.warn("keystore 不存在,release 将产出未签名包:${store.absolutePath}")
            }
        }
    }

    buildTypes {
        debug {
            // 刻意**不**用发布密钥签 debug —— 保持 Android 默认调试密钥。
            // 发布密钥只在真正出包时使用;用它签日常 debug 会让私钥和密码出现在每次构建里,毫无收益。
            // (参考项目 cuoa_app 把 debug 也指向 release 签名配置,那是应当避免的做法。)
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // 注意:没有 kotlinOptions { },jvmTarget 通过下面的 kotlin { } 块配置。
}

/** 让 `./gradlew signingReport` 之外也能一眼看出当前是否配了签名。 */
tasks.register("signingStatus") {
    group = "verification"
    description = "打印当前 release 签名配置来源,不泄露任何密码。"
    val configured = releaseSigning != null
    val source = when {
        System.getenv("JELLYCAST_STORE_FILE")?.isNotBlank() == true -> "环境变量"
        rootProject.file("keystore.properties").exists() -> "keystore.properties"
        else -> "无"
    }
    doLast {
        println(if (configured) "release 签名:已配置(来源:$source)" else "release 签名:未配置 —— release 将产出未签名包")
    }
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
