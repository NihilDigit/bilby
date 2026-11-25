package dev.bilby.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import dev.bilby.data.ContentBlockMatcher
import dev.bilby.player.QueueItem
import dev.bilby.resources.Res
import dev.bilby.resources.action_copy_title
import dev.bilby.resources.action_share
import dev.bilby.resources.comment_copied
import dev.bilby.resources.menu_block_up
import dev.bilby.resources.menu_copy_link
import dev.bilby.resources.menu_open_space
import dev.bilby.resources.queue_add
import dev.bilby.resources.queue_play_next
import dev.bilby.resources.video_bvid_clip_label
import dev.bilby.resources.video_bvid_copied
import dev.bilby.resources.video_bvid_copy
import dev.bilby.stringResource
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
import kotlinx.coroutines.launch

/**
 * 复制文本后的根层提示出口。桌面与 Android 12L 及以下没有系统级复制浮层(见 [NeedsCopyNotice]),
 * 由根部 [dev.bilby.ui.BilbyRoot] 接到全局 SnackbarHost。
 */
val LocalCopyNotice = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * 列表视频项菜单入队动作(「下一条播放」「加入播放队列」)，由根部 [dev.bilby.ui.BilbyApp] 提供。
 */
data class QueueMenuActions(
    val enqueue: (item: QueueItem, playNext: Boolean) -> Unit,
)

val LocalQueueActions = staticCompositionLocalOf<QueueMenuActions?> { null }

/** 视频列表项右键/长按本地屏蔽 UP 主动作，由根部 [dev.bilby.ui.BilbyApp] 提供。 */
data class VideoBlockActions(
    val blockUp: (upMid: Long, upName: String) -> Unit,
)

val LocalVideoBlockActions = staticCompositionLocalOf<VideoBlockActions?> { null }

/** 本地内容屏蔽匹配器，供搜索结果等列表实时过滤。 */
val LocalContentBlockMatcher = staticCompositionLocalOf { ContentBlockMatcher.EMPTY }

/**
 * 桌面右键菜单的动效方案:去掉移动端 `DropdownMenu` 默认的 `0.8 -> 1.0` 缩放弹簧,保留极短的
 * 80ms 淡入淡出,让指针所指之处即点即出。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object DesktopMenuMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
}

/**
 * 在非触摸输入下为 `DropdownMenu` 套用 [DesktopMenuMotionScheme]（去掉 0.8 -> 1.0 缩放弹簧，只保留 80ms 淡入淡出）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DesktopMenuMotionTheme(content: @Composable () -> Unit) {
    if (LocalPointerSource.current.isTouchLike) {
        content()
    } else {
        MaterialExpressiveTheme(
            colorScheme = MaterialTheme.colorScheme,
            motionScheme = DesktopMenuMotionScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/**
 * 右键或长按在按下的位置弹出 [menu]。给宽屏网格里的行用,替掉行尾那颗 ⋮:每格一颗时整屏是
 * 一列列竖排的点,而宽屏上右键本来就是弹菜单的通用手势,不需要一个看得见的入口去提示它。
 * 窄屏仍画 ⋮,那里没有鼠标。
 *
 * 位置在 Initial 阶段记下,不消费主键:点击还要收到这一次按下,长按回调本身又不带坐标。
 * 右键在同一阶段消费掉,免得点击把它当成一次点击。键盘焦点落在该项时,Menu 键或 Shift+F10
 * 在中心弹出同一份菜单。
 *
 * @param onClick 由这一层接管点击与长按。null 时这一层不接点击,[content] 拿到 openMenu 挂到
 *   自己的长按上:视频行自带 combinedClickable,外面再套一层的话长按先被里面那层吃掉。
 * @param menuLabel 读屏念的长按动作名。无障碍服务里长按是一个具名动作,菜单靠它才找得到。
 *   只在这一层接管点击时生效。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextMenuBox(
    onClick: (() -> Unit)?,
    menuLabel: String,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val latestHaptics by rememberUpdatedState(haptics)
    val lastPress = remember { PressPosition() }
    val boxSize = remember { BoxSizeHolder() }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val openMenu = remember {
        {
            latestHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
            menuAt = lastPress.value
        }
    }
    val onSecondaryPress = remember {
        { position: Offset ->
            menuAt = position
        }
    }
    Box(
        modifier = modifier
            .onSizeChanged { boxSize.value = it }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10))
                ) {
                    menuAt = Offset(boxSize.value.width / 2f, boxSize.value.height / 2f)
                    true
                } else {
                    false
                }
            }
            // 使用无协程的 PointerInputModifierNode 替代 suspending pointerInput:
            // 列表滚动时鼠标掠过卡片不会起协程,滑出视口卸载节点时也不会产生 PointerInputResetException。
            .then(ContextMenuPressElement(lastPress, onSecondaryPress))
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = onClick,
                        onLongClickLabel = menuLabel,
                        onLongClick = openMenu,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        content(openMenu)
        menuAt?.let { at ->
            // 零尺寸的锚点放在按下的位置,DropdownMenu 贴着它展开。外形同 ⋮ 弹出的那份
            // (M3E vertical menu),两个入口弹的是同一个菜单。
            Box(modifier = Modifier.offset { at.round() }) {
                DesktopMenuMotionTheme {
                    val focusManager = LocalFocusManager.current
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { menuAt = null },
                        shape = MenuDefaults.shape,
                        containerColor = MenuDefaults.containerColor,
                        modifier = Modifier.onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    focusManager.moveFocus(FocusDirection.Next)
                                    true
                                }

                                Key.DirectionUp -> {
                                    focusManager.moveFocus(FocusDirection.Previous)
                                    true
                                }

                                else -> false
                            }
                        },
                    ) {
                        menu { menuAt = null }
                    }
                }
            }
        }
    }
}

/**
 * 复制纯文本并在需要应用自报回执的平台(桌面、Android 12L 及以下)弹出全局提示。
 */
