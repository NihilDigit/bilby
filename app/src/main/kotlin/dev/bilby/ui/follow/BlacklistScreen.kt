package dev.bilby.ui.follow

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.BlockedUser
import dev.bilby.data.RelationRepository
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.appendDistinctBy
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BlacklistUiState(
    val users: List<BlockedUser> = emptyList(),
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 黑名单。
 *
 * **翻页按「已取条数 ≥ total」判到底**,不按"这一页是空的"判 —— 那是分组成员那条接口的判法,
 * 这条接口给了 total(notes/relation-groups.md 2.2)。两条挨着的接口用两套终止条件,抄错了
 * 不会报错,只会多发一次请求。
 */
class BlacklistViewModel(private val repository: RelationRepository) : ViewModel() {

    private val _state = MutableStateFlow(BlacklistUiState())
    val state: StateFlow<BlacklistUiState> = _state.asStateFlow()

    private var page = 0

    init {
        loadMore()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val next = page + 1
        _state.update { it.copy(loading = next == 1, appending = next > 1) }
        viewModelScope.launch {
            when (val result = repository.blacklist(next)) {
                is BiliResult.Ok -> {
                    page = next
                    _state.update {
                        val users = it.users.appendDistinctBy(result.value.users) { user -> user.mid }
                        it.copy(
                            users = users,
                            loading = false,
                            appending = false,
                            hasMore = users.size < result.value.total,
                            error = null,
                        )
                    }
                }

                is BiliResult.ApiError -> fail("${result.message}(${result.code})")
                is BiliResult.Failure -> fail(result.cause.message ?: "网络错误")
            }
        }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    /**
     * 取消拉黑。**乐观更新、失败回滚、不重拉**,与关注/取关同一套规矩:等一个来回再让这一行
     * 消失会让人以为没点上,而重拉整份名单会把已经翻到的位置一并丢掉。
     */
    fun unblock(mid: Long) {
        val before = _state.value.users
        if (before.none { it.mid == mid }) return
        _state.update { it.copy(users = it.users.filterNot { user -> user.mid == mid }) }
        viewModelScope.launch {
            val result = repository.unblock(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("取消拉黑失败: $result")
                _state.update { it.copy(users = before) }
            }
        }
    }

    private fun fail(message: String) {
        BiliLog.w("取黑名单失败: $message")
        _state.update { it.copy(loading = false, appending = false, error = message) }
    }
}

@Composable
fun BlacklistScreen(
    state: BlacklistUiState,
    onUnblock: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    AdaptiveContent(modifier = modifier) {
        PagedColumn(
            items = state.users,
            key = { it.mid },
            loading = state.loading,
            appending = state.appending,
            hasMore = state.hasMore,
            error = state.error,
            emptyText = stringResource(R.string.blacklist_empty),
            onLoadMore = onLoadMore,
            onRetry = onRetry,
            contentPadding = contentPadding,
        ) { user ->
            BlockedRow(user, onUnblock = { onUnblock(user.mid) })
        }
    }
}

/**
 * 一行一个被拉黑的人。**头像和名字不可点** —— 拉黑之后对方的空间本来就进不去,给一个点了
 * 报错的入口不如不给。这一行唯一的动作是取消拉黑。
 *
 * 副标题是拉黑时间。名单按时间倒序下发,没有这一行读者就分不出哪些是刚拉的、哪些是几年前的。
 */
@Composable
private fun BlockedRow(user: BlockedUser, onUnblock: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                user.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                stringResource(R.string.blacklist_blocked_at, formatRelativeTime(user.blockedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Avatar(url = user.faceUrl, size = Dimens.AvatarRow) },
        // 取消拉黑不二次确认:它是把关系放回原样,做错了再拉一次就是(拉黑那一侧要确认,
        // 见 BlockConfirmDialog)。
        trailingContent = {
            TextButton(onClick = onUnblock) { Text(stringResource(R.string.blacklist_unblock)) }
        },
    )
}
