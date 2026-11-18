package dev.bilby.ui.article

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.ArticleRepository
import dev.bilby.data.model.Article
import dev.bilby.ui.errorTextRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

@Immutable
data class ArticleUiState(
    val loading: Boolean = true,
    val article: Article? = null,
    val error: String? = null,
    val errorRes: StringResource? = null,
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
        _state.update { it.copy(loading = true, error = null, errorRes = null) }
        viewModelScope.launch {
            when (val result = repository.loadArticle(id, isRead)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(loading = false, article = result.value, error = null, errorRes = null)
                }

                else -> _state.update {
                    it.copy(loading = false, error = null, errorRes = result.errorTextRes("专栏详情"))
                }
            }
        }
    }
}
