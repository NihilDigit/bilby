import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Compose Desktop 自带的 ProGuard 读不了太新的 class 文件版本,产物固定在 17。
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 下列构件在 mediamp 0.5.0 字节码里零引用:material3 与图标是它的通用依赖顺带的,
// ui-test-junit4 误放在运行时作用域,会把 junit、truth、guava、coroutines-test 带进发行包。
// 排除写在配置上而不是 mediamp 依赖上:它是 KMP 根坐标,经 available-at 跳到 -desktop 变体后,
// 依赖级的 exclude 不再生效。
configurations.configureEach {
    exclude(group = "org.jetbrains.compose.material3")
    exclude(group = "org.jetbrains.compose.material", module = "material-icons-extended")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-test-junit4")
}

dependencies {
    implementation(libs.compose.mp.runtime)
    implementation(libs.compose.mp.ui)
    implementation(libs.compose.mp.foundation)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.tdanmaku)
    implementation(libs.mediamp.mpv)
    // mediamp 的 Gradle 元数据不带运行时包,平台原生库须显式声明。只做 x64,WoA 后续再加。
    runtimeOnly(libs.mediamp.mpv.runtime.windows.x64)
    runtimeOnly(compose.desktop.windows_x64)
}

compose.desktop {
    application {
        mainClass = "dev.bilby.desktop.demo.MainKt"
        // JDK 24 起加载 JNI 库属受限操作,不声明会在每次启动时打印警告
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        providers.gradleProperty("video").orNull?.let { args += listOf("--video", it) }
        providers.gradleProperty("audio").orNull?.let { args += listOf("--audio", it) }

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Bilby"
        }
    }
}
