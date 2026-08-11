package dev.bilby.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import dev.bilby.data.FollowState

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
 */
@Composable
fun FollowButton(
    state: FollowState,
    onClick: () -> Unit,
    prominent: Boolean = true,
) {
    var confirmingUnfollow by remember { mutableStateOf(false) }
    when (state) {
        FollowState.Self, FollowState.Blocked -> Unit
        FollowState.None -> {
            val label = @Composable { Text(stringResource(R.string.follow_none)) }
            if (prominent) {
                Button(onClick = onClick) { label() }
            } else {
                FilledTonalButton(onClick = onClick) { label() }
            }
        }

        FollowState.Following -> OutlinedButton(onClick = { confirmingUnfollow = true }) {
            Text(stringResource(R.string.follow_following))
        }

        FollowState.Mutual -> OutlinedButton(onClick = { confirmingUnfollow = true }) {
            Text(stringResource(R.string.follow_mutual))
        }
    }
    if (confirmingUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmingUnfollow = false },
            // 不写说明。**"取消关注"这四个字已经说完了这件事**,再补一句"取消后要重新找到
            // 这个人"是在教用户关注是怎么回事。PiliPlus 的取关面板同样只有选项没有说明
            // (request_utils.dart 的 relationMod 那一段)。
            title = { Text(stringResource(R.string.follow_unfollow_confirm_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnfollow = false
                    onClick()
                }) { Text(stringResource(R.string.follow_unfollow_confirm_title)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnfollow = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
