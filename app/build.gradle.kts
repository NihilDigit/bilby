import java.util.Properties

// LLM 凭据放 local.properties(不进 git),只注入 debug 版的 BuildConfig 作为默认值;
// 运行期的真相来源仍是 DataStore,用户可改(DESIGN 4 节)。release 版拿到的是空串。
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/**
 * 版本号由 tag 决定,不写在源码里 —— 写在源码里就必然会出现"tag 是 v0.2.0、包里是 0.1.0"
 * 这种对不上的发布。CI 传 `-PbilbyVersion=0.2.0`(取自 `v0.2.0` 这个 tag),本机不传时
 * 用 0.0.0-dev,一眼能认出这不是发布包。
 */
val releaseVersionName: String = (findProperty("bilbyVersion") as String?) ?: "0.0.0-dev"

/**
 * versionCode 从版本号推出来:`major*10000 + minor*100 + patch`。发布页上三个 ABI 变体
 * 共用同一个 versionCode——它们是同一个版本的三种打包,不是三个版本,装错了架构是装不上,
 * 不是"版本更旧"。
 */
val releaseVersionCode: Int = releaseVersionName
    .substringBefore('-')
    .split('.')
    .mapNotNull(String::toIntOrNull)
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }
    .coerceAtLeast(1)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.bilby"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.bilby"
        minSdk = 29
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    /**
     * 按 ABI 拆包,外加一个通用包。三个单架构变体各只带自己那一份 `.so`(media3 与
     * datastore 都有原生库),体积更小;通用包是给不知道自己该下哪个的人和存档用的,
     * 侧载分发没有商店替用户挑架构,少了它每次都要先问一句"你的机器是什么架构"。
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    /**
     * 发布签名从环境变量来,CI 之外没有这些变量时回落到 debug 密钥。
     *
     * 回落是有意的:本机 `assembleRelease` 的用途是验证 R8 有没有把东西混淆坏,而未签名的
     * APK 装不上,装不上就等于没验。发布用的密钥只存在于 CI,本机连一份都不该有。
     */
    signingConfigs {
        create("ci") {
            storeFile = System.getenv("BILBY_KEYSTORE")?.let(::file)
            storePassword = System.getenv("BILBY_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("BILBY_KEY_ALIAS")
            keyPassword = System.getenv("BILBY_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // 独立包名,debug 与 release 可以并存在同一台机器上。凭据、播放进度、
            // agent 会话也因此各存各的,不会互相污染。
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "LLM_BASE_URL", "\"${localProperties.getProperty("LLM_BASE_URL", "")}\"")
            buildConfigField("String", "LLM_API_KEY", "\"${localProperties.getProperty("LLM_API_KEY", "")}\"")
        }
        release {
            buildConfigField("String", "LLM_BASE_URL", "\"\"")
            buildConfigField("String", "LLM_API_KEY", "\"\"")
            signingConfig = signingConfigs.getByName(
                if (System.getenv("BILBY_KEYSTORE") != null) "ci" else "debug",
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 内置 Kotlin 下 jvmTarget 默认跟随 targetCompatibility,不必再写 kotlin.compilerOptions
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // JVM 单测里 android.util.Log 的方法默认抛异常。本项目的纪律是每一处被吞掉的
            // 失败都要记日志(见 CLAUDE.md),于是任何走到日志的分支在单测里都会炸——
            // 炸的不是被测逻辑,是日志本身。让这些方法返回默认值,测的才是逻辑。
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // 坐标是外部依赖的写法,但开发期由 settings.gradle.kts 的 includeBuild 替换成隔壁仓库的
    // 源码构建,这里的版本号在替换生效时不参与解析。见那边的注释。
    implementation(libs.tdanmaku)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
