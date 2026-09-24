package dev.bilby.ui.components

import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Spacing

/**
 * 写一段字再发出去的面板。评论区的 FAB 与单击回复都打开它。
 *
 * **它不是 `ModalBottomSheet`,是画在当前窗口里的一层:遮罩 + 贴底的面板。** sheet 那一版的
 * 表现是键盘先弹出来、隔一拍面板才从底下追上来,调"什么时候要焦点"调了两轮都对不齐。根因在
 * 结构上:sheet 是另开的一个窗口,有自己的一段升起动画,键盘升起时又去改它的 inset,两段动作
 * 各走各的。这一层没有自己的升起动画,面板贴着底、在底色里面按键盘让位,而 Compose 的 IME
 * inset 是跟着键盘动画逐帧走的 —— 面板就是被键盘托上来的,一段动作。发弹幕原先那一层
 * (`DanmakuInputLayer`)就是这个结构,当时在真机上验过。
 *
 * **遮罩铺满整个窗口,点面板以外的任何地方都是取消**:画面、标签行、弹幕胶囊都算。评论区只是
 * 播放页下半截的一块,所以主列表那一处经 [WindowOverlay] 挂到窗口最上面。楼中楼详情是一张
 * `ModalBottomSheet`,自成一个窗口,在那里回复时这一层画在那张 sheet 里面,画在外面会被它盖住;
 * 那张 sheet 本身就盖着下面的一切。
 *
 * 形状照 PiliPlus 的 `pages/video/reply_new/view.dart`:顶上一行说写给谁,中间是**没有边框的**
 * 输入区,底部一行放计数和发送。发送是这张面板上唯一的按钮,取 filled(风格指南 §2.4)。
 * 面板的圆角与底色取底部面板那一套(`BottomSheetDefaults`),它读起来就是一张从底下来的面板。
 *
 * 关掉(点遮罩、返回)不丢草稿,草稿归调用方。
 *
 * @param singleLine 回车即发送、不换行。评论是一段话,默认回车换行。
 * @param counter 底部左边的计数文字,null 即不显示。
 * @param error 这一次发送失败的原因,整句(含"发送失败")。报在发送键那一行的上方,草稿留在
 *   框里,旁边一个重试。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposerPanel(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    sending: Boolean,
    error: String?,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    counter: String? = null,
) {
    val focusRequester = remember { FocusRequester() }
    // 一出现就要焦点,键盘跟着焦点出来。这一层和页面同一个窗口,焦点到了键盘就会出来,不再另外
    // `keyboard.show()` —— 那是另开窗口(sheet)时为"只聚焦不弹键盘"加的保险。
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    // **键盘开始收起就是不写了。** 输入法开着时返回键先被它拿去收键盘,这一层根本接不到;
    // 按一下返回只收了键盘、面板还停在底部,人要再按一次。看的是键盘的动画目标,收起一开始它
    // 就是 0(`isImeVisible` 要等动画播完)。只在键盘确实弹出过之后才认:刚打开那一帧目标
    // 还是 0。
    val imeUp = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current) > 0
    var imeWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(imeUp) {
        if (imeUp) imeWasUp = true else if (imeWasUp) onDismiss()
    }
    // 键盘没开着时(硬件键盘、或者还没弹出来)返回键落到这里。
    BackHandler(onBack = onDismiss)
    val canSend = !sending && text.isNotBlank()
    // 遮罩淡入;面板本身不做进场动画,它跟着键盘上来。
    val scrimVisible = remember { MutableTransitionState(false).apply { targetState = true } }

    val bottomInset = rememberKeyboardFollowingInset()
    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visibleState = scrimVisible, enter = fadeIn()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BottomSheetDefaults.ScrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        Surface(
            color = BottomSheetDefaults.ContainerColor,
            shape = RoundedCornerShape(topStart = PanelCornerRadius, topEnd = PanelCornerRadius),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onConsumedWindowInsetsChanged { bottomInset.consumed = it },
        ) {
            // 底色一直铺到屏幕底边,键盘与导航栏在底色里面让位,见 [rememberKeyboardFollowingInset]。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomInset.value)
                    .padding(top = Spacing.Comfortable),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.Comfortable),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onTextChange,
                        singleLine = singleLine,
                        minLines = if (singleLine) 1 else MultiLineMinLines,
                        maxLines = if (singleLine) 1 else MultiLineMaxLines,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Send else ImeAction.Default),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                        // 占位和标题都只是画在旁边的 Text,输入框本身没有标签;读屏的标签
                        // 必须挂在这个节点上。
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = title },
                    )
                }
                if (error != null && !sending) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = Spacing.Comfortable, end = Spacing.Hair),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onSend) { Text(stringResource(R.string.action_retry)) }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Comfortable, end = Spacing.Comfortable, bottom = Spacing.Tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (counter != null) {
                            Text(
                                text = counter,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(onClick = onSend, enabled = canSend) {
                        if (sending) {
                            LoadingSpinner()
                        } else {
                            Text(stringResource(R.string.action_send))
                        }
                    }
                }
            }
        }
    }
}

/** 底部面板的顶角圆角,M3 bottom sheets 的规格值(28dp)。 */
private val PanelCornerRadius = 28.dp

