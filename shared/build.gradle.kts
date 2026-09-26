import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 目标只有 Android 与桌面 JVM。两者都跑在 JVM 上,KGP 因此不单独编译 commonMain 的元数据,
// 公共代码随各平台编译,可以直接用 JDK 与纯 Java 库(java.io.File、OkHttp、zxing)。
// 平台源码集只放两端确实不同的东西:Android 框架、Media3、mpv。
kotlin {
    android {
        namespace = "dev.bilby"
        compileSdk = 37
        minSdk = 29
        androidResources { enable = true }
        withHostTest {
            // JVM 单测里 android.util.Log 的方法默认抛异常。本项目的纪律是每一处被吞掉的
            // 失败都要记日志(见 CLAUDE.md),于是任何走到日志的分支在单测里都会炸——
            // 炸的不是被测逻辑,是日志本身。让这些方法返回默认值,测的才是逻辑。
            isReturnDefaultValues = true
            // Robolectric 只服务 player/LazyMediaSourceTest:Media3 的 MediaSource 在准备和
            // 释放的每一步上都要一个 Looper。其余单测仍是纯 JVM 的,不受影响。
            isIncludeAndroidResources = true
        }
        compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    }
    // Compose Desktop 自带的 ProGuard 读不了太新的 class 文件版本,桌面产物固定在 17。
    jvm("desktop") {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.tdanmaku)

            api(libs.compose.mp.runtime)
            api(libs.compose.mp.ui)
            api(libs.compose.mp.foundation)
            api(libs.compose.mp.animation)
            api(libs.compose.mp.material3)
            api(libs.compose.mp.material.icons.extended)
            api(libs.compose.mp.ui.tooling.preview)
            api(libs.compose.mp.resources)

            api(libs.navigation3.mp.ui)
            api(libs.adaptive.mp.navigation3)
            api(libs.lifecycle.mp.runtime.compose)
            api(libs.lifecycle.mp.viewmodel.compose)
            api(libs.lifecycle.mp.viewmodel.navigation3)

            api(libs.ktor.client.core)
            api(libs.ktor.client.okhttp)
            api(libs.ktor.client.content.negotiation)
            api(libs.ktor.serialization.kotlinx.json)
            api(libs.ktor.client.logging)
            api(libs.ktor.client.websockets)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)

            api(libs.coil.compose)
            api(libs.coil.network.okhttp)
            api(libs.zxing.core)

            api(libs.androidx.room.runtime)
            api(libs.androidx.datastore.preferences.core)
        }
        getByName("desktopMain").dependencies {
            // Android 用系统自带的 SQLite;桌面没有,Room 要一份打包进来的原生库。
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.compose.mp.ui.backhandler)
            api(libs.mediamp.mpv)
            // 应用内更新查 MSI 的安装登记(update/WindowsInstaller.kt)。
            implementation(libs.jna.platform)
        }
        androidMain.dependencies {
            // Android 目标上 Compose 各构件按 BOM 取,比 CMP 1.12.1 映射到的版本新
            // (material3 alpha25 对 alpha22)。桌面端编译的是 CMP 那一套,两边都得过。
            api(project.dependencies.platform(libs.androidx.compose.bom))
            api(libs.androidx.compose.ui)
            api(libs.androidx.compose.material3)
            api(libs.androidx.compose.material.icons.extended)
            api(libs.androidx.navigation3.runtime)
            api(libs.androidx.navigation3.ui)
            api(libs.androidx.adaptive.navigation3)
            api(libs.androidx.lifecycle.viewmodel.navigation3)

            api(libs.androidx.core.ktx)
            api(libs.androidx.activity.compose)
            api(libs.androidx.lifecycle.runtime.compose)
            api(libs.androidx.lifecycle.viewmodel.compose)

            api(libs.androidx.media3.exoplayer)
            api(libs.androidx.media3.exoplayer.dash)
            api(libs.androidx.media3.exoplayer.hls)
            api(libs.androidx.media3.ui.compose)
            api(libs.androidx.media3.session)
            api(libs.androidx.media3.extractor)

            api(libs.androidx.room.runtime)
            api(libs.androidx.datastore.preferences)
            api(libs.androidx.work.runtime.ktx)
        }
        // 公共单测两个目标各跑一遍。两端都是 JVM,直接用 JUnit 4,不另套 kotlin.test。
        commonTest.dependencies {
            implementation(libs.junit)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
        }
    }
}

// mediamp 0.5.0 把 ui-test-junit4 误放在运行时作用域,理由同 :desktop 里的同一条排除。
configurations.configureEach {
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}

compose.resources {
    packageOfResClass = "dev.bilby.resources"
    generateResClass = always
}
