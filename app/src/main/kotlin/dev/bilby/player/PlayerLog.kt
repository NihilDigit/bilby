package dev.bilby.player

import android.util.Log
import dev.bilby.BuildConfig

/**
 * 播放器的诊断日志。
 *
 * 和 [dev.bilby.BiliLog] 分开写不是重复造轮子:`BiliLog` 只有 w/e 两级,按 DESIGN 第 8 节
 * 它承担的是"被吞掉的失败必须留痕"。这里要打的是**成功路径上的事实**——实际选中的解码器名、
 * 本机硬解能力、倍速用的哪个算法。这些不是失败,用 w 打会把真正的失败淹掉;而它们又必须能在
 * 真机 logcat 里查到,否则"到底走没走硬解"只能靠猜。
 *
 * 同样只在 debug 构建输出,tag 与 BiliLog 一致,`adb logcat -s Bilby` 一次看全。
 */
internal object PlayerLog {
    private const val TAG = "Bilby"

    val isDebug: Boolean get() = BuildConfig.DEBUG

    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
