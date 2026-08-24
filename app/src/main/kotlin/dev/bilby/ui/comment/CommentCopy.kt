package dev.bilby.ui.comment

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import dev.bilby.R

/**
 * 长按正文打开选择面板([dev.bilby.ui.components.SelectableTextDialog])。
 *
 * **这里以前是长按当场复制整条。** 换成面板是因为"只想要中间那一句"没有出口,而整条复制在
 * 面板底部仍然是一个按钮 —— 常见的那一次没有变贵多少,不常见的那一次从做不到变成做得到。
 *
 * **只挂在正文上,不挂在整条评论上。** 进剪贴板的只有正文 —— 名字、时间、属地、点赞数不是
 * 这条评论的内容,是它的元信息。挂在整条上还有一个更实际的问题:主楼和摊开的楼中楼本来都
 * 没有整行点击,给它们套一个 `combinedClickable` 等于凭空多出一圈整行涟漪和一个 click 语义。
 *
 * **正文里的链接不受影响,这一点是查过实现的。** Compose 把 `LinkAnnotation` 渲染成盖在链接
 * 范围上的**子** `Box`,各自带 `combinedClickable`(foundation 的 `TextLinkScope.LinksComposables`)。
 * 指针事件在 Main 这一趟是子先于父,链接那个子节点会先把 down 消费掉,所以:
 *
 * - 点链接:子节点消费,这里的 `awaitFirstDown(requireUnconsumed = true)` 根本不返回,链接照常打开。
 * - 点正文空白处:没有子节点接,长按成立,面板打开。
 *
 * 代价是**长按正好压在链接上不会打开面板**(那个子 Box 消费了 down,却没有 onLongClick)。
 * 一条评论里链接只占几个字,旁边随便哪里都能长按,所以没有为它另铺一条路。
 *
 * 读屏那一侧另走 `semantics` 的 `onLongClick`:`pointerInput` 对无障碍服务不可见,
 * 而语义动作不改变触摸行为,两条互不干涉。
 *
 * @param onSelect 报告要选哪一段文字。**传的是原文**(见 SelectableTextDialog),
 *   由调用方把面板挂在列表之外的那一层 —— 挂在行里的话,行一被回收面板就跟着消失。
 */
@Composable
internal fun Modifier.selectTextOnLongPress(text: String, onSelect: (String) -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    val actionLabel = stringResource(R.string.text_select_title)

    // pointerInput 只在 key 变化时重启,直接捕获会把某一次组合时的值焊进那个还在跑的手势
    // 协程里。key 取 Unit,靠这一层拿最新的一份。
    val currentText by rememberUpdatedState(text)
    val currentOnSelect by rememberUpdatedState(onSelect)

    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    // detectTapGestures 不带触觉反馈(combinedClickable 才自带)。没有这一下,
                    // 手指还按着的时候没有任何东西说明"已经成了",人会继续按下去。
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnSelect(currentText)
                },
            )
        }
        .semantics { onLongClick(actionLabel) { currentOnSelect(currentText); true } }
}