@Composable
fun rememberCopyAction(
    clipLabel: String = "Bilby",
    copiedMessage: String = stringResource(Res.string.comment_copied),
): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyNotice = LocalCopyNotice.current
    val latestLabel by rememberUpdatedState(clipLabel)
    val latestMessage by rememberUpdatedState(copiedMessage)
    val latestNotice by rememberUpdatedState(copyNotice)
    return remember(clipboard, scope) {
        { value: String ->
            if (value.isNotEmpty()) {
                scope.launch {
                    clipboard.setClipEntry(plainTextClip(latestLabel, value))
                    if (NeedsCopyNotice) latestNotice(latestMessage)
                }
            }
        }
    }
}

/**
 * 单击即复制的紧凑标识胶囊(BV 号、AV 号、`UID: …`),自带独立点击消费,嵌在可折叠标题块或头部行内
 * 不会把点按冒泡给父容器。
 */
@Composable
fun CopyableIdPill(
    text: String,
    copyValue: String = text,
    modifier: Modifier = Modifier,
) {
    val copy = rememberCopyAction()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(role = Role.Button, onClick = { copy(copyValue) }),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.tabular,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = Spacing.Hair, vertical = CopyablePillVerticalPadding),
        )
    }
}

private val CopyablePillVerticalPadding = 2.dp

/**
 * M3E 纵向菜单项按位置取形状:单条取 standalone,首尾各取 leading / trailing,中间取 middle,
 * 不画任何分割线。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun menuItemShape(index: Int, total: Int): Shape = when {
    total <= 1 -> MenuDefaults.standaloneItemShape
    index == 0 -> MenuDefaults.leadingItemShape
    index == total - 1 -> MenuDefaults.trailingItemShape
    else -> MenuDefaults.middleItemShape
}

/** [VideoCommonMenuItems] 会画出的项数,供外层合并前后自定义菜单项时计算总项数。 */
@Composable
fun videoCommonMenuItemCount(
    bvid: String,
    upMid: Long = 0L,
    onOpenUpSpace: ((Long) -> Unit)? = null,
    includeQueueActions: Boolean = true,
    title: String? = null,
): Int {
    var count = 0
    if (bvid.isNotEmpty()) {
        if (includeQueueActions && LocalQueueActions.current != null) count += 2
        if (title == null || title.isNotBlank()) count += 1
        count += 2
        if (upMid != 0L && LocalVideoBlockActions.current != null) count += 1
    }
    if (upMid != 0L && onOpenUpSpace != null) count += 1
    return count
}

