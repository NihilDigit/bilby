package dev.bilby.ui.dynamic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import dev.bilby.data.CommentSort
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.comment.CommentSection
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.theme.Spacing

/**
 * 一条动态自己的一页:上面是这条动态,下面是它的评论区。
 *
 * **动态跟着评论一起滚,不钉在顶上。** 一条九宫格图文加一段长正文能占满整屏,钉住的话评论区
 * 只剩一条缝;而这一页存在的理由就是评论。所以卡片是评论列表的第一项(`CommentSection` 的
 * `header`),读过之后自然滑走。
 *
 * 评论区完全复用视频那一套,只是 oid 与 type 换成了服务端在这条动态的 `basic` 里下发的那对
 * (见 [dev.bilby.data.model.DynamicInteraction])。**这里不写任何按动态类型选评论区的判断** ——
 * 那张对照表不存在,写出来就是发明。
 */
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
    onExpandReplies: (rootId: Long) -> Unit,
    onSendComment: (text: String, replyTo: Long?) -> Unit,
    onLikeComment: (id: Long) -> Unit,
    onDeleteComment: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { BilbyTopBar(title = stringResource(R.string.dynamic_detail_title), onBack = onBack) },
    ) { padding ->
        val card = state.card
        when {
            card == null && state.loading -> FullScreenLoading(Modifier.padding(padding))
            card == null -> FullScreenError(
                state.error.orEmpty(),
                onRetry,
                Modifier.padding(padding),
            )

            else -> AdaptiveContent(modifier = Modifier.padding(padding)) {
                val cardView: @Composable () -> Unit = {
                    DynamicCardView(
                        card = card,
                        onAction = onAction,
                        onLike = onLike,
                        modifier = Modifier.padding(
                            horizontal = Spacing.Comfortable,
                            vertical = Spacing.Cozy,
                        ),
                    )
                }
                // 评论区关掉或者服务端没给身份时,这一页就只有这条动态。**不画一个空评论区**:
                // 那会让人以为是没读到,而实际上这里本来就没有可读的东西。
                if (card.interaction?.hasComments == true) {
                    CommentSection(
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
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        cardView()
                    }
                }
            }
        }
    }
}
