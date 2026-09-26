package dev.bilby.desktop

import kotlin.system.exitProcess

/**
 * 打包 release 时,Compose 插件带着这个系统属性把应用跑一遍,JVM 退出时写出 AOT 缓存
 * (见 desktop/build.gradle.kts 的 buildTypes.release.aot)。训练进程要自己退出,否则打包一直等着。
 */
private const val AOT_TRAINING_PROPERTY = "compose.aot.training-run"

/** 足够走完开窗、首屏与首页列表这段启动路径。 */
private const val AOT_TRAINING_MILLIS = 12_000L

internal fun exitAfterAotTraining() {
    if (System.getProperty(AOT_TRAINING_PROPERTY) != "true") return
    Thread({ Thread.sleep(AOT_TRAINING_MILLIS); exitProcess(0) }, "Bilby-Aot-Training")
        .apply { isDaemon = true; start() }
}
