package com.flowreader.app.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.flowreader.app.data.local.dao.ReaderStateDao
import com.flowreader.app.data.local.entity.ReaderStateEntity
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.*
import com.flowreader.app.util.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 正文读取**成本契约**（性能回归闸门）。
 *
 * 背景：真机上一本书最多 1434 章。打开书时把**每一章正文**都读出来拼恢复清单，
 * 意味着 1434 次正文读取卡在同步路径上；换章若也变成「跟书规模成正比」，阅读就彻底不流畅了。
 * 这正是「尝试一次 S5 之后变卡」的正确防线：**换章/打开书的成本必须与全书章数无关**。
 *
 * 因此这里不测「有没有更快」，而是测**规模无关性**：
 * 同一动作在 20 章与 2000 章的书上必须执行同样多次正文读取。
 * 任何「顺手多读几章」的改动（例如为新功能加载全章结构/图片索引）都会在这里变红。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderChapterLoadCostTest {

    /**
     * 建一个真实 [ReaderViewModel]，把正文读取次数通过 [reads] 暴露出来。
     *
     * 只计数**正文**读取：单章 [ChapterRepository.getChapterContent] 与批量
     * [ChapterRepository.getChapterContents]（按请求章数计）。元数据/长度查询不算，
     * 因为它们不随书规模线性搬运正文。
     */
    private fun TestScope.withCountedReader(
        chapterCount: Int,
        savedLocator: Boolean,
        initialSettings: kotlinx.coroutines.flow.Flow<AppSettings> = MutableStateFlow(AppSettings()),
        block: TestScope.(ReaderViewModel, () -> Int) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var reads = 0
        val cachedContent = mutableMapOf<Int, String>()
        try {
            val books = mockk<BookRepository>(relaxed = true)
            val chapters = mockk<ChapterRepository>(relaxed = true)
            val settings = mockk<SettingsRepository>(relaxed = true)
            val tts = mockk<TtsManager>(relaxed = true)
            val readerStateDao = mockk<ReaderStateDao>(relaxed = true)

            coEvery { books.getBookById(1) } returns Book(
                id = 1,
                title = "Book",
                author = "Author",
                filePath = "book.txt",
                format = BookFormat.TXT
            )
            coEvery { chapters.getChapterMetadataList(1) } returns (0 until chapterCount).map {
                Chapter(bookId = 1, index = it, title = "Chapter $it")
            }
            coEvery { chapters.getChapterContent(1, any()) } coAnswers {
                // Repository cache hits do not read text from storage. Model that cache so
                // prefetch and foreground reads are charged only when they actually load a row.
                cachedContent.getOrPut(secondArg()) {
                    reads += 1
                    "chapter body text"
                }
            }
            coEvery { chapters.getChapterContents(1, any()) } coAnswers {
                reads += arg<List<Int>>(1).size
                emptyMap()
            }
            coEvery { chapters.getTextUnitCounts(1) } returns emptyList()
            coEvery { chapters.getChapterTextLengths(1) } returns emptyMap()
            every { settings.appSettings } returns initialSettings
            every { settings.getDailyReadingGoal() } returns MutableStateFlow(30)
            every { tts.state } returns MutableStateFlow(TtsState())

            if (savedLocator) {
                // 恢复清单必须基于真实长度，否则用户位置会丢 —— 旧实现为此读了**全书**正文。
                val locator = TextLocator(
                    version = TextLocator.CURRENT_VERSION,
                    publicationId = "legacy-1",
                    revision = "legacy",
                    sectionId = "section_5",
                    documentKey = "chapter:5",
                    offsetUtf16 = 10
                )
                coEvery { readerStateDao.getState(1) } returns ReaderStateEntity(
                    bookId = 1,
                    locatorJson = TextLocatorCodec.toJson(locator),
                    quality = "EXACT",
                    navigationSequence = 1,
                    updatedAtMillis = 0
                )
            }

            val reader = ReaderViewModel(
                SavedStateHandle(mapOf("bookId" to 1L)), books, chapters,
                mockk(relaxed = true), mockk(relaxed = true), settings, mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), tts,
                mockk(relaxed = true), mockk(relaxed = true), readerStateDao,
                chapterImageResolver = mockk(relaxed = true)
            )
            store.put("reader", reader)
            runCurrent()
            block(reader) { reads }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun firstContentWaitsForSavedTypographyInsteadOfTypesettingTemporaryDefaults() = runTest {
        val saved = ReadingSettings(fontSize = 26, fontFamily = ReaderFontFamily.SERIF, pageMode = PageMode.SLIDE)
        val delayed = kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.delay(200)
            emit(AppSettings(defaultReadingSettings = saved))
        }
        withCountedReader(20, false, delayed) { reader, reads ->
            assertTrue(reader.uiState.value.isLoading)
            assertEquals(0, reads())
            advanceTimeBy(201)
            runCurrent()
            assertTrue(!reader.uiState.value.isLoading)
            assertEquals(saved, reader.uiState.value.readingSettings)
            assertTrue(reads() in 1..4)
        }
    }

    @Test
    fun openingABookDoesNotReadContentForEveryChapter() = runTest {
        var smallBook = -1
        var largeBook = -1
        withCountedReader(chapterCount = 20, savedLocator = true) { _, reads -> smallBook = reads() }
        withCountedReader(chapterCount = 2000, savedLocator = true) { _, reads -> largeBook = reads() }

        assertEquals(
            "打开书读取的正文次数不得随章数增长（20 章 = $smallBook 次，2000 章 = $largeBook 次）",
            smallBook,
            largeBook
        )
        assertTrue(
            "打开书只允许读取当前章与有界预取窗口，实际 $largeBook 次",
            largeBook in 1..4
        )
    }

    @Test
    fun switchingAChapterReadsOnlyTheChapterBeingOpened() = runTest {
        var smallBook = -1
        var largeBook = -1
        withCountedReader(chapterCount = 20, savedLocator = false) { reader, reads ->
            val before = reads()
            reader.goToNextChapter()
            runCurrent()
            smallBook = reads() - before
        }
        withCountedReader(chapterCount = 2000, savedLocator = false) { reader, reads ->
            val before = reads()
            reader.goToNextChapter()
            runCurrent()
            largeBook = reads() - before
        }

        assertEquals("换章成本不得随书规模增长", smallBook, largeBook)
        assertEquals("目标章已预热，换章只补齐窗口中新进入的一章", 1, largeBook)
    }
}