/**
 * 视频列表项共用的右键/长按动作:入队操作、复制标题、复制 BV 号、复制链接(或系统分享)、打开 UP 主空间。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoCommonMenuItems(
    bvid: String,
    title: String = "",
    close: () -> Unit,
    upMid: Long = 0L,
    onOpenUpSpace: ((Long) -> Unit)? = null,
    coverUrl: String = "",
    upName: String = "",
    durationSeconds: Long = 0L,
    includeQueueActions: Boolean = true,
    startIndex: Int = 0,
    totalCount: Int = startIndex + videoCommonMenuItemCount(bvid, upMid, onOpenUpSpace, includeQueueActions, title),
) {
    val system = LocalSystemActions.current
    val queueActions = LocalQueueActions.current.takeIf { includeQueueActions }
    val blockActions = LocalVideoBlockActions.current
    val copyTitle = rememberCopyAction()
    val copyBvid = rememberCopyAction(
        clipLabel = stringResource(Res.string.video_bvid_clip_label),
        copiedMessage = stringResource(Res.string.video_bvid_copied),
    )

    var cursor = startIndex
    if (bvid.isNotEmpty()) {
        if (queueActions != null) {
            val queueItem = QueueItem(
                bvid = bvid,
                title = title,
                upName = upName,
                coverUrl = coverUrl,
                durationSeconds = durationSeconds,
            )
            val playNextIndex = cursor++
            DropdownMenuItem(
                onClick = {
                    close()
                    queueActions.enqueue(queueItem, true)
                },
                text = { Text(stringResource(Res.string.queue_play_next)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null) },
                shape = menuItemShape(playNextIndex, totalCount),
            )
            val addQueueIndex = cursor++
            DropdownMenuItem(
                onClick = {
                    close()
                    queueActions.enqueue(queueItem, false)
                },
                text = { Text(stringResource(Res.string.queue_add)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null) },
                shape = menuItemShape(addQueueIndex, totalCount),
            )
        }
        if (title.isNotBlank()) {
            val copyTitleIndex = cursor++
            DropdownMenuItem(
                onClick = {
                    close()
                    copyTitle(title)
                },
                text = { Text(stringResource(Res.string.action_copy_title)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(copyTitleIndex, totalCount),
            )
        }
        val copyBvIndex = cursor++
        DropdownMenuItem(
            onClick = {
                close()
                copyBvid(bvid)
            },
            text = { Text(stringResource(Res.string.video_bvid_copy)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            shape = menuItemShape(copyBvIndex, totalCount),
        )
        val shareIndex = cursor++
        val copiesLink = system.shareCopiesLink
        DropdownMenuItem(
            onClick = {
                close()
                system.shareVideo(bvid, title)
            },
            text = { Text(stringResource(if (copiesLink) Res.string.menu_copy_link else Res.string.action_share)) },
            leadingIcon = {
                Icon(
                    if (copiesLink) Icons.Outlined.Link else Icons.Outlined.Share,
                    contentDescription = null,
                )
            },
            shape = menuItemShape(shareIndex, totalCount),
        )
        if (upMid != 0L && blockActions != null) {
            val blockUpIndex = cursor++
            DropdownMenuItem(
                onClick = {
                    close()
                    blockActions.blockUp(upMid, upName)
                },
                text = { Text(stringResource(Res.string.menu_block_up)) },
                leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null) },
                shape = menuItemShape(blockUpIndex, totalCount),
            )
        }
    }
    if (upMid != 0L && onOpenUpSpace != null) {
        val spaceIndex = cursor
        DropdownMenuItem(
            onClick = {
                close()
                onOpenUpSpace(upMid)
            },
            text = { Text(stringResource(Res.string.menu_open_space)) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            shape = menuItemShape(spaceIndex, totalCount),
        )
    }
}

/** 只给手势回调读写,不参与组合,所以不用 State。 */
private class PressPosition {
    var value: Offset = Offset.Zero
}

/** 只给键盘菜单快捷键读尺寸,不参与组合,避免首帧 onSizeChanged 触发列表项重组。 */
private class BoxSizeHolder {
    var value: IntSize = IntSize.Zero
}

private data class ContextMenuPressElement(
    val lastPress: PressPosition,
    val onSecondaryPress: (Offset) -> Unit,
) : ModifierNodeElement<ContextMenuPressNode>() {
    override fun create(): ContextMenuPressNode =
        ContextMenuPressNode(lastPress, onSecondaryPress)

    override fun update(node: ContextMenuPressNode) {
        node.lastPress = lastPress
        node.onSecondaryPress = onSecondaryPress
    }
}

private class ContextMenuPressNode(
    var lastPress: PressPosition,
    var onSecondaryPress: (Offset) -> Unit,
) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (pass != PointerEventPass.Initial || pointerEvent.type != PointerEventType.Press) return
        val position = pointerEvent.changes.firstOrNull()?.position ?: return
        lastPress.value = position
        if (pointerEvent.buttons.isSecondaryPressed) {
            pointerEvent.changes.forEach { it.consume() }
            onSecondaryPress(position)
        }
    }

    override fun onCancelPointerInput() = Unit
}
