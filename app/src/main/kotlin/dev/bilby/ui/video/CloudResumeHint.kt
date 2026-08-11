package dev.bilby.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.bilby.R
import dev.bilby.formatDurationMillis
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 放本地副本时,这条视频在别处被看到了更靠后(或更靠前)的位置。
 *
 * **是一条可以无视的提示,不是一次跳转。** 播放已经从本机上次的位置起播了,点了才 seek。
 * 自动跳过去会让播放头在用户没做任何事的情况下自己动,那比停在一个稍旧的位置更难理解 ——
 * 而"稍旧"有下限:它就是本机上次看到的地方。
 *
 * 判据本身在服务那侧([dev.bilby.player.mergeCachedProgress]),这里只负责摆出来。
 *
 * 比 [SkipToast] 停留得久一些 —— 那一条只是告知,这一条要等一次点击。
 */
@Composable
fun CloudResumeHint(
    positionMillis: Long?,
    onJump: (Long) -> Unit,
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
        delay(VISIBLE_MILLIS)
        visible = false
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
            modifier = Modifier.clickable(role = Role.Button) {
                visible = false
                onJump(position)
            },
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            ) {
                Text(
                    text = stringResource(
                        R.string.video_cloud_resume,
                        formatDurationMillis(position),
                    ),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = stringResource(R.string.video_cloud_resume_jump),
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
            }
        }
    }
}

/** 要等一次点击,所以比告知性的提示停留得久。 */
private const val VISIBLE_MILLIS = 8_000L
