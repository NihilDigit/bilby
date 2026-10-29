package dev.bilby.ui.comment

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.bilby.data.EmoteAction
import dev.bilby.data.LocalEmoteRepository
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.components.ConfigureInputDialogWindow
import dev.bilby.ui.components.EmotePickerPanel
import dev.bilby.ui.components.EmoteToggleButton
import dev.bilby.ui.components.PillInputField
import dev.bilby.ui.components.SettledKeyboardHeightTracker
import dev.bilby.ui.components.fullScreenDialogProperties
import dev.bilby.ui.components.insertAtCursor
import dev.bilby.ui.components.rememberReplyEmoteState
import dev.bilby.ui.imeTarget
import dev.bilby.ui.screenOrientationKey
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 写评论时的字数计数,**过了 [CounterFrom] 才出现**,而且不拦输入。
 *
 * 评论这一侧 PiliPlus 没有任何客户端长度限制(`pages/video/reply_new/view.dart` 里没有 `maxLength`),
 * 1000 是站内 web 版编辑器的参考刻度,真正的判决交给服务端 —— 被拒之后草稿留在框里,原因在输入框上方。
 */
@Composable
internal fun commentDraftCounter(length: Int): String? {
    if (length < CounterFrom) return null
    return stringResource(Res.string.input_length_counter, length, SoftLimit)
}

private const val SoftLimit = 1000
private const val CounterFrom = 800

/**
 * 浏览评论时固定在列表底部的轻量触发条。
 *
 * **不订阅 `WindowInsets.ime`。** 真正打字在独立窗口的 [CommentComposerDialog] 里进行,
 * 软键盘弹出与退避的整个过程中列表高度恒定不变,既不触发几百条富文本评论逐帧重新布局,
 * 也不会在输入法收起后留下未刷新的空白区。
 */
