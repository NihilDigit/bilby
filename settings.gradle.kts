pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// 构建要的 JDK 由 Gradle 按 toolchain 声明自己下载,本机和 CI 都不必预装:桌面端的编译、运行与
// 打包都要 Azul 25(Temurin 25 不带 jmods,见 desktop/build.gradle.kts)。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Bilby"
include(":app")
include(":shared")
include(":desktop")
// Windows ARM64 的 SQLite 原生库,见它的 build.gradle.kts
include(":sqlite-woa64")

// 弹幕引擎(https://github.com/NihilDigit/tdanmaku)已发布到 Maven Central,按坐标解析,
// 见版本目录里的 `tdanmaku`。
//
// 本地并排存在 `../tdanmaku` 且不在 CI 环境时,若两边 AGP 版本一致则自动启用复合构建,
// 让开发期直接联调隔壁仓库源码;若 AGP 版本不一致则自动回退到 Maven Central 坐标,避免 Android
// 构建触发 "Using multiple versions of the Android Gradle plugin in the same build is not allowed"。
// 可通过 `-Pbilby.localTdanmaku=true|false` 显式覆写。
// 注意:若 Bilby 使用了隔壁尚未发版的新 API,打 v tag 发版前仍须先发版 tdanmaku 并升级版本目录。
fun readAgpVersion(toml: File): String? {
    if (!toml.isFile) return null
    val match = Regex("""(?m)^\s*agp\s*=\s*"([^"]+)"""").find(toml.readText())
    return match?.groupValues?.get(1)
}

val tdanmakuDir = file("../tdanmaku")
val localTdanmakuOverride = providers.gradleProperty("bilby.localTdanmaku").orNull?.toBooleanStrictOrNull()
val isCiEnv = !providers.environmentVariable("CI").orNull.isNullOrEmpty()
val shouldIncludeLocalTdanmaku = when {
    localTdanmakuOverride != null -> localTdanmakuOverride && tdanmakuDir.isDirectory
    isCiEnv || !tdanmakuDir.isDirectory -> false
    else -> {
        val localAgp = readAgpVersion(file("gradle/libs.versions.toml"))
        val tdanmakuAgp = readAgpVersion(File(tdanmakuDir, "gradle/libs.versions.toml"))
        if (localAgp != null && tdanmakuAgp != null && localAgp != tdanmakuAgp) {
            logger.lifecycle(
                "Skipping composite build ../tdanmaku (AGP $tdanmakuAgp != Bilby AGP $localAgp); " +
                    "resolving dev.nihildigit:tdanmaku from Maven Central. Pass -Pbilby.localTdanmaku=true to force.",
            )
            false
        } else {
            true
        }
    }
}
if (shouldIncludeLocalTdanmaku) {
    includeBuild(tdanmakuDir)
}
