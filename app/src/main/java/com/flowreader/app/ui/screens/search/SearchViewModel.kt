package com.flowreader.app.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.GlobalSearchResult
import com.flowreader.app.domain.repository.SearchProgress
import com.flowreader.app.domain.repository.SearchRepository
import com.flowreader.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val history: List<String> = emptyList(),
    val bookResults: List<Book> = emptyList(),
    val chapterResults: List<GlobalSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val hasMoreChapters: Boolean = false,
    val error: String? = null,
    val progress: SearchProgress? = null,
    val cancelled: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            settingsRepository.getSearchHistory().collect { history ->
                _uiState.update { it.copy(history = history) }
            }
        }
    }

    fun updateQuery(query: String) {
        cancelSearch()
        _uiState.update {
            it.copy(
                query = query,
                bookResults = emptyList(),
                chapterResults = emptyList(),
                hasMoreChapters = false,
                error = null,
                cancelled = false,
                isSearching = query.isNotBlank()
            )
        }
        val request = generation
        searchJob = viewModelScope.launch {
            delay(350)
            search(query, request)
        }
    }

    fun cancelSearch() {
        generation++
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false, progress = null, cancelled = true) }
    }

    private suspend fun search(query: String, request: Long) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(bookResults = emptyList(), chapterResults = emptyList(), isSearching = false) }
            return
        }
        _uiState.update { it.copy(isSearching = true, error = null, cancelled = false) }
        try {
            val books = searchRepository.searchBooks(trimmed)
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            _uiState.update { it.copy(bookResults = books) }
            val chapters = searchRepository.searchChaptersWithProgress(trimmed, CHAPTER_PAGE_SIZE + 1, 0) { progress ->
                if (request == generation) _uiState.update { it.copy(progress = progress) }
            }
            currentCoroutineContext().ensureActive()
            if (request != generation) return
            _uiState.update {
                it.copy(
                    bookResults = books,
                    chapterResults = chapters.take(CHAPTER_PAGE_SIZE),
                    isSearching = false,
                    progress = null,
                    hasMoreChapters = chapters.size > CHAPTER_PAGE_SIZE
                )
            }
            settingsRepository.addSearchHistory(trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (request != generation) return
            _uiState.update {
                it.copy(isSearching = false, progress = null, error = "搜索失败: ${e.localizedMessage ?: "未知错误"}")
            }
        }
    }

    fun loadMoreChapters() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.isSearching || !_uiState.value.hasMoreChapters) return
        val request = generation
        val offset = _uiState.value.chapterResults.size
        _uiState.update { it.copy(isSearching = true, error = null, cancelled = false) }
        searchJob = viewModelScope.launch {
            try {
                val more = searchRepository.searchChaptersWithProgress(query, CHAPTER_PAGE_SIZE + 1, offset) { progress ->
                    if (request == generation) _uiState.update { it.copy(progress = progress) }
                }
                currentCoroutineContext().ensureActive()
                if (request != generation) return@launch
                _uiState.update {
                    it.copy(
                        chapterResults = it.chapterResults + more.take(CHAPTER_PAGE_SIZE),
                        hasMoreChapters = more.size > CHAPTER_PAGE_SIZE,
                        isSearching = false,
                        progress = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == generation) {
                    _uiState.update {
                        it.copy(isSearching = false, progress = null, error = "加载更多失败: ${e.localizedMessage ?: "未知错误"}")
                    }
                }
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            settingsRepository.clearSearchHistory()
        }
    }

    fun useHistory(query: String) {
        updateQuery(query)
    }

    companion object {
        const val CHAPTER_PAGE_SIZE = 20
    }
}
