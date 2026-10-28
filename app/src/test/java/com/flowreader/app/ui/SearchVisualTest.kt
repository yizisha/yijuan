package com.flowreader.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontFamily
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.SearchProgress
import com.flowreader.app.ui.screens.reader.components.SearchDialog
import com.flowreader.app.ui.screens.reader.components.ReaderContent
import com.flowreader.app.ui.screens.reader.components.PagedReader
import com.flowreader.app.ui.screens.search.SearchContent
import com.flowreader.app.ui.screens.search.SearchUiState
import com.flowreader.app.util.FtsSearchResult
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        rule.waitForIdle()
        val file = File("build/0.3.6-preview/$name.png").apply { parentFile!!.mkdirs() }
        rule.onRoot().captureRoboImage(file.absolutePath)
    }
    private val hit = GlobalSearchResult(1, "山与生活：这是一本较长书名的阅读笔记", 9, "第十章 山间清晨",
        "清晨，山间有薄雾升起，像一层轻纱。把世界柔和地包裹起来。", ranges = listOf(SourceRange("chapter:9", 7, 9)),
        confidence = SearchHitProjector.Confidence.EXACT)
    private fun results(dark: Boolean, name: String) {
        var clicked = false
        rule.setContent {
            FlowTheme(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                SearchContent(SearchUiState(query = "薄雾", chapterResults = listOf(hit)), {}, {},
                    { book, chapter, offset -> clicked = book == 1L && chapter == 9 && offset == 7 }, {}, {}, {}, {})
            }
        }
        capture(name)
        rule.onNodeWithText(hit.bookTitle).performClick()
        assertTrue(clicked)
    }
    @Test fun lightResultsKeepContextAndJumpParameters() = results(false, "search-light")
    @Test fun darkResultsKeepExistingWarmNightTheme() = results(true, "search-dark")
    @Test fun progressHasWorkingCancelWithoutPrematureEmptyMessage() {
        var cancelled = false
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                SearchContent(SearchUiState(query = "山", isSearching = true, progress = SearchProgress(320, 1434)),
                    {}, {}, { _, _, _ -> }, {}, { cancelled = true }, {}, {})
            }
        }
        rule.onNodeWithText("未找到匹配结果").assertDoesNotExist()
        rule.onNodeWithText("已检索 320 / 1434 章").assertExists()
        capture("search-progress")
        rule.onNodeWithText("取消").performClick()
        assertTrue(cancelled)
    }
    @Test fun cancelledSearchOffersRetry() {
        var retried = false
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                SearchContent(SearchUiState(query = "山", cancelled = true), {}, {}, { _, _, _ -> },
                    { retried = it == "山" }, {}, {}, {})
            }
        }
        capture("search-cancelled")
        rule.onNodeWithText("重新搜索").performClick()
        assertTrue(retried)
    }
    @Test fun readerSearchShowsProgressAndCancellation() {
        var cancelled = false
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                SearchDialog("薄雾", emptyList(), true, false, {}, {}, { _, _ -> }, {},
                    SearchProgress(32, 500), onCancel = { cancelled = true })
            }
        }
        capture("reader-search-progress")
        rule.onNodeWithText("取消").performClick()
        assertTrue(cancelled)
    }
    @Test fun readerFailureIsVisibleAndNotPresentedAsNoMatch() {
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                SearchDialog("山", emptyList(), false, false, {}, {}, { _, _ -> }, {}, error = "无法读取正文，请重试")
            }
        }
        rule.onNodeWithText("未找到匹配结果").assertDoesNotExist()
        rule.onNodeWithText("无法读取正文，请重试").assertExists()
        capture("reader-search-error")
    }

    private val before = List(10) { "前文第${it}段。" + "山川与清晨，安静的阅读时光。".repeat(8) }.joinToString("\n\n")
    private val targetText = "目标关键词在这里，搜索应定位到这一段。"
    private val source = "章首\n\n" + before + "\n\n" + targetText + "\n\n" + before
    private val locator = TextLocator(publicationId = "pub", revision = "rev", documentKey = "chapter:9",
        offsetUtf16 = source.indexOf(targetText))

    @Test fun pagedSourceJumpUsesMeasuredPageInsteadOfTreatingCharacterOffsetAsPageNumber() {
        var reported = -1
        var applied = false
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                PagedReader(Chapter(bookId = 1, index = 9, title = "章首", content = source), 0,
                    ReadingSettings(fontSize = 18), ReaderPalettes.of(ReaderPaletteId.PAPER), FontFamily.Default, emptyList(), 0,
                    { page, _ -> reported = page }, {}, {}, onHighlightSelection = { _, _, _ -> },
                    onBookmarkSelection = { _, _, _ -> }, onImageClick = { _, _ -> },
                    paginationDispatcher = kotlinx.coroutines.Dispatchers.Main.immediate,
                    initialLocator = locator, onInitialLocatorApplied = { applied = true })
            }
        }
        rule.waitForIdle()
        org.junit.Assert.assertTrue(applied)
        org.junit.Assert.assertTrue("应跳到中间页，不能是第一页或被误当页码的最后一页：$reported", reported in 1..6)
        rule.onNode(hasText("目标关键词", substring = true)).assertIsDisplayed()
        capture("search-jump-paged")
    }

    @Test fun scrollSourceJumpUsesParagraphGeometryInsteadOfCharacterOffsetAsPixel() {
        var state: androidx.compose.foundation.ScrollState? = null
        var applied = false
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                val scroll = rememberScrollState()
                state = scroll
                ReaderContent(Chapter(bookId = 1, index = 9, title = "章首", content = source),
                    ReadingSettings(fontSize = 18), ReaderPalettes.of(ReaderPaletteId.PAPER), FontFamily.Default,
                    scrollState = scroll, onTap = { _, _ -> }, onDoubleTap = {}, onHorizontalDrag = {},
                    onHighlightSelection = { _, _, _ -> }, onBookmarkSelection = { _, _, _ -> }, onPositionChanged = {},
                    initialLocator = locator, onInitialLocatorApplied = { applied = true })
            }
        }
        rule.waitForIdle()
        assertTrue(applied)
        org.junit.Assert.assertTrue(state!!.value > 500)
        org.junit.Assert.assertNotEquals(locator.offsetUtf16, state!!.value)
        val target = rule.onNode(hasText(targetText, substring = true), useUnmergedTree = true)
        target.assertIsDisplayed()
        org.junit.Assert.assertTrue("命中文字需要完整位于阅读视口内", target.fetchSemanticsNode().boundsInRoot.top > 70f)
        capture("search-jump-scroll")
    }
}
