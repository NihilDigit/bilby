package com.bilby

import android.util.Log

/**
 * 全仓库统一的日志入口。约定(见 team 讨论):tag 固定 "Bilby";业务失败(接口返回非 0
 * 的 code)与被吞掉的异常一律 [w],真正致命的用 [e];只在 debug 构建打印,调用点不用
 * 自己重复判断 BuildConfig.DEBUG。
 *
 * 绝不打印凭据(SESSDATA/bili_jct/access_key/LLM key/cookie 值)——调用方负责只传
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
