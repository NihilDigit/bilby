package dev.bilby.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.VideoCover
import dev.bilby.ui.theme.Spacing

/**
 * 画面收走之后顶上那条。**它出现的场合**:视频暂停、人往下翻简介或评论,画面因此收起
 * (见 `VideoScreen` 的 `playerPinned` —— 播放中钉着不收)。
 * 所以这里不需要第二个判据,跟着收起进度淡入就够。
 *
 * 参照 PiliPlus 的 `pages/video/view.dart` 的 `_buildOverlayToolBar`,三处按本项目的结构改了:
 *
 * - **加了可选封面缩略图与强调标题。** 这条出现时人在下翻内容,整屏没有第二个地方写着这是哪条视频。
 * - **不搬「回主页」。** 返回在这里就是 backstack 弹一层,没有"主页"这个另外的去处。
 * - **不搬「更多设置」。** 倍速、画质、字幕是播放器控件,画面收走时它们本该跟着一起走
 *   (风格指南 §4.3:控件跟播放器,不跟页面 chrome)。
 *
 * **两个落点,只有一个改播放状态。** 点条本身把画面拿回来(展开),点右边那枚胶囊按钮才是接着放。
 * 分开是因为"我想再看一眼画面"和"我要接着看"是两件事,而后者不可逆地让声音响起来 —— 在
 * 阅读区里误触一下就开始外放,代价比多点一次大。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QuickPlayBar(
    title: String,
    /** 播完了没有。播完显示「重新播放」,按下从头开始;其余是「继续播放」。 */
    finished: Boolean,
    /**
     * 停在哪里,0 到 1。人在下方阅读时整屏看不到画面和进度条,这根线回答"按下去从哪接着放"。
     * null 或 `<= 0f` 时不画:时长还不知道、尚未起播,或者播放器里装的已经不是这一页的视频。
     *
     * 传 lambda 是为了让读取落在这里、落在下面那个"完全展开就不进组合"之后:位置刻度放着时
     * 半秒一条,在调用方读的话整块画面跟着重组。
     */
    progress: () -> Float?,
    onBack: () -> Unit,
    /** 点条:把画面拿回来,不动播放。 */
    onExpand: () -> Unit,
    /** 点按钮:接着放(或从头放),并把画面拿回来。 */
    onPlay: () -> Unit,
    /** 收起进度,0 是完全展开、1 是收到底。见 `CollapsingHeaderState.collapsedFraction`。 */
    visibility: () -> Float,
    modifier: Modifier = Modifier,
    /** 可选的视频封面地址;非空时在标题左侧展示 `44x27.5.dp`(`4.dp` 圆角)缩略图。 */
    coverUrl: String = "",
) {
    val isShown by remember { derivedStateOf { visibility() > 0f } }
    // 完全展开时整条不进组合:它此刻既看不见也不该接触摸,而画面正占着这块位置。
    if (!isShown) return
    val isInteractive by remember { derivedStateOf { visibility() > 0.5f } }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .height(QuickPlayBarHeight)
            // 淡入而不是滑入:它出现的位置就是画面正在退出的那块,两个东西一起动会看不出
            // 谁是谁。透明度跟着手指走,松手停在哪就是哪,和画面的收起量始终对得上。
            .graphicsLayer { alpha = visibility().coerceIn(0f, 1f) }
            .clickable(enabled = isInteractive, onClick = onExpand)
            .then(if (isInteractive) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(QuickPlayBarHeight)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(end = Spacing.Cozy),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.action_back),
                    )
                }
                if (coverUrl.isNotBlank()) {
                    VideoCover(
                        url = coverUrl,
                        cornerRadius = CoverThumbCorner,
                        aspectRatio = CoverThumbAspectRatio,
                        modifier = Modifier
                            .padding(end = Spacing.Cozy)
                            .width(CoverThumbWidth),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = Spacing.Tight),
                )
                FilledTonalButton(
                    onClick = onPlay,
                    shapes = ButtonDefaults.shapes(
                        shape = CircleShape,
                        pressedShape = MaterialTheme.shapes.small,
                    ),
                    contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = if (finished) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(PlayPillIconSize),
                    )
                    Text(
                        text = stringResource(
                            if (finished) Res.string.video_replay else Res.string.video_resume,
                        ),
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = Spacing.Hair),
                    )
                }
            }
            // 贴着条的下沿,也就是面板的上沿:线是条的一部分,落在面板里会被读成标签行的装饰。
            val shownProgress = progress()
            if (shownProgress != null && shownProgress > 0f) {
                LinearProgressIndicator(
                    progress = { shownProgress.coerceIn(0f, 1f) },
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(ProgressLineHeight),
                )
            }
        }
    }
}

/**
 * 直接传入静态进度值(`0f..1f`)的便捷重载,`0f` 时不画底沿进度条。
 */
@Composable
fun QuickPlayBar(
    title: String,
    finished: Boolean,
    onBack: () -> Unit,
    onExpand: () -> Unit,
    onPlay: () -> Unit,
    visibility: Float,
    modifier: Modifier = Modifier,
    coverUrl: String = "",
    progress: Float = 0f,
) = QuickPlayBar(
    title = title,
    finished = finished,
    progress = { progress.takeIf { it > 0f } },
    onBack = onBack,
    onExpand = onExpand,
    onPlay = onPlay,
    visibility = { visibility },
    modifier = modifier,
    coverUrl = coverUrl,
)

private val CoverThumbWidth = 44.dp
private const val CoverThumbAspectRatio = 16f / 10f
private val CoverThumbCorner = 4.dp
private val PlayPillIconSize = 18.dp
private val ProgressLineHeight = 3.dp

/**
 * 这条的高度。取和顶栏一样的 64dp:它站的正是页面顶栏的位置,矮一档会让下面的内容跟着上下
 * 挪一截,而这块空间在收起过程中本来就在变。
 */
val QuickPlayBarHeight = 64.dp
