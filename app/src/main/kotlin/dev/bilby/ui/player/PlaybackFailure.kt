package dev.bilby.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 播放失败盖在画面上的那一块。**视频页和直播间共用这一份** —— 两处的画面是同一个壳,失败时
 * 底下同样是一片黑,提示与重试的样子没有理由各写一遍。
 *
 * 和听视频页那一行仍是两个实现,因为形态差得远:那边是一行贴着播放控制的小字。
 */
@Composable
fun PlaybackFailure(
    message: String,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(Spacing.Comfortable),
    ) {
        Text(
            text = message,
            color = FixedColors.OnMedia,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        // 重试中不给按钮:此刻按下去只会打断已经在跑的那次。
        if (retrying) {
            CircularProgressIndicator(
                color = FixedColors.OnMedia,
                modifier = Modifier.padding(top = Spacing.Cozy).size(24.dp),
            )
        } else {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry), color = FixedColors.OnMedia)
            }
        }
    }
}

/**
 * **失败不立刻报。** 服务在取流路上自己会退避重试(见 `AudioPlaybackService.retryAfterFailure`),
 * 重试期间 error 就已经是有值的了 —— 照直显示的话,一次最终成功的加载中途也会闪一下"播放
 * 失败",而那时它明明还在正常往下走。
 *
 * 等它稳定 [PlaybackErrorGraceMillis] 还在,才当成真失败。[raw] 中途消失或换了内容,计时
 * 从头开始。
 */
@Composable
fun rememberSettledPlaybackError(raw: String?): String? {
    var settled by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(raw) {
        if (raw == null) {
            settled = null
            return@LaunchedEffect
        }
        delay(PlaybackErrorGraceMillis)
        settled = raw
    }
    return settled
}

/** 取流失败的宽限期。服务自己的重试最坏花 3 秒,留一点余地。 */
private const val PlaybackErrorGraceMillis = 5_000L
