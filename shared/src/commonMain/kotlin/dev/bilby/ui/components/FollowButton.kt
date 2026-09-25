package dev.bilby.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.bilby.data.FollowState
import dev.bilby.resources.*
import dev.bilby.stringResource

/**
 * 关注按钮。**播放页和空间页共用这一份** —— 两处原先各写了一份一模一样的实现,连取关确认
 * 框都是逐字重复的;判据一旦要改(比如互关的字面),只改一处就会让两页说法不一致。
 *
 * 三种可关注状态各有各的字面:互关不能显示成"已关注",那会把"对方也关注了你"这条信息抹掉,
 * 而这正是 B 站用户会去看的东西。自己的空间和已拉黑都不显示按钮 —— 前者没有这个动作,
 * 后者要先解除拉黑。
 *
 * **取关要二次确认,关注不用**:关注是可逆的轻动作,取关会丢掉这条关系(重新关注要再找到
 * 这个人)。确认放在发起请求之前 —— `onClick` 是乐观更新 + 发请求的入口,按钮状态不能先跳
 * 过去再跳回来。
 *
 * @param prominent 这一页的主角是不是"这个人"。空间页是(整页都在讲他,关注是这页最主要的
 *   动作,用 filled);播放页不是(主角是这条视频,关注只是顺手做的一件事,用 tonal)。
 *   同一个动作在两页给不同的强调,依据是 M3 的强调层级对应动作主次,而不是按钮长得好不好看。
 *   已关注一律 outlined:关系已经建立之后,"取关"更不该抢眼。
 *
 *   不突出时换成 [CompactFollowButton]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FollowButton(
    state: FollowState,
    onClick: () -> Unit,
    prominent: Boolean = true,
    /**
     * 要取关的是谁。**给了就写进确认框的标题**——一屏上可能同时有几个关注按钮(关注列表页),
     * 一个只写「取消关注」的框说不出按下去会取关哪一个。拿不到名字时退回不带名字的标题,
     * 而不是留一处空白。
     */
    name: String = "",
) {
    var confirmingUnfollow by remember { mutableStateOf(false) }
    if (!prominent) {
        CompactFollowButton(
            state = state,
            onFollow = onClick,
            onUnfollow = { confirmingUnfollow = true },
        )
    } else when (state) {
        FollowState.Self, FollowState.Blocked -> Unit
        FollowState.None -> {
            val text = stringResource(Res.string.follow_none)
            Button(onClick = onClick) { Text(text) }
        }

        FollowState.Following -> OutlinedButton(onClick = { confirmingUnfollow = true }) {
            Text(stringResource(Res.string.follow_following))
        }

        FollowState.Mutual -> OutlinedButton(onClick = { confirmingUnfollow = true }) {
            Text(stringResource(Res.string.follow_mutual))
        }
    }
    if (confirmingUnfollow) {
        UnfollowConfirmDialog(
            name = name,
            onConfirm = onClick,
            onDismiss = { confirmingUnfollow = false },
        )
    }
}

/**
 * 取关确认。[FollowButton] 与空间页的分体关注按钮共用,两处的字面必须一样。
 *
 * @param onConfirm 确认取关。框由这里先关掉,调用方不必再关一次。
 */
@Composable
fun UnfollowConfirmDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // 不写说明。**标题已经说完了这件事**,再补一句"取消后要重新找到这个人"是在教用户
        // 关注是怎么回事。PiliPlus 的取关面板同样只有选项没有说明
        // (request_utils.dart 的 relationMod 那一段)。
        title = {
            Text(
                if (name.isBlank()) {
                    stringResource(Res.string.follow_unfollow_confirm_title)
                } else {
                    stringResource(Res.string.follow_unfollow_confirm_named, name)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(Res.string.follow_unfollow_confirm_title)) }
        },
        // 「保留」而不是「取消」。两个按钮上都带着「取消」两个字的时候,读者得先分清哪个
        // 取消的是关注、哪个取消的是这个框 —— 而这是个不可逆动作的最后一道闸。
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.follow_unfollow_keep))
            }
        },
    )
}

/**
 * 播放页 UP 行上的关注按钮:M3 Expressive 的 XS 尺寸(容器 32dp,触控区仍由最小交互尺寸撑到
 * 48dp),图标加文字,两态都是实底。
 *
 * 播放页那一行左边是 40dp 的头像和两行字,原先那个 48dp 高、已关注时还带描边的胶囊把整行的
 * 重心拽到右边。实底不描边,和下面动作栏的格子是同一种质感。已关注换成更淡的一档底色:
 * 关系建立之后,这个按钮能做的只剩取关,不该比关注时更显眼。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompactFollowButton(state: FollowState, onFollow: () -> Unit, onUnfollow: () -> Unit) {
    val height = ButtonDefaults.ExtraSmallContainerHeight
    val following = state == FollowState.Following || state == FollowState.Mutual
    val text = when (state) {
        FollowState.Mutual -> stringResource(Res.string.follow_mutual)
        FollowState.Following -> stringResource(Res.string.follow_following)
        else -> stringResource(Res.string.follow_none)
    }
    if (state == FollowState.Self || state == FollowState.Blocked) return
    FilledTonalButton(
        onClick = if (following) onUnfollow else onFollow,
        modifier = Modifier.heightIn(min = height),
        contentPadding = ButtonDefaults.contentPaddingFor(height, hasStartIcon = true),
        colors = if (following) {
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Icon(
            imageVector = if (following) Icons.Filled.Check else Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(height)),
        )
        Spacer(modifier = Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
        Text(text, style = ButtonDefaults.textStyleFor(height))
    }
}
