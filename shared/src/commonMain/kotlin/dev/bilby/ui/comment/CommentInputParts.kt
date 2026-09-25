package dev.bilby.ui.comment

import androidx.compose.runtime.Composable
import dev.bilby.resources.*
import dev.bilby.stringResource

/**
 * 写评论时的字数计数,**过了 [CounterFrom] 才出现**,而且不拦输入。
 *
 * 和弹幕那条计数器不是一件事:弹幕有 100 字的硬上限(PiliPlus 的 `danmaku.dart` 注明),
 * 超出的按键必须被拦住并且要有计数器解释为什么。评论这一侧 **PiliPlus 没有任何客户端长度
 * 限制**(`pages/video/reply_new/view.dart` 里没有 `maxLength`,也没有 LengthLimiting 的
 * formatter),所以这里不敢写一个上限去拦:写错了就是一条合法评论发不出去,而且只在长评论上
 * 才犯。1000 是站内 web 版编辑器的那个数,拿来当"快到头了"的刻度,真正的判决交给服务端 ——
 * 被拒之后草稿留在框里,原因在发送键上方那一行。
 *
 * 返回 null 即不显示。
 */
@Composable
internal fun commentDraftCounter(length: Int): String? {
    if (length < CounterFrom) return null
    return stringResource(Res.string.input_length_counter, length, SoftLimit)
}

/** 站内 web 版评论编辑器的字数刻度。**不是本地上限**,理由见 [commentDraftCounter]。 */
private const val SoftLimit = 1000

/** 到这个长度才把计数器画出来。写两句话的人不需要被提醒还剩多少。 */
private const val CounterFrom = 800
