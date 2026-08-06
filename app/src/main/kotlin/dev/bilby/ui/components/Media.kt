package dev.bilby.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.bilby.api.toHttpsUrl
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing

/**
 * B 站图床有防盗链,不带 Referer 一律 403;部分接口还会返回 `//` 开头的协议相对地址。
 * 这两件事以前散在七八个 AsyncImage 调用点上,漏一个就是一张裂图,收到这里来。
 */
@Composable
private fun biliImageRequest(url: String) = ImageRequest.Builder(LocalContext.current)
    .data(url.toHttpsUrl())
    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
    .build()

/** 封面底边那条「看到哪了」的刻度高度。 */
private val ProgressBarHeight = 3.dp

/**
 * 视频封面。固定 16:9,内容裁切填满 —— B 站封面本身是 16:9,但接口偶尔给别的比例,
 * 用 Fit 的话列表里会出现高矮不齐的行。
 *
 * [durationText] 与 [progressFraction] 压在封面上而不是排进下面的文字行:
 * 这两个都是"现在要不要点开"的决策依据,压在图上一眼扫到,排进文字行就得读一遍。
 */
@Composable
fun VideoCover(
    url: String,
    modifier: Modifier = Modifier,
    durationText: String = "",
    progressFraction: Float? = null,
    cornerRadius: Dp = 8.dp,
) {
    Box(modifier = modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(cornerRadius))) {
        AsyncImage(
            model = biliImageRequest(url),
            // 封面是装饰:它右边就是同一条视频的标题,读屏再念一遍图片等于把每条读两遍。
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        if (durationText.isNotEmpty()) {
            // 有进度条时把角标抬高一点,否则两者压在同一条底边上,角标正好盖住进度条的末端,
            // 看起来像进度只走到一半就断了。
            val bottomInset = if (progressFraction != null && progressFraction > 0f) {
                Spacing.Hair + ProgressBarHeight
            } else {
                Spacing.Hair
            }
            MediaBadge(
                text = durationText,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.Hair, bottom = bottomInset),
            )
        }

        // 看到哪了。用两个 Box 而不是 LinearProgressIndicator:后者自带高度、圆头和 track 色,
        // 压在图片上像个跑进画面里的控件;这里要的只是一条刻度。
        if (progressFraction != null && progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(ProgressBarHeight)
                    .background(FixedColors.ScrimOnMedia),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * 压在图片上的小角标(时长)。底色固定黑色遮罩、文字固定白色,不跟主题 ——
 * 背后是 UP 主上传的任意图片,不是我们能控制的 surface。取值理由见 [FixedColors]。
 */
@Composable
fun MediaBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = FixedColors.OnMedia,
        modifier = modifier
            .background(FixedColors.ScrimOnMedia, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = Spacing.Hair, vertical = 1.dp),
    )
}

/**
 * 方形封面。合集/系列用它,和视频行的 16:9 拉开距离 —— 形状本身就说明了
 * "这是一组视频"还是"这是一个视频",不用先读下面那行小字。
 */
@Composable
fun SquareCover(url: String, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = biliImageRequest(url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)),
    )
}

/** 圆形头像。同样走 [biliImageRequest],同样是装饰(旁边一定跟着用户名)。 */
@Composable
fun Avatar(url: String, modifier: Modifier = Modifier, size: Dp = Dimens.AvatarSmall) {
    AsyncImage(
        model = biliImageRequest(url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape),
    )
}

/** 固定宽度的封面,列表行专用。 */
@Composable
fun ListCover(
    url: String,
    modifier: Modifier = Modifier,
    durationText: String = "",
    progressFraction: Float? = null,
    width: Dp = Dimens.ListCoverWidth,
    cornerRadius: Dp = 8.dp,
) = VideoCover(
    url = url,
    durationText = durationText,
    progressFraction = progressFraction,
    cornerRadius = cornerRadius,
    modifier = modifier.width(width),
)
