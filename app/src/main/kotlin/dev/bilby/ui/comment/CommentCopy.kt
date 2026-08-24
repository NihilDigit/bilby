package dev.bilby.ui.comment

import android.content.ClipData
import android.os.Build
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import dev.bilby.R
import kotlinx.coroutines.launch

/**
 * 把一段评论正文放进剪贴板,该报回执时报一句。
 *
 * 单独拎出来是因为**触发方式有两种**,而复制这件事只有一份:没有整行点击的那几处
 * (主楼、摊开的楼中楼、面板顶上的主楼)用 [copyOnLongPress];预览层整行本来就可点,
 * 它走 `combinedClickable` 的 `onLongClick`,理由见 `SubReplyPreviewRow`。
 *
 * **返回的 lambda 不带触觉反馈。** `combinedClickable` 自己会在长按时
 * `performHapticFeedback(LongPress)`(foundation 的 `Clickable.kt` 里那一句),
 * 这里再来一下就是震两回;`detectTapGestures` 反过来什么都不做,所以那一侧由
 * [copyOnLongPress] 自己补。
 */
@Composable
internal fun rememberCopyAction(text: String, snackbar: SnackbarHostState): () -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.comment_copy_clip_label)
    val copiedNotice = stringResource(R.string.comment_copied)
    // 正文走 rememberUpdatedState,所以它变了不必重建这个 lambda;真正需要重建的只有换语言
    // (两条文案)和换宿主。稳定的 lambda 也让 combinedClickable 不必跟着每次重组重设。
    val currentText by rememberUpdatedState(text)
    return remember(clipboard, scope, clipLabel, copiedNotice, snackbar) {
        {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, currentText)))
                if (NeedsCopyNotice) snackbar.showSnackbar(copiedNotice)
            }
            Unit
        }
    }
}

/**
 * 长按正文复制。**给没有整行点击的那几处用**,预览层不走这条(见 [rememberCopyAction])。
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
 * - 点正文空白处:没有子节点接,长按成立,复制。
 *
 * 代价是**长按正好压在链接上不会复制**(那个子 Box 消费了 down,却没有 onLongClick)。
 * 一条评论里链接只占几个字,旁边随便哪里都能长按,所以没有为它另铺一条路。
 *
 * 读屏那一侧另走 `semantics` 的 `onLongClick`:`pointerInput` 对无障碍服务不可见,
 * 而语义动作不改变触摸行为,两条互不干涉。
 *
 * @param text 要复制的原文。传 [dev.bilby.data.CommentItem.message] 本身,不是渲染出来的样子。
 */
@Composable
internal fun Modifier.copyOnLongPress(text: String, snackbar: SnackbarHostState): Modifier {
    val haptics = LocalHapticFeedback.current
    val copy = rememberCopyAction(text, snackbar)
    val actionLabel = stringResource(R.string.comment_copy)

    // pointerInput 只在 key 变化时重启,直接捕获会把某一次组合时的 copy 焊进那个还在跑的
    // 手势协程里。key 取 Unit,靠这一层拿最新的一份。
    val currentCopy by rememberUpdatedState(copy)

    return this
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    // detectTapGestures 不带触觉反馈(combinedClickable 才自带)。没有这一下,
                    // 手指还按着的时候没有任何东西说明"已经成了",人会继续按下去。
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentCopy()
                },
            )
        }
        .semantics { onLongClick(actionLabel) { currentCopy(); true } }
}

/**
 * 要不要自己报一句「已复制」。
 *
 * **Android 13(API 33)起系统自己会弹一个复制预览浮层**,再报一条就是同一件事说两遍。
 * 官方文档把这条单列为 "Avoid duplicate notifications",同一节又要求 12L(API 32)及以下
 * 由应用自己给反馈 —— 见 `android-docs-mirror/pages/develop/ui/compose/touch-input/copy-and-paste.md`
 * 的 "Feedback to copying content"。
 *
 * **不能省成"一律不报"**:本项目 `minSdk = 29`,29–32 那一段真的在支持范围里,
 * 那些机器上不报就是长按之后什么都没发生。
 */
private val NeedsCopyNotice: Boolean
    get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
