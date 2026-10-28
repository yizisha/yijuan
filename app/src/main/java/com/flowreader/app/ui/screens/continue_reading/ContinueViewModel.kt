package com.flowreader.app.ui.screens.continue_reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the optional 继续阅读 tab.
 *
 * It derives from the same `getAllBooks()` flow the shelf uses instead of a second query, so a
 * book that is opened in the reader moves to the top here on the next invalidation with no extra
 * bookkeeping — and no risk of the two lists disagreeing.
 */
@HiltViewModel
class ContinueViewModel @Inject constructor(
    bookRepository: BookRepository
) : ViewModel() {

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            bookRepository.getAllBooks().collect { all ->
                _books.value = all
                    .filter { it.lastReadTime != null }
                    .sortedByDescending { it.lastReadTime }
                _isLoading.value = false
            }
        }
    }
}
