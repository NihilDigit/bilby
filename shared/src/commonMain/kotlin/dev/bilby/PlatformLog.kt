package dev.bilby

/** 日志最终落到哪里。Android 是 logcat,桌面是标准错误。规矩在 [BiliLog]。 */
internal expect object PlatformLog {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable?)
    fun e(tag: String, message: String, throwable: Throwable?)
}
