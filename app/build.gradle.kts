import java.util.Properties

// LLM 凭据放 local.properties(不进 git),只注入 debug 版的 BuildConfig 作为默认值;
// 运行期的真相来源仍是 DataStore,用户可改(DESIGN 4 节)。release 版拿到的是空串。
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

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
        versionCode = 1
        versionName = "0.1.0"
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
            // 自用单用户应用,不上架,也就没有发布密钥。用 debug 密钥签名只为了 release 包
            // 能装到机器上真的跑一遍 —— R8 造成的崩溃只在 release 出现,不装就等于没验。
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
