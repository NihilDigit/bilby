package dev.bilby.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import dev.bilby.formatDurationMillis
import dev.bilby.ui.theme.Spacing

/**
 * 放本地副本时,这条视频在别处被看到了更靠后(或更靠前)的位置。
 *
 * **是一条可以无视的提示,不是一次跳转。** 播放已经从本机上次的位置起播了,点了才 seek。
 * 自动跳过去会让播放头在用户没做任何事的情况下自己动,那比停在一个稍旧的位置更难理解 ——
 * 而"稍旧"有下限:它就是本机上次看到的地方。
 *
 * 判据本身在服务那侧([dev.bilby.player.mergeCachedProgress]),这里只负责摆出来。
 *
 * **两个动作都写出来,并且不自动消失。** 想恢复进度的时候是少数,所以"不恢复"要有一个看得见
 * 的出口,而不是等它自己走。给了显式的拒绝入口之后再保留自动消失,这条提示就有了三种结局,
 * 事后分不清刚才那条是自己关掉的还是自己走的。
 *
 * 用文字而不是一个 × :这条提示只有一行小字宽,三个 48dp 热区排下来 × 反而更占地方,而"忽略"
 * 两个字不用猜。整块不再可点,跳转只认那一个按钮 —— 两个动作并排时,"点哪都是跳转"会把忽略
 * 变成一个容易误触的陷阱。
 *
 * **忽略过的位置不再弹,而记住这件事不能放在这里。** 这个组件有两个调用点(全屏一份、竖排
 * 一份),切全屏换的是另一个实例,[visible] 从 false 重新起步,`LaunchedEffect` 又把它设回
 * true —— 忽略过的提示会因为转一次屏重新冒出来。所以判据由调用方持有,见 [VideoScreen] 里的
 * `dismissedResumeMillis`:这里只负责把被忽略的那个位置报上去。
 *
 * @param onDismiss 报告哪一个位置被忽略了。调用方据此不再把同一个值传进来。
 */
@Composable
fun CloudResumeHint(
    positionMillis: Long?,
    onJump: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    // 退场动画还在跑的时候 [positionMillis] 可能已经变回 null,那一帧仍然要有数字可画。
    var shown by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(positionMillis) {
        if (positionMillis == null) {
            visible = false
            return@LaunchedEffect
        }
        shown = positionMillis
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            MaterialTheme.motionScheme.fastSpatialSpec(),
            expandFrom = Alignment.Top,
        ) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = shrinkVertically(
            MaterialTheme.motionScheme.fastSpatialSpec(),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = modifier,
    ) {
        val position = shown ?: return@AnimatedVisibility
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.inverseSurface,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = Spacing.Cozy, end = Spacing.Hair),
            ) {
                Text(
                    text = stringResource(
                        R.string.video_cloud_resume,
                        formatDurationMillis(position),
                    ),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                // 两个按钮的强调度要分开:跳转是这条提示存在的理由,忽略只是退路。都用
                // inversePrimary 的话,读者得先读字才知道哪个是主的。
                TextButton(
                    onClick = {
                        visible = false
                        onJump(position)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inversePrimary,
                    ),
                ) {
                    Text(stringResource(R.string.video_cloud_resume_jump))
                }
                TextButton(
                    onClick = {
                        visible = false
                        onDismiss(position)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ),
                ) {
                    Text(stringResource(R.string.video_cloud_resume_dismiss))
                }
            }
        }
    }
}
