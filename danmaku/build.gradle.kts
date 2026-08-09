// 这个模块将来要整体搬进一个独立发布的库(给 Bilby 和 Animeko 共用,见 notes/danmaku.md
// §9),所以纪律从第一行开始。两层分开:
//   dev.danmaku.compose 下的编排层文件(Danmaku.kt、DanmakuViewport.kt、DanmakuLayoutConfig.kt、
//   DanmakuFlightPlan.kt、DanmakuScheduler.kt、DanmakuTimeline.kt、DanmakuCompiler.kt、
//   ProcessingReport.kt、DanmakuHash.kt、DanmakuFrameRateCap.kt、SpecialDanmaku.kt)只依赖 kotlin stdlib,
//   不导入 android.*/Compose/协程 —— 搬 commonMain 时要是纯粹的文件复制。
//   渲染 host 这一层需要 Compose 才能画东西,允许引 compose-ui/foundation 与协程,但不引
//   material3 —— 库不该带主题,主题是宿主 app 的事。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.danmaku.compose"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
