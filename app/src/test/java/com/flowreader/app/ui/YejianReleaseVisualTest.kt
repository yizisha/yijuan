package com.flowreader.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import com.flowreader.app.core.designsystem.reader.background
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.domain.model.*
import com.flowreader.app.ui.screens.reader.components.*
import com.flowreader.feature.reader.ChapterPaginator
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YejianReleaseVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun launcherNameAndAdaptiveArtworkUseTheNewBrand() = iconPreview()

    @Config(sdk = [26], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
    @Test
    fun adaptiveArtworkAlsoLoadsOnAndroid8() = iconPreview("brand-api26")

    private fun iconPreview(name: String = "brand-api34") {
        val icon = rule.activity.getDrawable(R.mipmap.ic_launcher)!!
        assertTrue(icon is android.graphics.drawable.AdaptiveIconDrawable)
        assertEquals("一卷", rule.activity.getString(R.string.app_name))
        assertEquals("一卷", rule.activity.packageManager.getApplicationLabel(rule.activity.applicationInfo).toString())
        val bitmap = icon.toBitmap(360, 360)
        rule.setContent {
            FlowTheme(AppThemeMode.LIGHT) {
                Column(
                    Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFFBF0DE)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(bitmap.asImageBitmap(), "一卷", Modifier.size(144.dp))
                    Spacer(Modifier.height(24.dp))
                    Text("一卷", style = MaterialTheme.typography.headlineMedium)
                    Text(com.flowreader.app.BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(32.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Image(bitmap.asImageBitmap(), "48dp", Modifier.size(48.dp))
                        Image(bitmap.asImageBitmap(), "64dp", Modifier.size(64.dp))
                    }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/reports/yijuan-039/$name.png")
    }

    @Test fun chapterButtonsAreDirectAndThereIsOnlyOneMoreMenu() = controlsPreview(false)

    @Test fun nightControlsKeepExistingPaletteAndDisableBoundaries() = controlsPreview(true)

    private fun controlsPreview(night: Boolean) {
        var chapter by mutableIntStateOf(2)
        var settings = 0
        var searched = 0
        rule.setContent {
            FlowTheme(if (night) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                ReaderControls(
                    true, "一卷", "章节", chapter, 3, { .5f }, false,
                    ReaderPalettes.of(if (night) ReaderPaletteId.NIGHT else ReaderPaletteId.PAPER), { "章节" },
                    {}, { chapter-- }, { chapter++ }, {}, {}, {}, {}, {}, { searched++ }, {}, {},
                    { settings++ }, {}, {}, {},
                    modifier = Modifier.background(
                        ReaderPalettes.of(if (night) ReaderPaletteId.NIGHT else ReaderPaletteId.PAPER).background
                    )
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_more)).assertDoesNotExist()
        rule.onAllNodesWithContentDescription(rule.activity.getString(R.string.action_more)).assertCountEquals(1)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_prev_chapter)).performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_prev_chapter)).assertIsNotEnabled()
        repeat(2) { rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_next_chapter)).performClick() }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_next_chapter)).assertIsNotEnabled()
        rule.onRoot().captureRoboImage("build/reports/yejian-030/controls-${if (night) "night" else "light"}.png")
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_more)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.reader_menu_search)).performClick()
        assertEquals(1, searched)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_more)).performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNode(isPopup()).captureRoboImage("build/reports/yejian-030/single-menu-${if (night) "night" else "light"}.png")
        rule.onNodeWithText(rule.activity.getString(R.string.reader_settings_title)).performClick()
        assertEquals(1, settings)
    }

    @Test fun aReadyRequestedPageDisplaysWhileTheRestIsBlockedWithoutSavingProvisionalProgress() {
        val captured = AtomicReference<ReaderPageKey>()
        val store = object : ReaderPageStore {
            override fun get(key: ReaderPageKey): List<List<ChapterPaginator.Fragment>>? {
                captured.set(key)
                return null
            }
            override fun put(key: ReaderPageKey, pages: List<List<ChapterPaginator.Fragment>>) {}
        }
        val cache = ReaderPageCache(store = store)
        val queued = ConcurrentLinkedQueue<Runnable>()
        val paused = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued += block
            }
        }
        val readyText = "当前页已就绪，其余内容在后台继续排版。"
        val prefix = "前一页的正文。".repeat(80) + "\n\n"
        val chapter = Chapter(bookId = 1, index = 0, title = "EPUB 开书", content = prefix + readyText)
        var reports = 0
        rule.setContent {
            FlowTheme {
                PagedReader(
                    chapter, 0, ReadingSettings(), ReaderPalettes.of(ReaderPaletteId.PAPER),
                    androidx.compose.ui.text.font.FontFamily.Default, emptyList(), 1, { _, _ -> reports++ }, {}, {},
                    onHighlightSelection = { _, _, _ -> }, onBookmarkSelection = { _, _, _ -> },
                    onImageClick = { _, _ -> }, pageCache = cache, paginationDispatcher = paused
                )
            }
        }
        rule.waitUntil(10_000) { captured.get() != null && queued.isNotEmpty() }
        rule.runOnIdle {
            cache.publishPreview(captured.get(), 1, listOf(ChapterPaginator.Fragment(readyText, prefix.length, true, false, false)))
        }
        rule.onNode(hasText(readyText, substring = true)).assertIsDisplayed()
        assertEquals(0, reports)
        rule.onRoot().captureRoboImage("build/reports/yejian-030/first-page-before-full-layout.png")
        rule.runOnIdle { while (queued.isNotEmpty()) queued.poll()?.run() }
        rule.waitForIdle()
        assertTrue(cache.size() > 0)
    }

    @Test fun reopeningAndNightSwitchReuseTheReadyLayoutWithoutDispatchingAgain() {
        val cache = ReaderPageCache()
        var shown by mutableStateOf(true)
        var night by mutableStateOf(false)
        var dispatches = 0
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                kotlinx.coroutines.Dispatchers.Main.immediate.dispatch(context, block)
            }
        }
        val chapter = Chapter(bookId = 11, index = 2, title = "EPUB 再次打开", content = "再次进入时，直接显示已经准备好的正文，不重复整章排版。")
        rule.setContent {
            FlowTheme(if (night) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                if (shown) {
                    val palette = ReaderPalettes.of(if (night) ReaderPaletteId.NIGHT else ReaderPaletteId.PAPER)
                    PagedReader(
                        chapter, 2, ReadingSettings(palette = palette.id), palette,
                        androidx.compose.ui.text.font.FontFamily.Default, emptyList(), 0, { _, _ -> }, {}, {},
                        onHighlightSelection = { _, _, _ -> }, onBookmarkSelection = { _, _, _ -> },
                        onImageClick = { _, _ -> }, pageCache = cache, paginationDispatcher = dispatcher, bookId = 11
                    )
                }
            }
        }
        rule.waitUntil(10_000) { cache.size() == 1 }
        assertEquals(1, dispatches)
        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        rule.runOnIdle { shown = true }
        rule.onNodeWithText(chapter.title).assertIsDisplayed()
        assertEquals(1, dispatches)
        rule.onRoot().captureRoboImage("build/reports/yejian-030/reopen-without-retypesetting.png")
        rule.runOnIdle { night = true }
        rule.waitForIdle()
        assertEquals(1, dispatches)
        rule.onRoot().captureRoboImage("build/reports/yejian-030/night-without-retypesetting.png")
    }
}
