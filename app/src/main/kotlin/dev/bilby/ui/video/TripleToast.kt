package dev.bilby.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import dev.bilby.R
import dev.bilby.data.TripleResult
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 三连之后的一次性提示,几秒后自己走。
 *
 * **成功也报。** 三样里成了哪几样由服务端逐项回执决定(见 [TripleResult]):硬币不够的时候
 * 赞和收藏照常生效,而那一格图标本来就没亮过,人分不出是这次没投成还是自己记错了。
 *
 * 报的是**成了什么**,不是"哪一样失败了"。失败的原因这条接口不给 —— 硬币不够、收藏夹满、
 * 稿件不让投币回的都是同一个 false,照着编一句理由比不说更糟。
 */
@Composable
fun TripleToast(outcome: TripleOutcome?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    // 退场动画还没跑完时 outcome 可能已经被换掉,那一帧仍然要有话可说。
    var shown by remember { mutableStateOf<String?>(null) }

    val separator = stringResource(R.string.video_triple_separator)
    val allLabel = stringResource(R.string.video_triple_all)
    val noneLabel = stringResource(R.string.video_triple_none)
    val someFormat = stringResource(R.string.video_triple_some)
    val likeLabel = stringResource(R.string.video_action_like)
    val coinLabel = stringResource(R.string.video_action_coin)
    val favLabel = stringResource(R.string.video_action_favorite)

    // key 用 seq 而不是整个 outcome:连按两次得到相等的结果时,不带序号的 key 认成同一个值,
    // 第二次什么都不会弹。
    LaunchedEffect(outcome?.seq) {
        val current = outcome ?: return@LaunchedEffect
        val result = current.result
        shown = when {
            current.error != null -> current.error
            result == null -> noneLabel
            result.liked && result.coined && result.favored -> allLabel
            result.allFailed -> noneLabel
            else -> {
                val done = buildList {
                    if (result.liked) add(likeLabel)
                    if (result.coined) add(coinLabel)
                    if (result.favored) add(favLabel)
                }
                someFormat.format(done.joinToString(separator))
            }
        }
        visible = true
        delay(VisibleMillis)
        visible = false
    }

    // 从上边缘展开:提示落在画面顶部,规范要求进场方向随所在的屏幕边缘。与 [SkipToast] 同形,
    // 那两条本来就可能前后脚出现,长得不一样只会让人以为是两种东西。
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
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.inverseSurface,
        ) {
            Text(
                text = shown.orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            )
        }
    }
}

private const val VisibleMillis = 2_500L
