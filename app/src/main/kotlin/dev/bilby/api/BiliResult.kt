package dev.bilby.api

import kotlinx.serialization.Serializable

/** B 站所有接口共用的外层信封。 */
@Serializable
data class BiliResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val ttl: Int = 0,
    val data: T? = null,
)

/**
 * 业务失败(code != 0)和传输失败是两回事,调用方几乎总要分别处理:前者能拿到 B 站的
 * 错误码(-101 未登录、-412 风控),后者只能重试。所以不塞进 Result<T> 里用异常区分。
 */
sealed interface BiliResult<out T> {
    data class Ok<T>(val value: T) : BiliResult<T>
    data class ApiError(val code: Int, val message: String) : BiliResult<Nothing>
    data class Failure(val cause: Throwable) : BiliResult<Nothing>
}

inline fun <T, R> BiliResult<T>.map(transform: (T) -> R): BiliResult<R> = when (this) {
    is BiliResult.Ok -> BiliResult.Ok(transform(value))
    is BiliResult.ApiError -> this
    is BiliResult.Failure -> this
}

fun <T> BiliResult<T>.getOrNull(): T? = (this as? BiliResult.Ok)?.value

/**
 * 把失败原样传给类型参数不同的调用方。合并两个接口的结果时,两条错误分支的 T 不同,
 * 直接返回会被推断成 BiliResult<Any>;错误分支本来就不携带 T,换成 Nothing 才是准确的。
 */
fun BiliResult<*>.propagateFailure(): BiliResult<Nothing> = when (this) {
    is BiliResult.Ok -> error("Ok 不是失败,不该走到这里")
    is BiliResult.ApiError -> this
    is BiliResult.Failure -> this
}

/** 未登录。Cookie 失效后所有需要登录态的接口都返回它,是触发刷新流程的信号。 */
const val CODE_NOT_LOGGED_IN = -101

/** 风控拦截。命中后按 DESIGN 5 节的礼仪退避 60s 再续。 */
const val CODE_RATE_LIMITED = -412
