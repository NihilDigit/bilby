package dev.bilby.ui.dynamic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.bilby.data.CommentSort
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.comment.CommentSection
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListScrollbar
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.LocalSidePane
import dev.bilby.ui.components.SidePaneLayer
import dev.bilby.ui.components.rememberSidePaneState
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.theme.Spacing

/**
 * 一条动态自己的一页:窄屏下上面是这条动态、下面是它的评论区;宽屏(`Expanded` 及以上且有评论区)
 * 拆为左右双栏,左栏独立滚动展示动态正文,右栏独立滚动展示评论区并在右栏内托管楼中楼回复面板。
 *
 * **窄屏下动态跟着评论一起滚,不钉在顶上。** 一条九宫格图文加一段长正文能占满整屏,钉住的话评论区
 * 只剩一条缝;而这一页存在的理由就是评论。所以窄屏下卡片是评论列表的第一项(`CommentSection` 的
 * `header`),读过之后自然滑走。
 *
 * 评论区完全复用视频那一套,只是 oid 与 type 换成了服务端在这条动态的 `basic` 里下发的那对
 * (见 [dev.bilby.data.model.DynamicInteraction])。**这里不写任何按动态类型选评论区的判断** ——
 * 那张对照表不存在,写出来就是发明。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DynamicDetailScreen(
    state: DynamicDetailUiState,
    commentState: CommentUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAction: (DynamicAction) -> Unit,
    onLike: (like: Boolean) -> Unit,
    onCommentSort: (CommentSort) -> Unit,
    onCommentRefresh: () -> Unit,
    onCommentLoadMore: () -> Unit,
    /** 评论正文里引的那条链接。站内解析归导航层,见 MainActivity 的 openLink。 */
    onOpenLink: (String) -> Unit,
    onExpandReplies: (rootId: Long) -> Unit,
    onSendComment: (text: String, replyTo: Long?) -> Unit,
    onLikeComment: (id: Long) -> Unit,
    onDeleteComment: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val isWide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    var scrollToCommentsTick by remember { mutableIntStateOf(0) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.dynamic_detail_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val card = state.card
        when {
            card == null && state.loading -> FullScreenLoading(Modifier.padding(padding))
            card == null -> FullScreenError(
                state.errorRes?.let { stringResource(it) } ?: state.error.orEmpty(),
                onRetry,
                Modifier.padding(padding),
            )

            else -> {
                val selectableText = !LocalPointerSource.current.isTouchLike
                val hasComments = card.interaction?.hasComments == true
                if (isWide && hasComments) {
                    AdaptiveContent(
                        maxWidth = TwoPaneMaxWidth,
                        modifier = Modifier.padScaffoldExceptBottom(padding),
                    ) {
                        val sidePane = rememberSidePaneState()
                        CompositionLocalProvider(LocalSidePane provides sidePane) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = Spacing.Comfortable),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(LeftPaneWeight)
                                        .fillMaxHeight(),
                                ) {
                                    val leftListState = rememberLazyListState()
                                    val bottomInset = navigationBarsBottom()
                                    LazyColumn(
                                        state = leftListState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            top = Spacing.Cozy,
                                            bottom = Spacing.Comfortable + bottomInset,
                                        ),
                                    ) {
                                        item(key = "dynamic-card") {
                                            DynamicCardView(
                                                card = card,
                                                onAction = onAction,
                                                onLike = onLike,
                                                openable = false,
                                                selectableText = selectableText,
                                                onCommentClick = { scrollToCommentsTick++ },
                                            )
                                        }
                                    }
                                    ListScrollbar(leftListState, Modifier.align(Alignment.CenterEnd))
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(RightPaneWeight)
                                        .fillMaxHeight()
                                        .navigationBarsPadding()
                                        .padding(top = Spacing.Cozy, bottom = Spacing.Cozy)
                                        .clip(MaterialTheme.shapes.largeIncreased)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                ) {
                                    CommentSection(
                                        onOpenLink = onOpenLink,
                                        state = commentState,
                                        onSort = onCommentSort,
                                        onRefresh = onCommentRefresh,
                                        onLoadMore = onCommentLoadMore,
                                        onExpandReplies = onExpandReplies,
                                        onSend = onSendComment,
                                        onLike = onLikeComment,
                                        onDelete = onDeleteComment,
                                        onUserClick = { onAction(DynamicAction.OpenUser(it)) },
                                        header = null,
                                        scrollToCommentsTick = scrollToCommentsTick,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    SidePaneLayer(sidePane)
                                }
                            }
                        }
                    }
                } else {
                    // **只让顶栏,底边交给里面。** 评论区底部那条输入栏的底色要铺到屏幕底边、在底色里面
                    // 让导航栏(见 CommentSection 的 CommentInputBar);这里要是按 Scaffold 给的 padding
                    // 把底边也让掉,输入栏下面就露出一条页面底色。
                    AdaptiveContent(
                        modifier = Modifier.padScaffoldExceptBottom(padding),
                    ) {
                        val cardView: @Composable () -> Unit = {
                            DynamicCardView(
                                card = card,
                                onAction = onAction,
                                onLike = onLike,
                                openable = false,
                                selectableText = selectableText,
                                onCommentClick = if (hasComments) {
                                    { scrollToCommentsTick++ }
                                } else {
                                    null
                                },
                                modifier = Modifier.padding(
                                    horizontal = Spacing.Comfortable,
                                    vertical = Spacing.Cozy,
                                ),
                            )
                        }
                        // 评论区关掉或者服务端没给身份时,这一页就只有这条动态。**不画一个空评论区**:
                        // 那会让人以为是没读到,而实际上这里本来就没有可读的东西。
                        if (hasComments) {
                            CommentSection(
                                onOpenLink = onOpenLink,
                                state = commentState,
                                onSort = onCommentSort,
                                onRefresh = onCommentRefresh,
                                onLoadMore = onCommentLoadMore,
                                onExpandReplies = onExpandReplies,
                                onSend = onSendComment,
                                onLike = onLikeComment,
                                onDelete = onDeleteComment,
                                onUserClick = { onAction(DynamicAction.OpenUser(it)) },
                                header = cardView,
                                scrollToCommentsTick = scrollToCommentsTick,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .navigationBarsPadding(),
                            ) {
                                cardView()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 双栏动态详情总宽上限:超宽屏下保持正文与评论区行长适中,避免左右拉得过宽。 */
private val TwoPaneMaxWidth = 1360.dp
private const val LeftPaneWeight = 1.1f
private const val RightPaneWeight = 1f
