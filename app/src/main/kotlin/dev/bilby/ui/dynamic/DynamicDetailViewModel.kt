package dev.bilby.ui.dynamic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.DynamicRepository
import dev.bilby.data.model.DynamicCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DynamicDetailUiState(
    val card: DynamicCard? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 一条动态自己的一页:正文 + 评论区。
 *
 * **进来就拉一次详情,不接受列表页传过来的那份卡片。** 评论区的 oid 与 type 在 `basic` 里,
 * 而列表项的 `basic` 有时是缺的(notes/dynamic-cards.md 第 8 节)—— 用列表那份就要在这里
 * 再分出"齐了直接用、缺了补一次"两条路,而其中一条从来没被走到过的话,它坏了也没人知道。
 * 详情那条请求本来也要发:列表里的正文可能是摘要。
 *
 * 评论区自己不在这里,它是 [dev.bilby.ui.comment.CommentViewModel] —— 那一份要等 oid 与 type
 * 落地才建得出来,和播放页等 aid 是同一个形状。
 */
class DynamicDetailViewModel(
    private val repository: DynamicRepository,
    private val id: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DynamicDetailUiState(loading = true))
    val state: StateFlow<DynamicDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        load()
    }

    /** 点赞。乐观更新、失败回滚、不重拉,理由同 [OtherDynamicsViewModel.like]。 */
    fun like(like: Boolean) {
        applyLike(like)
        viewModelScope.launch {
            val result = repository.likeDynamic(id, like)
            if (result is BiliResult.ApiError || result is BiliResult.Failure) {
                BiliLog.w("动态 $id 点赞失败,已回滚")
                applyLike(!like)
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = repository.loadDetail(id)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(card = result.value, loading = false, error = null)
                }

                is BiliResult.ApiError -> setError("${result.message}(${result.code})")
                is BiliResult.Failure -> setError(result.cause.message ?: "网络错误")
            }
        }
    }

    private fun applyLike(like: Boolean) = _state.update { current ->
        val card = current.card ?: return@update current
        val interaction = card.interaction ?: return@update current
        if (interaction.liked == like) return@update current
        current.copy(
            card = card.copy(
                interaction = interaction.copy(
                    liked = like,
                    likeCount = (interaction.likeCount + if (like) 1 else -1).coerceAtLeast(0),
                ),
            ),
        )
    }

    private fun setError(message: String) = _state.update { it.copy(loading = false, error = message) }
}
