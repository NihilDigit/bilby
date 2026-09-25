package dev.bilby.ui

import dev.bilby.BiliLog
import dev.bilby.resources.*
import org.jetbrains.compose.resources.StringResource
import dev.bilby.api.BiliResult
import dev.bilby.api.CODE_NOT_LOGGED_IN
import java.io.IOException

/**
 * 失败在屏幕上说哪一句。**全应用只有三句**,这里是唯一的产出口。
 *
 * 原先七处各自写 `"$message($code)"`,于是屏幕上出现的是「请求错误(-352)」这种话:接口的
 * message 是写给调用方看的,错误码更是,而读到它的人拿它做不了任何事。这一屏要回答的是
 * **现在该做什么**,而答案只有三种 —— 等网、重新登录、以及没别的可做。分档因此就是三档,
 * 再细的区分(哪个接口、哪个业务码)进 [BiliLog],不进界面。
 *
 * 已经这么做过两处([dev.bilby.ui.search.NormalSearchController]、
 * [dev.bilby.ui.login.TvLoginScreen] 各写了一份自己的映射),这一份是把那条判断提出来共用。
 *
 * **返回的是资源 id,不是字符串。** ViewModel 里没有 Context,拼好的中文串既不跟语言设置走,
 * 也没法在测试里断言;state 里存 id,`stringResource` 留给调用点。
 *
 * @param where 日志里这条失败发生在哪(「空间页取投稿」这类)。原文与错误码只落在这里。
 */
fun BiliResult<*>.errorTextRes(where: String): StringResource = when (this) {
    // Ok 不该走到这里,但几处调用点是 `when (result) { is Ok -> …; else -> result.errorTextRes() }`,
    // 编译期收窄不到,抛异常等于把一个不可能的分支变成崩溃。退回最钝的那一句。
    is BiliResult.Ok -> Res.string.error_refused

    is BiliResult.ApiError -> {
        BiliLog.w("$where 失败($code): $message")
        // 凭据过期是唯一一档用户能自己解决的业务失败:access_key 与 cookie 都不能续期,
        // 过期就得重新扫码(notes/auth-model.md 末节)。别的业务码对用户是同一件事。
        if (code == CODE_NOT_LOGGED_IN) Res.string.error_login_expired else Res.string.error_refused
    }

    is BiliResult.Failure -> {
        BiliLog.w("$where 异常", cause)
        errorTextResFor(cause)
    }
}

/**
 * 异常那一侧的分档。[IOException] 一族(DNS、连不上、超时、证书)是用户自己能处理的;
 * 剩下的(解不开返回体一类)他做什么都没用。
 *
 * 单独留一个入口是给拿不到 [BiliResult] 的调用方用的(助理那条链路上是 LLM 的 HTTP 客户端)。
 */
fun errorTextResFor(cause: Throwable): StringResource =
    if (cause is IOException) Res.string.error_network else Res.string.error_refused
