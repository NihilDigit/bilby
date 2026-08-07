package dev.bilby.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.data.SponsorSegment
import kotlinx.coroutines.delay

/**
 * 给定当前播放位置,返回应该跳到哪里;不需要跳时返回 null。
 *
 * 纯函数,不摸播放器状态:方便单测,调用方(播放器的位置监听)只管按返回值 seek。
 * 要求 `segments` 已经按 startMillis 升序且互不重叠——SponsorBlockRepository.segments
 * 已经做了排序和合并,这里不重复做,免得两处逻辑各管一半、出 bug 时不好定位是哪一层。
 */
fun nextSkipTarget(positionMillis: Long, segments: List<SponsorSegment>): Long? {
    for (segment in segments) {
        // segments 按起点升序:还没到这一段的起点,后面的起点只会更晚,可以直接判定未命中。
        if (positionMillis < segment.startMillis) return null
        if (positionMillis < segment.endMillis) return segment.endMillis
    }
    return null
}

/**
 * category 到中文类别名的映射,取自 BSponsorBlock 的分类定义
 * (PiliPlus/lib/models/common/sponsor_block/segment_type.dart 的 shortTitle)。
 *
 * 顺序即设置页里的显示顺序,所以用有序的 linked map,不要换成别的容器。
 * 只列 actionType 为 skip 的类别 —— 空降点(poi_highlight)和整片打标
 * (exclusive_access)不是"跳过一段时间"的语义,给它们一个跳过开关是错的。
 */
val CATEGORY_LABELS: Map<String, Int> = mapOf(
    "sponsor" to R.string.sponsor_sponsor,
    "selfpromo" to R.string.sponsor_selfpromo,
    "interaction" to R.string.sponsor_interaction,
    "intro" to R.string.sponsor_intro,
    "outro" to R.string.sponsor_outro,
    "preview" to R.string.sponsor_preview,
    "padding" to R.string.sponsor_padding,
    "filler" to R.string.sponsor_filler,
    "music_offtopic" to R.string.sponsor_music_offtopic,
)

/**
 * 跳过时的一次性提示,几秒后自动消失。否则用户会以为播放器卡住了。
 * category 为 null 表示"这次没有发生跳过",不显示——可见性收在组件内部,
 * 调用方只需要在每次跳过时把新的 category 传进来。
 */
@Composable
fun SkipToast(category: String?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    // 类别名要走资源,取不到就退回接口给的原始 category(至少能看出跳过了什么)。
    val categoryName = CATEGORY_LABELS[category]?.let { stringResource(it) } ?: category.orEmpty()
    val label = stringResource(R.string.sponsor_skipped, categoryName)

    LaunchedEffect(category) {
        if (category != null) {
            visible = true
            delay(2500)
            visible = false
        }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
