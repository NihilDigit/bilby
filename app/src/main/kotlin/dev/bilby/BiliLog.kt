package dev.bilby

import android.util.Log

/**
 * 全仓库统一的日志入口,规矩见 DESIGN.md 第 8 节:tag 固定 "Bilby";业务失败(接口返回
 * 非 0 的 code)与被吞掉的异常一律 [w],真正致命的用 [e]。
 *
 * **任何被吞掉的失败都必须留一行日志。** B 站的失败几乎都是静默的——参数放错位置、
 * 写接口被风控、参数结构不对,返回的都是一个错误码,被 runCatching 吞掉后表现出来
 * 全是"功能没生效",而"没生效"指向不了任何原因。
 *
 * **警告与错误在 release 里照打。** 这里曾经整体挡在 BuildConfig.DEBUG 后面,结果是
 * 唯一会出现 R8 相关故障的那个构建,恰好一个字都不说;用户报"搜索搜一半就停了",
 * 抓下来的 logcat 里没有任何我们自己的记录。侧载应用的 logcat 不是泄露渠道,而
 * "凭据永不进日志"这条纪律本来就保证了这里没有敏感信息。
 *
 * **绝不打印凭据**(SESSDATA/bili_jct/access_key/LLM key/cookie 值)——调用方只传
 * URL 路径、错误码、message 这类不敏感信息,cookie 相关日志只能带键名。
 */
object BiliLog {
    private const val TAG = "Bilby"

    fun w(message: String) {
        Log.w(TAG, message)
    }

    /**
     * **异常的类型与 message 拼进正文,不只交给栈。** `Log.getStackTraceString` 对
     * `UnknownHostException` 有特例:遍历 cause 链命中就返回空串(AOSP 为压噪声写死的)。
     * 于是一次 DNS 失败在 logcat 里只剩一行光秃秃的 message,没有任何原因 —— 正是这个文件
     * 存在要防的那种"只知道没生效,指不出为什么"。真实代价:一次扫码登录连着七次轮询全失败,
     * 日志里七行"TV 登录轮询异常",查不出是网络还是接口。
     */
    fun w(message: String, throwable: Throwable) {
        Log.w(TAG, "$message: ${throwable.describe()}", throwable)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, throwable?.let { "$message: ${it.describe()}" } ?: message, throwable)
    }

    /** 类型 + message,并跟上 cause 链——网络异常的原因常常只在 cause 里。 */
    private fun Throwable.describe(): String = generateSequence(this) { it.cause }
        .take(CAUSE_CHAIN_LIMIT)
        .joinToString(" <- ") { "${it.javaClass.simpleName}(${it.message.orEmpty()})" }

    /** cause 链取前几层就够定位;OkHttp 的链能套十几层,全打出来只会把有用的挤走。 */
    private const val CAUSE_CHAIN_LIMIT = 3

    /** 过程信息,只在 debug 打:它的量足以淹没上面那些真正要看的行。 */
    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