/**
 * 多行时起步就给四行,照 PiliPlus(`minLines: 4`):一个只有一行高的框在说"写一句就好",
 * 而评论常常是一段话。长到八行就不再长,改在框里滚,面板不至于顶到屏幕上半截。
 */
private const val MultiLineMinLines = 4
private const val MultiLineMaxLines = 8

/**
 * 面板底下要让出的高度:键盘与导航栏取并集(键盘弹起时它的 inset 已经盖过导航栏,两个相加就是
 * 垫两层),减去外面已经让过的。
 *
 * **键盘升起的动画里,最多升到它上一次落定的高度。** 真机上量到过(HyperOS,2026-09-25):同一个
 * 输入场景第一次弹键盘,动画目标 913px,一路准确;第二次起动画目标报成 1012px,inset 跟着升到
 * 1011,动画结束才改报 913 —— 而键盘在屏幕上一直是同样高。面板贴着这个虚高的值走,就是"弹上去
 * 又往下退一段"。我们这边两次的状态完全一样(新的面板、新的输入框、空草稿),去掉多余的
 * `keyboard.show()` 也照旧,虚高出在输入法那一侧,这里只能绕开:记住上一次落定的真实高度
 * ([SettledKeyboardHeight],按横竖屏分开),动画途中不超过它。
 *
 * 键盘真的变高了(换输入法、切到表情面板)也不会被压住:动画结束那一刻按真实值落定,那一下
 * 不在任何插入动画里(当前值与动画目标相等),用弹簧滑过去,并更新记下的高度。键盘正在升降时
 * 不加弹簧:弹簧追不上,面板和键盘之间会漏出一条缝。
 *
 * 不用 `windowInsetsPadding`:它只能照原样跟。外面已经让过的部分由调用方在面板上挂的
 * `onConsumedWindowInsetsChanged` 报进来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun rememberKeyboardFollowingInset(): KeyboardFollowingInset {
    val density = LocalDensity.current
    val state = remember { KeyboardFollowingInset() }
    val imeNow = WindowInsets.ime
    val imeTarget = WindowInsets.imeAnimationTarget
    val nav = WindowInsets.navigationBars
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(state, orientation) {
        snapshotFlow {
            val ime = imeNow.getBottom(density)
            val keyboardAnimating = ime != imeTarget.getBottom(density)
            if (!keyboardAnimating && ime > 0) SettledKeyboardHeight[orientation] = ime
            val cap = SettledKeyboardHeight[orientation]
            val capped = if (keyboardAnimating && cap != null) minOf(ime, cap) else ime
            // 并集再扣掉外面已经让过的,和 `union(...).exclude(...)` 同义,只是键盘那一份换成了
            // 压过顶的值。
            val px = (maxOf(capped, nav.getBottom(density)) - state.consumed.getBottom(density)).coerceAtLeast(0)
            px to keyboardAnimating
        }.collectLatest { (px, keyboardAnimating) ->
            val dp = with(density) { px.toDp() }
            // 第一次直接落位:面板刚出现时底下就该是导航栏的高度,不该从 0 滑上来。
            if (keyboardAnimating || !state.placed) {
                state.animatable.snapTo(dp)
                state.placed = true
            } else {
                state.animatable.animateTo(dp, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    return state
}

/**
 * 键盘上一次落定的高度(px),按横竖屏分开。进程内共享:每次打开面板都是新的一层,记在面板
 * 身上的话第二次打开就又是空的,而虚高正是从第二次开始的。
 */
private val SettledKeyboardHeight = mutableMapOf<Int, Int>()

private class KeyboardFollowingInset {
    val animatable = Animatable(0.dp, Dp.VectorConverter)
    var placed = false
    var consumed: WindowInsets by mutableStateOf(WindowInsets(0))
    val value: Dp get() = animatable.value
}
