package com.flowreader.app.data.repository

import com.flowreader.app.data.local.dao.BookDao
import com.flowreader.app.data.local.entity.BookEntity
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.repository.BookRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.conflate

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao
) : BookRepository {

    override fun getAllBooks(): Flow<List<Book>> {
        return bookDao.getAllBooks().map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.Default).conflate()
    }

    override suspend fun getBooksPaged(offset: Int, limit: Int): List<Book> {
        return bookDao.getBooksPaged(offset, limit).map { it.toDomain() }
    }

    override suspend fun getBookCount(): Int {
        return bookDao.getBookCount()
    }

    override fun getBooksByCategory(categoryId: Long): Flow<List<Book>> {
        return bookDao.getBooksByCategory(categoryId).map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.Default).conflate()
    }

    override fun getRecentlyReadBooks(limit: Int): Flow<List<Book>> {
        return bookDao.getRecentlyReadBooks(limit).map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.Default).conflate()
    }

    override fun searchBooks(query: String): Flow<List<Book>> {
        return bookDao.searchBooks(query).map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.Default).conflate()
    }

    override fun getBooksByTag(tag: String): Flow<List<Book>> {
        return bookDao.getBooksByTag(tag).map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.Default).conflate()
    }

    override suspend fun getBookById(id: Long): Book? {
        return bookDao.getBookById(id)?.toDomain()
    }

    override suspend fun getBookByPath(filePath: String): Book? {
        return bookDao.getBookByPath(filePath)?.toDomain()
    }

    override suspend fun insertBook(book: Book): Long {
        return bookDao.insertBook(BookEntity.fromDomain(book))
    }

    override suspend fun updateBook(book: Book) {
        bookDao.updateBookPreservingFavorite(BookEntity.fromDomain(book))
    }

    override suspend fun setFavorite(bookId: Long, isFavorite: Boolean) {
        check(bookDao.setFavorite(bookId, isFavorite) == 1) { "书籍不存在，无法更新收藏" }
    }

    override suspend fun setBooksSeries(bookIds: List<Long>, seriesName: String?) {
        val name = seriesName?.let { com.flowreader.app.domain.model.BookSeries.requireName(it) }
        bookDao.assignSeries(bookIds.filter { it > 0 }.distinct(), name)
    }

    override suspend fun reorderSeries(seriesName: String, bookIds: List<Long>) {
        bookDao.reorderSeries(com.flowreader.app.domain.model.BookSeries.requireName(seriesName), bookIds)
    }

    override suspend fun renameSeries(seriesName: String, newName: String) {
        bookDao.renameSeries(seriesName, com.flowreader.app.domain.model.BookSeries.requireName(newName))
    }

    override suspend fun deleteBook(book: Book) {
        bookDao.deleteBook(BookEntity.fromDomain(book))
    }

    override suspend fun deleteBookById(id: Long) {
        bookDao.deleteBookById(id)
    }

    override suspend fun updateReadingProgress(bookId: Long, chapter: Int, position: Int, progress: Float) {
        bookDao.updateReadingProgress(bookId, chapter, position, progress)
    }

    override suspend fun deleteBooksByIds(ids: List<Long>) {
        val valid = ids.filter { it > 0L }.distinct()
        if (valid.isEmpty()) return
        bookDao.deleteBooksByIds(valid)
    }

    override suspend fun moveBooksToCategory(ids: List<Long>, categoryId: Long?) {
        val valid = ids.filter { it > 0L }.distinct()
        if (valid.isEmpty()) return
        bookDao.updateCategoryForBooks(valid, categoryId)
    }

    override suspend fun updateBooksMetadata(ids: List<Long>, author: String?, tags: List<String>?) {
        val valid = ids.filter { it > 0L }.distinct()
        if (valid.isEmpty()) return
        // A null field means "leave alone"; a blank author would otherwise silently erase every
        // author in the selection when the user only meant to retag.
        author?.takeIf { it.isNotBlank() }?.let { bookDao.updateAuthorForBooks(valid, it.trim()) }
        tags?.let { bookDao.updateTagsForBooks(valid, it.map { tag -> tag.trim() }.filter { tag -> tag.isNotEmpty() }.joinToString(",")) }
    }
}
