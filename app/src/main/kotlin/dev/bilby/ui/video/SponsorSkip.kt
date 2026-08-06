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
import androidx.compose.ui.unit.dp
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

/** category 到中文类别名的映射,取自 BSponsorBlock 的分类定义(与 PiliPlus 的中文文案一致)。 */
private val CATEGORY_LABELS = mapOf(
    "sponsor" to "赞助推广",
    "selfpromo" to "自我推广",
    "interaction" to "三连提醒",
    "intro" to "片头",
    "outro" to "片尾",
    "preview" to "预览回顾",
    "padding" to "填充内容",
    "filler" to "离题闲聊",
    "music_offtopic" to "非音乐段落",
)

/**
 * 跳过时的一次性提示,几秒后自动消失。否则用户会以为播放器卡住了。
 * category 为 null 表示"这次没有发生跳过",不显示——可见性收在组件内部,
 * 调用方只需要在每次跳过时把新的 category 传进来。
 */
@Composable
fun SkipToast(category: String?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }

    LaunchedEffect(category) {
        if (category != null) {
            label = "已跳过${CATEGORY_LABELS[category] ?: category}"
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
