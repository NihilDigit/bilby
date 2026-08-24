package dev.bilby.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 画面收走之后顶上那条。**它出现的场合只有一个**:视频暂停、人往下翻评论,画面因此收起
 * (见 `VideoScreen` 的 `canCollapsePlayer` —— 播放中钉着不收,简介那一边也不许收)。
 * 所以这里不需要第二个判据,跟着收起进度淡入就够。
 *
 * 参照 PiliPlus 的 `pages/video/view.dart` 的 `_buildOverlayToolBar`,三处按本项目的结构改了:
 *
 * - **加了标题。** 那边正中只放一个播放按钮,因为它的条上方还有别的东西写着在看什么;这条
 *   出现时人在评论区,整屏没有第二个地方写着这是哪条视频。
 * - **不搬「回主页」。** 返回在这里就是 backstack 弹一层,没有"主页"这个另外的去处。
 * - **不搬「更多设置」。** 倍速、画质、字幕是播放器控件,画面收走时它们本该跟着一起走
 *   (风格指南 §4.3:控件跟播放器,不跟页面 chrome)。
 *
 * **两个落点,只有一个改播放状态。** 点条本身把画面拿回来(展开),点右边那颗按钮才是接着放。
 * 分开是因为"我想再看一眼画面"和"我要接着看"是两件事,而后者不可逆地让声音响起来 —— 在
 * 评论区里误触一下就开始外放,代价比多点一次大。
 */
@Composable
fun QuickPlayBar(
    title: String,
    /** 播完了没有。播完显示「重新播放」,按下从头开始;其余是「继续播放」。 */
    finished: Boolean,
    onBack: () -> Unit,
    /** 点条:把画面拿回来,不动播放。 */
    onExpand: () -> Unit,
    /** 点按钮:接着放(或从头放),并把画面拿回来。 */
    onPlay: () -> Unit,
    /** 收起进度,0 是完全展开、1 是收到底。见 `CollapsingHeaderState.collapsedFraction`。 */
    visibility: Float,
    modifier: Modifier = Modifier,
) {
    // 完全展开时整条不进组合:它此刻既看不见也不该接触摸,而画面正占着这块位置。
    if (visibility <= 0f) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .height(QuickPlayBarHeight)
            // 淡入而不是滑入:它出现的位置就是画面正在退出的那块,两个东西一起动会看不出
            // 谁是谁。透明度跟着手指走,松手停在哪就是哪,和画面的收起量始终对得上。
            .alpha(visibility)
            .clickable(onClick = onExpand),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = Spacing.Tight),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // text button 而不是实心按钮:这一条本身是让路让出来的,不该比它下面的评论还重。
            TextButton(
                onClick = onPlay,
                contentPadding = PaddingValues(horizontal = Spacing.Tight),
            ) {
                Icon(
                    imageVector = if (finished) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Text(
                    text = stringResource(
                        if (finished) R.string.video_replay else R.string.video_resume,
                    ),
                    modifier = Modifier.padding(start = Spacing.Hair),
                )
            }
        }
    }
}

/**
 * 这条的高度。取和顶栏一样的 64dp:它站的正是页面顶栏的位置,矮一档会让下面的评论跟着上下
 * 挪一截,而这块空间在收起过程中本来就在变。
 */
val QuickPlayBarHeight = 64.dp
