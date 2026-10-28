package com.flowreader.app.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FullTextSearch @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FullTextSearch"
        private const val DB_NAME = "flowreader_fts.db"
        private val FTS_SPECIAL = setOf('^', '*', '-', '+', '~', '(', ')', '{', '}', '[', ']', ':', '"')

        /**
         * In-book search, scoped to one book.
         *
         * `book_id` **must** be compared through `CAST(? AS INTEGER)`. `book_content_fts` is an
         * FTS5 external-content table, so its columns have no declared type and therefore no
         * column affinity, while the values come back from `book_content.book_id` as INTEGER.
         * `SQLiteDatabase.rawQuery()` can only bind `String` arguments, and SQLite never treats
         * INTEGER 5 as equal to TEXT '5' when no affinity converts one side — so a bare
         * `book_id = ?` matched nothing and in-book search always returned zero results.
         * Guarded by `FullTextSearchQueryTest`.
         */
        internal const val SEARCH_IN_BOOK_SQL =
            "SELECT chapter_index, chapter_title, " +
                "snippet(book_content_fts, 3, '<<', '>>', '...', 30) as matched_text\n" +
                "FROM book_content_fts\n" +
                "WHERE book_id = CAST(? AS INTEGER) AND book_content_fts MATCH ?\n" +
                "ORDER BY rank\n" +
                "LIMIT ?"

        /**
         * 重建/刷新索引时每批取多少章正文。
         *
         * 逐章取正文会让「打开书」「导入后首次搜索」变成上百次数据库查询；
         * 一次全取又会让超大书的整本正文同时驻留内存。32 章是一个折中。
         */
        const val INDEX_CHUNK_SIZE = 32

        /** Global search across every indexed book; no book scoping, so no CAST needed. */
        internal const val SEARCH_ALL_SQL =
            "SELECT book_id, chapter_index, chapter_title, " +
                "snippet(book_content_fts, 3, '<<', '>>', '...', 30) as matched_text\n" +
                "FROM book_content_fts\n" +
                "WHERE book_content_fts MATCH ?\n" +
                "ORDER BY rank\n" +
                "LIMIT ? OFFSET ?"
    }

    @Volatile
    private var database: SQLiteDatabase? = null

    /**
     * Whether this device's SQLite actually has the fts5 module.
     *
     * Searching anything is impossible without it, so callers must not pretend a search ran and
     * found nothing. Set once per process while the schema is created.
     */
    @Volatile
    private var ftsReady: Boolean = true

    /** See [ftsReady]. */
    val isFtsAvailable: Boolean get() = ftsReady

    /**
     * Serializes every index mutation. `deleteAllContent()` + re-index (global rebuild) and
     * `deleteBookContent()` + re-index (opening a book in the reader) must not interleave, or a
     * book can end up deleted from the index after the rebuild already counted it as fresh.
     * The lock lives here rather than in one caller so no code path can skip it.
     */
    private val indexMutex = Mutex()

    /**
     * Serializes database initialization. Without this, concurrent callers can both pass
     * `if (database?.isOpen == true)` and both open a handle, leaking the first one.
     */
    private val initMutex = Mutex()

    /** Runs [block] with exclusive access to the index. Not re-entrant — never nest calls. */
    suspend fun <T> withIndexLock(block: suspend () -> T): T = indexMutex.withLock { block() }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (database?.isOpen == true) return@withContext

        initMutex.withLock {
            // Re-check inside the lock — another coroutine may have initialized while we waited.
            if (database?.isOpen == true) return@withContext

            try {
                database = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
                createTablesAndTriggers()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize FTS database", e)
                throw FtsException("Failed to initialize FTS database", e)
            }
        }
    }

    private fun createTablesAndTriggers() {
        database?.execSQL("""
            CREATE TABLE IF NOT EXISTS book_content(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL,
                chapter_index INTEGER NOT NULL,
                chapter_title TEXT,
                content TEXT,
                UNIQUE(book_id, chapter_index)
            )
        """.trimIndent())

        // Created *before* the FTS objects on purpose. On a device whose SQLite has no fts5 module,
        // the CREATE VIRTUAL TABLE below throws, and every statement after it would never run — which
        // is exactly how this table went missing on the Xiaomi test device and made the "skip
        // re-indexing when nothing changed" check silently fail every time.
        database?.execSQL("""
            CREATE TABLE IF NOT EXISTS indexed_books(
                book_id INTEGER PRIMARY KEY,
                fingerprint TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        ftsReady = createFtsObjects()
        if (!ftsReady) {
            Log.w(
                TAG,
                "SQLite on this device has no fts5 module: full-text search is unavailable and " +
                    "index writes are skipped instead of failing on every app start"
            )
        }
    }

    /**
     * Creates the FTS5 virtual table and its triggers.
     *
     * @return false when this SQLite build has no fts5 module (Android ships it on most but not all
     * devices — the Xiaomi PD2408/V2408A test device is one that does not). Never throws, so a
     * missing module degrades to "search unavailable" instead of a broken schema and a startup error.
     */
    private fun createFtsObjects(): Boolean = try {
        database?.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS book_content_fts USING fts5(
                book_id,
                chapter_index,
                chapter_title,
                content,
                content='book_content',
                content_rowid='id'
            )
        """.trimIndent())

        database?.execSQL("""
            CREATE TRIGGER IF NOT EXISTS book_content_ai AFTER INSERT ON book_content BEGIN
                INSERT INTO book_content_fts(rowid, book_id, chapter_index, chapter_title, content)
                VALUES (new.id, new.book_id, new.chapter_index, new.chapter_title, new.content);
            END
        """.trimIndent())

        database?.execSQL("""
            CREATE TRIGGER IF NOT EXISTS book_content_ad AFTER DELETE ON book_content BEGIN
                INSERT INTO book_content_fts(book_content_fts, rowid, book_id, chapter_index, chapter_title, content)
                VALUES ('delete', old.id, old.book_id, old.chapter_index, old.chapter_title, old.content);
            END
        """.trimIndent())

        database?.execSQL("""
            CREATE TRIGGER IF NOT EXISTS book_content_au AFTER UPDATE ON book_content BEGIN
                INSERT INTO book_content_fts(book_content_fts, rowid, book_id, chapter_index, chapter_title, content)
                VALUES ('delete', old.id, old.book_id, old.chapter_index, old.chapter_title, old.content);
                INSERT INTO book_content_fts(rowid, book_id, chapter_index, chapter_title, content)
                VALUES (new.id, new.book_id, new.chapter_index, new.chapter_title, new.content);
            END
        """.trimIndent())
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create FTS objects", e)
        false
    }

    private fun ensureInitialized() {
        val db = database
        if (db == null || !db.isOpen) {
            throw FtsNotInitializedException("FullTextSearch has not been initialized")
        }
    }

    suspend fun indexChapter(
        bookId: Long,
        chapterIndex: Int,
        chapterTitle: String,
        content: String
    ) = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            database?.execSQL(
                "INSERT OR REPLACE INTO book_content (book_id, chapter_index, chapter_title, content) VALUES (?, ?, ?, ?)",
                arrayOf(bookId.toString(), chapterIndex.toString(), chapterTitle, content)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to index chapter", e)
            throw FtsException("Failed to index chapter", e)
        }
    }

    private fun escapeFtsQuery(query: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < query.length) {
            val c = query[i]
            if (c == '"') {
                sb.append("\"\"")
            } else if (c in FTS_SPECIAL) {
                sb.append(' ').append(c).append(' ')
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    suspend fun search(
        bookId: Long,
        query: String,
        maxResults: Int = 50
    ): List<FtsSearchResult> = withContext(Dispatchers.IO) {
        ensureInitialized()
        if (!ftsReady) throw FtsUnavailableException()

        if (query.isBlank()) return@withContext emptyList()

        val escapedQuery = escapeFtsQuery(query)
        val safeMatch = "\"$escapedQuery\"*"

        try {
            val results = mutableListOf<FtsSearchResult>()
            val cursor = database?.rawQuery(
                SEARCH_IN_BOOK_SQL,
                arrayOf(bookId.toString(), safeMatch, maxResults.toString())
            )

            cursor?.use {
                while (it.moveToNext()) {
                    results.add(
                        FtsSearchResult(
                            chapterIndex = it.getInt(0),
                            chapterTitle = it.getString(1),
                            matchedText = it.getString(2),
                            bookId = bookId
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "FTS search failed", e)
            throw FtsException("FTS search failed for query: $query", e)
        }
    }

    suspend fun searchAll(
        query: String,
        maxResults: Int = 100,
        offset: Int = 0
    ): List<FtsSearchResult> = withContext(Dispatchers.IO) {
        ensureInitialized()
        if (!ftsReady) throw FtsUnavailableException()
        if (query.isBlank()) return@withContext emptyList()

        val safeMatch = "\"${escapeFtsQuery(query)}\"*"
        try {
            val results = mutableListOf<FtsSearchResult>()
            val cursor = database?.rawQuery(
                SEARCH_ALL_SQL,
                arrayOf(safeMatch, maxResults.toString(), offset.toString())
            )

            cursor?.use {
                while (it.moveToNext()) {
                    results.add(
                        FtsSearchResult(
                            bookId = it.getLong(0),
                            chapterIndex = it.getInt(1),
                            chapterTitle = it.getString(2),
                            matchedText = it.getString(3)
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Global FTS search failed", e)
            throw FtsException("Global FTS search failed for query: $query", e)
        }
    }

    suspend fun deleteBookContent(bookId: Long) = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            database?.execSQL("DELETE FROM book_content WHERE book_id = ?", arrayOf(bookId.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete book content", e)
            throw FtsException("Failed to delete book content", e)
        }
    }

    /**
     * True when [bookId]'s index was last written for exactly this [fingerprint].
     *
     * A miss (including any error) means "re-index"; never throws, so a broken state table can only
     * cost speed, never correctness.
     */
    suspend fun isBookIndexed(bookId: Long, fingerprint: String): Boolean = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            database?.rawQuery(
                "SELECT 1 FROM indexed_books WHERE book_id = ? AND fingerprint = ? LIMIT 1",
                arrayOf(bookId.toString(), fingerprint)
            )?.use { cursor ->
                return@withContext cursor.moveToFirst()
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read index state for book $bookId", e)
            false
        }
    }

    /** Records that [bookId]'s index now matches [fingerprint]. */
    suspend fun markBookIndexed(bookId: Long, fingerprint: String) = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            database?.execSQL(
                "INSERT OR REPLACE INTO indexed_books (book_id, fingerprint, updated_at) VALUES (?, ?, ?)",
                arrayOf(bookId.toString(), fingerprint, System.currentTimeMillis().toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record index state for book $bookId", e)
        }
    }

    suspend fun deleteAllContent() = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            database?.execSQL("DELETE FROM book_content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all indexed content", e)
            throw FtsException("Failed to delete all indexed content", e)
        }
    }

    /**
     * Close the database when the singleton is being destroyed.
     * Hilt will call this when the application is terminated.
     */
    fun close() {
        try {
            database?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing FTS database", e)
        } finally {
            database = null
        }
    }
}

class FtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
class FtsNotInitializedException(message: String) : Exception(message)

/**
 * This device's SQLite has no fts5 module, so no full-text search can ever succeed.
 *
 * Distinct from [FtsException] so the UI can say what is actually wrong (and so callers skip work
 * that cannot possibly help) instead of reporting "no results" for a search that never ran.
 */
class FtsUnavailableException :
    Exception("本机数据库不支持全文检索（SQLite 缺少 fts5 模块），搜索不可用")

/**
 * 一条搜索命中。
 *
 * [matchedText] 是**已去掉高亮标记与省略号**的可显示文本（调用方负责：FTS 原始片段含
 * `<<` `>>`，直接显示会让用户看到标记）。
 *
 * [locatorOffset] 是该命中在**章内规范正文**中的 UTF-16 偏移，由 [SearchHitProjector]
 * 回到正文重新定位并验证得到；-1 表示无法可靠定位（UI 必须标注为近似，
 * 并退回章首，**不得**把 chapterIndex 当作字符偏移）。
 */
data class FtsSearchResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val matchedText: String,
    val bookId: Long = 0,
    val bookTitle: String = "",
    /** 章内 UTF-16 命中偏移；-1 表示未定位。 */
    val locatorOffset: Int = -1,
    /** 定位可信度；默认保守为近似。 */
    val locatorConfidence: com.flowreader.app.domain.model.SearchHitProjector.Confidence =
        com.flowreader.app.domain.model.SearchHitProjector.Confidence.APPROXIMATE
) {
    /** 是否具备可精确跳转的坐标。 */
    val isLocatable: Boolean get() = locatorOffset >= 0
}
