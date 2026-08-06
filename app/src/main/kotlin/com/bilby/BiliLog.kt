package com.bilby

import android.util.Log

/**
 * 全仓库统一的日志入口,规矩见 DESIGN.md 第 8 节:tag 固定 "Bilby";业务失败(接口返回
 * 非 0 的 code)与被吞掉的异常一律 [w],真正致命的用 [e];只在 debug 构建打印,调用点
 * 不用自己判断 BuildConfig.DEBUG。
 *
 * **任何被吞掉的失败都必须留一行日志。** B 站的失败几乎都是静默的——参数放错位置、
 * 写接口被风控、参数结构不对,返回的都是一个错误码,被 runCatching 吞掉后表现出来
 * 全是"功能没生效",而"没生效"指向不了任何原因。
 *
 * **绝不打印凭据**(SESSDATA/bili_jct/access_key/LLM key/cookie 值)——调用方只传
 * URL 路径、错误码、message 这类不敏感信息,cookie 相关日志只能带键名。
 */
object BiliLog {
    private const val TAG = "Bilby"

    fun w(message: String) {
        if (BuildConfig.DEBUG) Log.w(TAG, message)
    }

    fun w(message: String, throwable: Throwable) {
        if (BuildConfig.DEBUG) Log.w(TAG, message, throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(TAG, message, throwable)
    }
}
