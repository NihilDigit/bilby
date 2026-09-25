package dev.bilby

import java.time.LocalTime

internal actual object PlatformLog {
    actual fun d(tag: String, message: String) = print("D", tag, message, null)

    actual fun w(tag: String, message: String, throwable: Throwable?) = print("W", tag, message, throwable)

    actual fun e(tag: String, message: String, throwable: Throwable?) = print("E", tag, message, throwable)

    private fun print(level: String, tag: String, message: String, throwable: Throwable?) {
        System.err.println("${LocalTime.now()} $level/$tag: $message")
        throwable?.printStackTrace()
    }
}
