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
import androidx.compose.ui.graphics.Color
import dev.bilby.ui.components.SeekBarSegment
import dev.bilby.ui.theme.Spacing
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
 * 每一类到底指什么。**这九个名字自己解释不了自己**:"填充内容""预览回顾""非音乐段落"
 * 单看都像黑话,而用户要判断的是"我要不要让播放器自动跳过它" —— 判断不了就只能全开或全关,
 * 那九个开关就白给了。
 */
val CATEGORY_DESCRIPTIONS: Map<String, Int> = mapOf(
    "sponsor" to R.string.sponsor_sponsor_desc,
    "selfpromo" to R.string.sponsor_selfpromo_desc,
    "interaction" to R.string.sponsor_interaction_desc,
    "intro" to R.string.sponsor_intro_desc,
    "outro" to R.string.sponsor_outro_desc,
    "preview" to R.string.sponsor_preview_desc,
    "padding" to R.string.sponsor_padding_desc,
    "filler" to R.string.sponsor_filler_desc,
    "music_offtopic" to R.string.sponsor_music_offtopic_desc,
)

/**
 * 九类分三组。分组不是为了好看,是为了让"要不要跳"这个判断能一次做一组:推广是别人塞进来的,
 * 结构片段是这条视频自己的包装,内容片段是 UP 讲的话里偏题的那部分 —— 三种东西的取舍理由
 * 完全不同,平铺成九行就只剩九个孤立的开关。
 */
val CATEGORY_GROUPS: List<Pair<Int, List<String>>> = listOf(
    R.string.settings_sponsorblock_group_promo to listOf("sponsor", "selfpromo", "interaction"),
    R.string.settings_sponsorblock_group_structure to listOf("intro", "outro", "preview", "padding"),
    R.string.settings_sponsorblock_group_content to listOf("filler", "music_offtopic"),
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

    // 提示浮在画面顶部,所以从上边缘展开 —— 规范:"The direction a component enters is informed
    // by their location on screen, expanding away from the device edge."
    // 组件动效用 MotionScheme 的 spring,不用转场那套 tween(见 theme/Motion.kt 的说明)。
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
                text = label,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            )
        }
    }
}

/**
 * 类别配色,取自 SponsorBlock 官方扩展的默认色板 —— 用过那个扩展的人不必重新学一遍颜色。
 *
 * 只覆盖 [CATEGORY_LABELS] 里那九个会被跳过的类别;取不到就不染色(返回 null),
 * 而不是给一个兜底色 —— 染上一个没人认识的颜色比不染更糟。
 */
fun sponsorCategoryColor(category: String): Color? = when (category) {
    "sponsor" -> Color(0xFF00D400)
    "selfpromo" -> Color(0xFFFFFF00)
    "interaction" -> Color(0xFFCC00FF)
    "intro" -> Color(0xFF00FFFF)
    "outro" -> Color(0xFF0202ED)
    "preview" -> Color(0xFF008FD6)
    "padding" -> Color(0xFF2F2F2F)
    "filler" -> Color(0xFF7300FF)
    "music_offtopic" -> Color(0xFFFF9900)
    else -> null
}

/** 片段转成进度条能画的形状。颜色认不出来的整条丢掉,不占位。 */
fun List<SponsorSegment>.toSeekBarSegments(): List<SeekBarSegment> = mapNotNull { segment ->
    sponsorCategoryColor(segment.category)?.let {
        SeekBarSegment(segment.startMillis, segment.endMillis, it)
    }
}
