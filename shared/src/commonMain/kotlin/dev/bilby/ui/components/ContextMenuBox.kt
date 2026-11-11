package dev.bilby.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import dev.bilby.resources.Res
import dev.bilby.resources.action_share
import dev.bilby.resources.menu_copy_link
import dev.bilby.resources.menu_open_space
import dev.bilby.resources.video_bvid_clip_label
import dev.bilby.resources.video_bvid_copied
import dev.bilby.resources.video_bvid_copy
import dev.bilby.stringResource
import dev.bilby.ui.LocalSystemActions
import kotlinx.coroutines.launch

/**
 * 复制文本后的根层提示出口。桌面与 Android 12L 及以下没有系统级复制浮层(见 [NeedsCopyNotice]),
 * 由根部 [dev.bilby.ui.BilbyRoot] 接到全局 SnackbarHost。
 */
val LocalCopyNotice = staticCompositionLocalOf<(String) -> Unit> { {} }

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
    val lastPress = remember { PressPosition() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val openMenu = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuAt = lastPress.value
    }
    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Menu || (event.isShiftPressed && event.key == Key.F10))
                ) {
                    menuAt = Offset(boxSize.width / 2f, boxSize.height / 2f)
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        val position = event.changes.first().position
                        lastPress.value = position
                        if (event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            menuAt = position
                        }
                    }
                }
            }
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
                val isTouch = LocalPointerSource.current.isTouchLike
                val menuPopup: @Composable () -> Unit = {
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
                if (isTouch) {
                    menuPopup()
                } else {
                    MaterialExpressiveTheme(
                        colorScheme = MaterialTheme.colorScheme,
                        motionScheme = DesktopMenuMotionScheme,
                        typography = MaterialTheme.typography,
                        shapes = MaterialTheme.shapes,
                        content = menuPopup,
                    )
                }
            }
        }
    }
}

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
fun videoCommonMenuItemCount(
    bvid: String,
    upMid: Long = 0L,
    onOpenUpSpace: ((Long) -> Unit)? = null,
): Int {
    var count = 0
    if (bvid.isNotEmpty()) count += 2
    if (upMid != 0L && onOpenUpSpace != null) count += 1
    return count
}

/**
 * 视频列表项共用的右键/长按动作:复制 BV 号、复制链接(或系统分享)、打开 UP 主空间。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoCommonMenuItems(
    bvid: String,
    title: String,
    close: () -> Unit,
    upMid: Long = 0L,
    onOpenUpSpace: ((Long) -> Unit)? = null,
    startIndex: Int = 0,
    totalCount: Int = startIndex + videoCommonMenuItemCount(bvid, upMid, onOpenUpSpace),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyNotice = LocalCopyNotice.current
    val system = LocalSystemActions.current
    val clipLabel = stringResource(Res.string.video_bvid_clip_label)
    val copiedText = stringResource(Res.string.video_bvid_copied)

    var cursor = startIndex
    if (bvid.isNotEmpty()) {
        val copyBvIndex = cursor++
        DropdownMenuItem(
            onClick = {
                close()
                scope.launch {
                    clipboard.setClipEntry(plainTextClip(clipLabel, bvid))
                    copyNotice(copiedText)
                }
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