@Composable
internal fun CommentPromptBar(
    draftPreview: String,
    placeholder: String,
    onOpenKeyboard: () -> Unit,
    onOpenEmoji: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasDraft = draftPreview.isNotBlank()
    val hasEmoteRepo = LocalEmoteRepository.current != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight)
            .heightIn(min = PromptMinHeight)
            .clip(MaterialTheme.shapes.largeIncreased)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.largeIncreased)
            .clickable(role = Role.Button, onClick = onOpenKeyboard)
            .padding(
                start = Spacing.Comfortable,
                end = if (hasEmoteRepo) Spacing.Hair else Spacing.Comfortable,
                top = Spacing.Hair,
                bottom = Spacing.Hair,
            ),
    ) {
        Text(
            text = if (hasDraft) draftPreview.replace('\n', ' ') else placeholder,
            style = MaterialTheme.typography.bodyLarge,
            color = if (hasDraft) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasEmoteRepo) {
            IconButton(onClick = onOpenEmoji) {
                Icon(
                    imageVector = Icons.Outlined.SentimentSatisfied,
                    contentDescription = stringResource(Res.string.emote_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 独立模态评论输入浮层。
 *
 * 自成一个全屏对话框窗口([ConfigureInputDialogWindow]):
 * - 点击上方任意遮罩空白、向下滑动输入面板、按系统返回键或收起软键盘均可直接退出输入态;
 * - 软键盘与表情面板切换时复用落定过的键盘高度托底,避免面板高度跳变闪烁;
 * - 退出时整个浮层直接移出组合,草稿由调用方按回复对象持久化保留。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentComposerDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    /** 顶栏显示的当前对象说明(例如「回复 @某某」或「发一条评论」)。 */
    headerTitle: String,
    placeholder: String,
    sending: Boolean,
    error: String?,
    initialOpenEmoji: Boolean,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = fullScreenDialogProperties(),
    ) {
        ConfigureInputDialogWindow()
        val density = LocalDensity.current
        val keyboard = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }

        var fieldValue by remember {
            mutableStateOf(TextFieldValue(text = draft, selection = TextRange(draft.length)))
        }
        if (fieldValue.text != draft) {
            fieldValue = TextFieldValue(
                text = draft,
                selection = TextRange(fieldValue.selection.start.coerceAtMost(draft.length)),
            )
        }
        val updateField: (TextFieldValue) -> Unit = { next ->
            val textChanged = next.text != fieldValue.text
            fieldValue = next
            if (textChanged) onDraftChange(next.text)
        }

        var emojiOpen by remember { mutableStateOf(initialOpenEmoji) }
        var holdingForKeyboard by remember { mutableStateOf(false) }
        val (emoteState, retryEmotes) = rememberReplyEmoteState(active = emojiOpen)

        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val imeTargetBottomPx = WindowInsets.imeTarget.getBottom(density)
        val navBottomDp = with(density) { navBottomPx.toDp() }
        val netImeDp = with(density) { (imeBottomPx - navBottomPx).coerceAtLeast(0).toDp() }
        val netTargetImeDp = with(density) { (imeTargetBottomPx - navBottomPx).coerceAtLeast(0).toDp() }

        val orientation = screenOrientationKey()
        val heightTracker = remember(orientation) { SettledKeyboardHeightTracker(orientationKey = orientation) }
        val settledNetImeDp = heightTracker.onFrame(netImeDp.value, netTargetImeDp.value).dp

        var keyboardEverShown by remember { mutableStateOf(false) }
        if (imeTargetBottomPx > navBottomPx || netImeDp > MinKeyboardRecognizeHeight) {
            keyboardEverShown = true
        }

        val close: () -> Unit = {
            keyboard?.hide()
            onDismiss()
        }

        BackHandler(onBack = close)

        LaunchedEffect(Unit) {
            if (!initialOpenEmoji) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }

        // 切回键盘后留短暂过渡窗口防塌缩;若当前设备无软键盘(外接硬件键盘或触控桌面),超时后自然落定。
        LaunchedEffect(holdingForKeyboard) {
            if (!holdingForKeyboard) return@LaunchedEffect
            delay(KeyboardResumeGraceMillis)
            holdingForKeyboard = false
        }

        // 软键盘开始升起:接过过渡托底,若表情面板还开着也一并收掉。
        LaunchedEffect(imeTargetBottomPx, navBottomPx) {
            if (imeTargetBottomPx > navBottomPx) {
                holdingForKeyboard = false
                if (emojiOpen) emojiOpen = false
            }
        }

        // 软键盘曾真正弹起过,之后被用户收起(且非切去表情面板、也非正在等待键盘升起)时,一并关闭浮层。
        LaunchedEffect(keyboardEverShown, emojiOpen, holdingForKeyboard, imeTargetBottomPx, imeBottomPx) {
            if (keyboardEverShown && !emojiOpen && !holdingForKeyboard && imeTargetBottomPx == 0 && imeBottomPx == 0) {
                onDismiss()
            }
        }

        var dragDownPx by remember { mutableFloatStateOf(0f) }
        val dismissDragThresholdPx = with(density) { DismissSwipeThreshold.toPx() }

        Box(modifier = Modifier.fillMaxSize()) {
            // 上方整片半透明遮罩:点击任意位置直接关闭输入浮层。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FixedColors.ScrimOnMedia)
                    .pointerInput(Unit) {
                        detectTapGestures { close() }
                    },
            )

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = ComposerSheetShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = Breakpoints.ReadableWidth)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.barsAndCutout.only(WindowInsetsSides.Horizontal))
                    .pointerInput(dismissDragThresholdPx) {
                        detectVerticalDragGestures(
                            onDragStart = { dragDownPx = 0f },
                            onDragEnd = { dragDownPx = 0f },
                            onDragCancel = { dragDownPx = 0f },
                        ) { change, dragAmount ->
                            dragDownPx = (dragDownPx + dragAmount).coerceAtLeast(0f)
                            if (dragDownPx >= dismissDragThresholdPx) {
                                change.consume()
                                dragDownPx = 0f
                                close()
                            }
                        }
                    },
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = Spacing.Cozy,
                                end = Spacing.Cozy,
                                top = Spacing.Tight,
                                bottom = Spacing.Tight,
                            ),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(start = Spacing.Tight),
                        ) {
                            Text(
                                text = headerTitle,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = close) {
                                Icon(
                                    imageVector = Icons.Outlined.KeyboardArrowDown,
                                    contentDescription = stringResource(Res.string.action_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (error != null && !sending) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = Spacing.Tight),
                            ) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onSend) {
                                    Text(stringResource(Res.string.action_retry))
                                }
                            }
                        }
                        PillInputField(
                            value = fieldValue,
                            onValueChange = updateField,
                            placeholder = placeholder,
                            canSend = !sending && fieldValue.text.isNotBlank(),
                            sending = sending,
                            onSend = onSend,
                            maxLines = MaxLines,
                            imeSend = false,
                            fieldModifier = Modifier
                                .focusRequester(focusRequester)
                                .semantics { contentDescription = headerTitle },
                            trailing = {
                                EmoteToggleButton(
                                    open = emojiOpen,
                                    onToggle = {
                                        if (emojiOpen) {
                                            emojiOpen = false
                                            holdingForKeyboard = true
                                            focusRequester.requestFocus()
                                            keyboard?.show()
                                        } else {
                                            keyboardEverShown = false
                                            holdingForKeyboard = false
                                            emojiOpen = true
                                            keyboard?.hide()
                                        }
                                    },
                                )
                            },
                        )
                        commentDraftCounter(fieldValue.text.length)?.let { counter ->
                            Text(
                                text = counter,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth().padding(end = Spacing.Comfortable),
                            )
                        }
                    }

                    // 底部支撑区:净键盘高度与表情面板内容区 1:1 等高,小横条高度传入 bottomInset 做内容避让。
                    if (emojiOpen) {
                        val contentHeight = maxOf(netImeDp, settledNetImeDp)
                        EmotePickerPanel(
                            state = emoteState,
                            onRetry = retryEmotes,
                            onSelect = { action ->
                                if (action is EmoteAction.InsertText) {
                                    updateField(fieldValue.insertAtCursor(action.text))
                                }
                            },
                            height = contentHeight,
                            bottomInset = navBottomDp,
                        )
                    } else {
                        val reserveNet = if (holdingForKeyboard) maxOf(netImeDp, settledNetImeDp) else netImeDp
                        Box(modifier = Modifier.fillMaxWidth().height(reserveNet + navBottomDp))
                    }
                }
            }
        }
    }
}

private const val MaxLines = 6
private const val KeyboardResumeGraceMillis = 180L
private val PromptMinHeight = 52.dp
private val ComposerSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val MinKeyboardRecognizeHeight = 120.dp
private val DismissSwipeThreshold = 40.dp
