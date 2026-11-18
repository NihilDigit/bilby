import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// 桌面的原生库按构建机的架构取:jpackage 不能交叉打包,x64 与 arm64 的发行包各在对应的机器上构建。
val isWindowsArm64Host = System.getProperty("os.name").startsWith("Windows") &&
    System.getProperty("os.arch") == "aarch64"
val zstdClassifier = if (isWindowsArm64Host) "win_aarch64" else "win_amd64"

// 目标只有 Android 与桌面 JVM。两者都跑在 JVM 上,KGP 因此不单独编译 commonMain 的元数据,
// 公共代码随各平台编译,可以直接用 JDK 与纯 Java 库(java.io.File、OkHttp、zxing)。
// 平台源码集只放两端确实不同的东西:Android 框架、Media3、mpv。
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "dev.bilby"
        // Compose 1.13 要求 compileSdk 37.1
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
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
            // 应用内差分更新要的 libzstd,取只捆本架构原生库的那一份,理由见 update/ZstdPatch.kt。
            implementation("${libs.zstd.jni.get()}:$zstdClassifier")
            if (isWindowsArm64Host) runtimeOnly(project(":sqlite-woa64"))
        }
        androidMain.dependencies {
            // commonMain 的 CMP 1.13.0-rc01 / navigation3 1.2.0 / adaptive 1.3.0 / lifecycle 2.11.0
            // 在 Android 端已直接重定向到完全一致的 AndroidX 构件;此处保留 BOM 以把
            // material-icons-extended 从 1.7.3 抬到 1.7.8,并与 :app 的 ui-tooling 对齐。
            api(project.dependencies.platform(libs.androidx.compose.bom))
            api(libs.androidx.compose.material.icons.extended)

            api(libs.androidx.core.ktx)
            api(libs.androidx.activity.compose)

            api(libs.androidx.media3.exoplayer)
            api(libs.androidx.media3.exoplayer.dash)
            api(libs.androidx.media3.exoplayer.hls)
            api(libs.androidx.media3.ui.compose)
            api(libs.androidx.media3.session)
            api(libs.androidx.media3.extractor)

            api(libs.androidx.room.runtime)
            api(libs.androidx.datastore.preferences)
            api(libs.androidx.work.runtime.ktx)
            api(libs.coil.gif)
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
        // 差分更新的测试要真的解 zstd。发行包里那一份只捆 Windows 的原生库,CI 的单测跑在 Linux 上,
        // 初始化即失败;测试另取捆全平台原生库的那一份,类与版本相同。
        getByName("desktopTest").dependencies {
            runtimeOnly(libs.zstd.jni)
            val testOs = when {
                System.getProperty("os.name").startsWith("Windows") -> "windows"
                System.getProperty("os.name").startsWith("Mac") -> "macos"
                else -> "linux"
            }
            val testArch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
            // 手势回归通过离屏 Compose 场景发送真实指针事件,运行库版本与应用保持一致。
            runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-$testOs-$testArch:${libs.versions.composeMultiplatform.get()}")
        }
    }
}

// Windows 应用内更新器:纯 java.base 单类打包为独立可执行 jar 并以 .bin 资源名放进 desktopMain 类路径,
// 既彻底消除运行期释放 .ps1 调 powershell.exe -ExecutionPolicy Bypass,又避免 ProGuard 过滤嵌套 .jar。
val compileWindowsUpdateApplier = tasks.register<JavaCompile>("compileWindowsUpdateApplier") {
    source = fileTree("updaterHelper/src/main/java") { include("**/*.java") }
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("updaterHelper/classes"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) })
    options.encoding = "UTF-8"
    options.release.set(21)
}
val packageWindowsUpdateApplier = tasks.register<Jar>("packageWindowsUpdateApplier") {
    dependsOn(compileWindowsUpdateApplier)
    from(compileWindowsUpdateApplier.map { it.destinationDirectory })
    manifest {
        attributes("Main-Class" to "dev.bilby.updater.WindowsUpdateApplier")
    }
    destinationDirectory.set(layout.buildDirectory.dir("updaterHelper/resources/update"))
    archiveFileName.set("bilby-update-applier.bin")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
kotlin.sourceSets.named("desktopMain") {
    resources.srcDir(layout.buildDirectory.dir("updaterHelper/resources"))
}
tasks.matching { it.name == "desktopProcessResources" || it.name == "assembleDesktopMainResources" }.configureEach {
    dependsOn(packageWindowsUpdateApplier)
}

// 桌面目标的编译与单测用 JDK 25:差分解码经 FFM(java.lang.foreign)直调 libzstd,它在 JDK 22 转正,
// 21 上还是预览 API。只改桌面这两个任务,Android 的编译与 Robolectric 单测仍用运行 Gradle 的 JDK。
// 字节码仍是 17,发行包跑在 Zulu 25 上。
val desktopJdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
tasks.named<KotlinJvmCompile>("compileKotlinDesktop") {
    kotlinJavaToolchain.toolchain.use(desktopJdk)
}
tasks.named<Test>("desktopTest") {
    javaLauncher = desktopJdk
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
if (isWindowsArm64Host) {
    // Robolectric 的 native runtime 与 Conscrypt 都不提供 Windows ARM64 原生库;这里的 Robolectric
    // 单测只用 Looper、Bundle 与 MediaSession,不碰 SQLite 与 Conscrypt TLS,在 ARM64 宿主上关掉即可。
    tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
        systemProperty("robolectric.sqliteMode", "LEGACY")
        systemProperty("robolectric.conscryptMode", "OFF")
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

composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

compose {
    dependencyCompatibility {
        // Coil 3.6.3 传递依赖声明了 skiko 0.150.1,由 CMP 1.13.0-rc01 统一解析到 0.156.1。
        exclude("io.coil-kt.coil3")
    }
    resources {
        packageOfResClass = "dev.bilby.resources"
        generateResClass = always
    }
}
