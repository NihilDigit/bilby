package dev.bilby.ui.comment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.ConfigureInputDialogWindow
import dev.bilby.ui.components.EmotePickerPanel
import dev.bilby.ui.components.EmoteToggleButton
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PillInputField
import dev.bilby.ui.components.SettledKeyboardHeightTracker
import dev.bilby.ui.components.fullScreenDialogProperties
import dev.bilby.ui.components.insertAtCursor
import dev.bilby.ui.components.rememberReplyEmoteState
import dev.bilby.ui.components.trackEditableFocus
import dev.bilby.ui.imeTarget
import dev.bilby.ui.screenOrientationKey
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular
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
 * 浏览评论时固定在列表底部的轻量胶囊触发条。
 *
 * **不订阅 `WindowInsets.ime`。** 真正打字在独立窗口的 [CommentComposerDialog] 里进行,
 * 软键盘弹出与退避的整个过程中列表高度恒定不变,既不触发几百条富文本评论逐帧重新布局,
 * 也不会在输入法收起后留下未刷新的空白区。
 *
 * @param inSheetFooter 嵌在 [dev.bilby.ui.components.PaneSheet] 的 `footer` 槽位里时传 `true`,
 *   避免与 `SheetFooterSlot` 自带的底部安全区和外层边距重复叠加。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CommentPromptBar(
    draftPreview: String,
    placeholder: String,
    onOpenKeyboard: () -> Unit,
    onOpenEmoji: () -> Unit,
    modifier: Modifier = Modifier,
    inSheetFooter: Boolean = false,
) {
    val hasDraft = draftPreview.isNotBlank()
    val hasEmoteRepo = LocalEmoteRepository.current != null
    val outerModifier = if (inSheetFooter) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = outerModifier
            .heightIn(min = PromptMinHeight)
            .interactiveCard(
                shape = CircleShape,
                baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                enableScale = false,
            )
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
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

            val sheetShape = MaterialTheme.shapes.extraLarge.copy(
                bottomStart = CornerSize(0.dp),
                bottomEnd = CornerSize(0.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = sheetShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = ComposerMaxWidth)
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
                                style = MaterialTheme.typography.labelLargeEmphasized,
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
                                style = MaterialTheme.typography.labelSmall.tabular,
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

/**
 * 桌面窗口或非触屏指针模式下一律采用就地停靠的评论编辑卡片([InlineCommentComposer]),
 * 避免全窗口模态输入浮层给左侧视频画面蒙黑遮罩;Android 触屏软键盘输入则继续走 [CommentComposerDialog]。
 *
 * 编辑态打开期间锁定分支,避免二合一设备先用鼠标点开输入框、再用手指点发送或表情时中途切换成模态弹窗。
 */
@Composable
internal fun rememberUseInlineCommentComposer(composerOpen: Boolean): Boolean {
    var hadWindowCaption by remember { mutableStateOf(false) }
    if (LocalWindowCaption.current != null) hadWindowCaption = true
    val useInline = LocalWindowCaption.current != null || hadWindowCaption || !LocalPointerSource.current.isTouchLike
    var latched by remember { mutableStateOf(useInline) }
    if (!composerOpen) {
        latched = useInline
    }
    return if (composerOpen) latched else useInline
}

/**
 * 桌面与键鼠模式下的内嵌停靠评论编辑卡片:直接替代列底部的 [CommentPromptBar] 原地展开,
 * 无全窗口对话框、无跨栏遮罩。
 *
 * - 打开或切换回复对象时自动请求焦点;`Esc` 收起卡片,`Ctrl+Enter` / `Meta+Enter` 直接发送;
 * - 回复具体对象时顶部显示紧凑「回复 @…」条与取消回复按钮;
 * - 表情开关开启时在卡片内部平滑展开 `200.dp` 高的 [EmotePickerPanel]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun InlineCommentComposer(
    targetKey: Any,
    draft: String,
    onDraftChange: (String) -> Unit,
    replyLabel: String?,
    onClearReply: (() -> Unit)?,
    placeholder: String,
    sending: Boolean,
    error: String?,
    initialOpenEmoji: Boolean,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequestKey: Int = 0,
    inSheetFooter: Boolean = false,
) {
    BackHandler(onBack = onDismiss)

    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hasEmoteRepo = LocalEmoteRepository.current != null

    var fieldValue by remember(targetKey) {
        mutableStateOf(TextFieldValue(text = draft, selection = TextRange(draft.length)))
    }
    if (fieldValue.text != draft) {
        fieldValue = TextFieldValue(
            text = draft,
            selection = TextRange(draft.length),
        )
    }
    val updateField: (TextFieldValue) -> Unit = { next ->
        val textChanged = next.text != fieldValue.text
        fieldValue = next
        if (textChanged) onDraftChange(next.text)
    }

    var emojiOpen by remember { mutableStateOf(initialOpenEmoji) }
    LaunchedEffect(initialOpenEmoji) {
        if (initialOpenEmoji) emojiOpen = true
    }
    val (emoteState, retryEmotes) = rememberReplyEmoteState(active = emojiOpen)
    val canSend = !sending && fieldValue.text.isNotBlank()

    LaunchedEffect(targetKey, focusRequestKey) {
        focusRequester.requestFocus()
    }

    val outerModifier = if (inSheetFooter) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight)
    }

    val borderColor by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.primary.copy(alpha = InlineFocusedBorderAlpha)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = InlineUnfocusedBorderAlpha)
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "inlineComposerBorder",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.largeIncreased,
        border = BorderStroke(InlineComposerBorderWidth, borderColor),
        modifier = outerModifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val bare = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed
            if (bare && event.key == Key.Escape) {
                onDismiss()
                return@onPreviewKeyEvent true
            }
            val submitChord = (event.isCtrlPressed || event.isMetaPressed) &&
                !event.isAltPressed &&
                !event.isShiftPressed
            if (submitChord && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                if (canSend) onSend()
                return@onPreviewKeyEvent true
            }
            false
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            if (replyLabel != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = replyLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onClearReply != null) {
                        val clearLabel = stringResource(Res.string.comment_reply_cancel)
                        BilbyTooltip(text = clearLabel, anchorAbove = true) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(ReplyClearButtonSize)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClick = onClearReply),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = clearLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(ReplyClearIconSize),
                                )
                            }
                        }
                    }
                }
            }

            Box(
                contentAlignment = Alignment.TopStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = InlineMinLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = updateField,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = InlineMinLines,
                    maxLines = MaxLines,
                    interactionSource = interactionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .trackEditableFocus()
                        .semantics { contentDescription = replyLabel ?: placeholder },
                )
            }

            if (error != null && !sending) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (hasEmoteRepo) {
                    val emoteLabel = stringResource(Res.string.emote_open)
                    BilbyTooltip(text = emoteLabel, anchorAbove = true) {
                        IconButton(
                            onClick = { emojiOpen = !emojiOpen },
                            modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SentimentSatisfied,
                                contentDescription = emoteLabel,
                                tint = if (emojiOpen) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                        }
                    }
                }
                commentDraftCounter(fieldValue.text.length)?.let { counter ->
                    Text(
                        text = counter,
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
                    modifier = Modifier.heightIn(min = InlineActionButtonHeight),
                ) {
                    Text(
                        text = stringResource(Res.string.action_cancel),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                val sendLabel = stringResource(Res.string.action_send)
                BilbyTooltip(text = sendLabel, shortcut = "Ctrl+Enter", anchorAbove = true) {
                    Button(
                        onClick = onSend,
                        enabled = canSend,
                        contentPadding = PaddingValues(horizontal = Spacing.Comfortable, vertical = Spacing.Hair),
                        modifier = Modifier.heightIn(min = InlineActionButtonHeight),
                    ) {
                        if (sending) {
                            LoadingSpinner(size = Dimens.IconInline)
                        } else {
                            Text(
                                text = sendLabel,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = emojiOpen && hasEmoteRepo,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
            ) {
                EmotePickerPanel(
                    state = emoteState,
                    onRetry = retryEmotes,
                    onSelect = { action ->
                        if (action is EmoteAction.InsertText) {
                            updateField(fieldValue.insertAtCursor(action.text))
                            focusRequester.requestFocus()
                        }
                    },
                    height = InlineEmotePanelHeight,
                    modifier = Modifier.clip(MaterialTheme.shapes.medium),
                )
            }
        }
    }
}

private const val InlineMinLines = 2
private const val MaxLines = 6
private const val KeyboardResumeGraceMillis = 180L
private const val InlineFocusedBorderAlpha = 0.6f
private const val InlineUnfocusedBorderAlpha = 0.4f
private val InlineComposerBorderWidth = 1.dp
private val ReplyClearButtonSize = 24.dp
private val ReplyClearIconSize = 16.dp
private val InlineActionButtonHeight = 36.dp
private val InlineEmotePanelHeight = 200.dp
private val PromptMinHeight = 52.dp
/** M3 `bottom-sheets.md` 宽屏输入面板的最大宽度。 */
private val ComposerMaxWidth = 640.dp
private val MinKeyboardRecognizeHeight = 120.dp
private val DismissSwipeThreshold = 40.dp
