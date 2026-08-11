package dev.bilby.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.ArticleRepository
import dev.bilby.data.model.Article
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArticleUiState(
    val loading: Boolean = true,
    val article: Article? = null,
    val error: String? = null,
)

class ArticleViewModel(
    private val id: String,
    private val isRead: Boolean,
    private val repository: ArticleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ArticleUiState())
    val state: StateFlow<ArticleUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.loadArticle(id, isRead)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(loading = false, article = result.value, error = null)
                }

                is BiliResult.ApiError -> setError(result.message)
                is BiliResult.Failure -> setError(result.cause.message ?: "网络错误")
            }
        }
    }

    private fun setError(message: String) = _state.update { it.copy(loading = false, error = message) }
}
