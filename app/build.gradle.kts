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

/**
 * 端到端测试(:app 的 androidTest)要连一台**真实** Jellyfin 服务器,地址/账号/密码从项目根的
 * `testing.properties` 读取。该文件已被 .gitignore 排除,**绝不进版本库**;可提交的只有
 * `testing.properties.example`(纯占位符)。
 *
 * 拿不到时三个值都是空字符串,`TestCredentials.assumeConfigured()` 会让端到端测试**跳过**而不是
 * 失败 —— 和上面签名材料的取舍一致:别人 clone 下来不配服务器也能拿到全绿的构建。
 */
val e2eCredentials: Properties = Properties().apply {
    val file = rootProject.file("testing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/**
 * 生成 `buildConfigField` 用的 Java 字符串字面量。必须转义反斜杠与双引号 —— 密码里出现
 * 这两个字符时,不转义会直接生成一段语法错误的 BuildConfig.java(而报错信息里会带上密码片段)。
 */
fun e2eProp(key: String): String {
    val raw = e2eCredentials.getProperty(key).orEmpty()
    val escaped = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    return "\"$escaped\""
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

        // HiltTestApplication 需要一个自定义 runner,见 androidTest 下的 CustomTestRunner。
        testInstrumentationRunner = "dev.insua.jellycast.e2e.CustomTestRunner"
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
            // 🔴 端到端凭据**只**注入 debug 变体(androidTest 跑的就是 debug)。
            // 刻意不写在 defaultConfig 里 —— 那样 release APK 里也会带上真实服务器地址和密码。
            buildConfigField("String", "E2E_BASE_URL", e2eProp("e2e.baseUrl"))
            buildConfigField("String", "E2E_USERNAME", e2eProp("e2e.username"))
            buildConfigField("String", "E2E_PASSWORD", e2eProp("e2e.password"))

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

    /**
     * 每个 instrumentation 测试跑在**自己的进程**里。
     *
     * 不是洁癖,是实测的必要条件:这一套端到端测试全部依赖 `@Singleton`(ExoPlayer、PlayQueue、
     * PlaybackService、JellyfinSession……),它们在同一个进程里被所有用例共享。
     * `OfflineE2eTest` 会真的把设备切进飞行模式再切回来 —— 之后 `PlaybackE2eTest` 的"一集播完
     * 自动连播"就再也不会推进队列(STATE_ENDED 到了、队列一步不动),而它单独跑、或者整套里
     * 去掉断网用例之后,都是绿的。也就是说进程内残留状态确实会跨用例传染。
     *
     * Orchestrator 每跑一个用例重开一次进程,把这类耦合从根上去掉 —— 这也是官方对
     * "测试互相污染 / 共享单例" 给出的标准答案。代价是每个用例多几秒启动时间。
     */
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    // buildConfig = true:上面的 buildConfigField 需要它才会生成 BuildConfig 类。
    buildFeatures {
        compose = true
        buildConfig = true
    }
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
    // NetworkModule 引用 JellyCastDatabase(继承自 RoomDatabase)取 CachedItemDao——:core:database
    // 对 room-runtime 是 implementation 依赖不会传递,这里需要显式声明才能在编译期看到父类型
    // (与 :core:player 的 PlayerModule 同样的理由)。
    implementation(libs.room.runtime)
    implementation(project(":core:subtitle"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:player"))
    implementation(project(":core:diagnostics"))
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
    // AppSessionViewModelTest(Task 5:冷启动恢复迷你播放条)第一次在 :app 的 JVM 单测里需要
    // 造假 ServerStore/JellyfinSession/AudioPlaybackEngine/PlayerConnection/LastPlayedStore,
    // 沿用其余模块(:feature:home 等)已经在用的同一套 MockK + kotlinx-coroutines-test 组合。
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    // ⚠️ 源码集纪律:src/test 是 JUnit5 + MockK,src/androidTest 是 JUnit4 + AndroidJUnit4。
    // 两套框架不混用 —— instrumentation 侧只有 JUnit4 能被 AndroidJUnitRunner 驱动。
    androidTestImplementation(libs.junit4)
    // 离线提示条必须被断言成"在视口里真的看得见"(assertIsDisplayed),不能只断言 ViewModel 状态:
    // 真机验证时出现过 isOffline=true、提示条也被组合出来了,却因为懒列表的锚定行为被顶到视口
    // 上方,用户一眼看不到。只有 Compose 语义树上的可见性断言能抓住这种形状的缺陷。
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    // SystemBarsAppearanceTest 直接读 WindowCompat.getInsetsController —— :app 只把 core-ktx
    // 声明成 implementation,androidTest 的编译期 classpath 不会传递拿到,需要单独声明一次。
    androidTestImplementation(libs.androidx.core.ktx)
    androidTestUtil(libs.androidx.test.orchestrator)
    androidTestUtil(libs.androidx.test.services)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // ListScrollRestoreTest(Task 5:列表滚动位置复现)手搭一个 NavHost 直接驱动
    // LibraryScreenContent/LibraryUiState——这两个依赖在 :app 的 main 源码集里只是
    // `implementation`,不会传递到 androidTest 的编译期 classpath(同上面 core-ktx 那条注释
    // 的道理),需要单独声明一次。
    androidTestImplementation(project(":core:model"))
    androidTestImplementation(project(":feature:library"))
    androidTestImplementation(libs.navigation.compose)
    androidTestImplementation(libs.androidx.lifecycle.viewmodel.compose)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    // 端到端测试直接注入 @Singleton 的 ExoPlayer(和 PlaybackService 用的是同一个实例),
    // 需要在编译期看到这个类型 —— :core:player 对 media3 是 implementation 依赖,不传递。
    androidTestImplementation(libs.media3.exoplayer)
    androidTestImplementation(libs.kotlinx.coroutines)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
