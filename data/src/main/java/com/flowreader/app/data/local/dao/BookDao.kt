package com.flowreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.flowreader.app.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadTime DESC, addedTime DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY lastReadTime DESC, addedTime DESC")
    fun getAllBooksSync(): List<BookEntity>

    @Query("SELECT * FROM books ORDER BY lastReadTime DESC, addedTime DESC LIMIT :limit OFFSET :offset")
    suspend fun getBooksPaged(offset: Int, limit: Int): List<BookEntity>

    @Query("SELECT * FROM books WHERE categoryId = :categoryId ORDER BY addedTime DESC")
    fun getBooksByCategory(categoryId: Long): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE lastReadTime IS NOT NULL ORDER BY lastReadTime DESC LIMIT :limit")
    fun getRecentlyReadBooks(limit: Int = 10): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookByIdSync(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE filePath = :filePath")
    suspend fun getBookByPath(filePath: String): BookEntity?

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE tags LIKE '%' || :tag || '%' ORDER BY addedTime DESC")
    fun getBooksByTag(tag: String): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity): Long

    /**
     * 切换一本书的活动内容 revision（主计划 5.2 / 5.3 第 4 步）。
     *
     * **必须与 `ContentIndexDao.activateRevision` 在同一个事务里调用**：`books.activeRevision`
     * 是唯一激活开关，文件侧已经先落地，所以这里只做指针切换。
     */
    @Query(
        """
        UPDATE books
           SET publicationId = :publicationId,
               activeRevision = :activeRevision,
               contentState = :contentState
         WHERE id = :bookId
        """
    )
    suspend fun updateContentState(bookId: Long, publicationId: String, activeRevision: String, contentState: String)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET isFavorite = :favorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: Long, favorite: Boolean): Int

    @Transaction
    suspend fun updateBookPreservingFavorite(book: BookEntity) {
        val current = getBookById(book.id) ?: return
        updateBook(book.copy(isFavorite = current.isFavorite, seriesName = current.seriesName, seriesOrder = current.seriesOrder))
    }

    @Query("SELECT * FROM books WHERE seriesName = :name ORDER BY seriesOrder, id")
    suspend fun getSeriesBooks(name: String): List<BookEntity>

    @Query("UPDATE books SET seriesName = :name, seriesOrder = :position WHERE id = :bookId")
    suspend fun setSeries(bookId: Long, name: String?, position: Int): Int

    @Transaction
    suspend fun assignSeries(ids: List<Long>, name: String?) {
        val members = name?.let { getSeriesBooks(it) }.orEmpty()
        var last = members.maxOfOrNull { it.seriesOrder } ?: 0
        val existing = members.map { it.id }.toSet()
        ids.forEach { id ->
            if (name == null || id !in existing) {
                check(setSeries(id, name, if (name == null) 0 else ++last) == 1) { "书籍不存在，系列未保存" }
            }
        }
    }

    @Transaction
    suspend fun reorderSeries(name: String, ids: List<Long>) {
        val members = getSeriesBooks(name)
        require(ids.size == ids.distinct().size && ids.toSet() == members.map { it.id }.toSet()) { "系列已变化，请重新排序" }
        ids.forEachIndexed { index, id -> setSeries(id, name, index + 1) }
    }

    @Transaction
    suspend fun renameSeries(name: String, newName: String) {
        require(name == newName || getSeriesBooks(newName).isEmpty()) { "已有同名系列，请先调整名称" }
        getSeriesBooks(name).forEach { setSeries(it.id, newName, it.seriesOrder) }
    }

    @Delete
    suspend fun deleteBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: Long)

    @Query(
        "UPDATE books SET currentChapter = :chapter, currentPosition = :position, " +
            "readingProgress = :progress, lastReadTime = :time WHERE id = :bookId"
    )
    suspend fun updateReadingProgress(bookId: Long, chapter: Int, position: Int, progress: Float, time: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM books")
    suspend fun getBookCount(): Int

    // v53 batch shelf edits. One statement per action instead of a per-row loop, so a 200-book
    // selection emits a single Room invalidation rather than 200.

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun deleteBooksByIds(ids: List<Long>)

    @Query("UPDATE books SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun updateCategoryForBooks(ids: List<Long>, categoryId: Long?)

    @Query("UPDATE books SET author = :author WHERE id IN (:ids)")
    suspend fun updateAuthorForBooks(ids: List<Long>, author: String)

    @Query("UPDATE books SET tags = :tags WHERE id IN (:ids)")
    suspend fun updateTagsForBooks(ids: List<Long>, tags: String)
}
