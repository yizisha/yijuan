package com.flowreader.app.ui

import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import com.flowreader.app.core.designsystem.component.BookCover
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.model.AppVisualStyle
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.ui.screens.library.LibraryScreen
import com.flowreader.app.ui.screens.library.LibraryUiState
import com.flowreader.app.ui.screens.library.LibraryViewModel
import com.flowreader.app.R
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CleanCoverVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val titles = listOf("友甲(友·合)", "我可是要做仙尊与魔帝的男人", "山与生活", "The Mountains Between Us")

    @Test fun shortAndLongGridTitlesKeepProgressAndMenusAligned() = alignedGrid(AppVisualStyle.CLEAN_BLUE, 1f, "grid-aligned")
    @Test fun warmGridUsesTheSameAlignmentWithoutChangingItsCoverArt() = alignedGrid(AppVisualStyle.WARM_PAPER, 1f, "grid-aligned-warm")

    @Config(sdk = [34], qualifiers = "zh-rCN-w320dp-h891dp-420dpi")
    @Test fun twoLineTitleSlotsFollowLargeTextAndKeepTheFooterAligned() = alignedGrid(AppVisualStyle.CLEAN_BLUE, 1.6f, "grid-aligned-small-large-text")

    private fun alignedGrid(style: AppVisualStyle, scale: Float, name: String) {
        val books = listOf(
            Book(id = 1, title = "友甲(友·合)", author = "未知作者", filePath = "one.txt", format = BookFormat.TXT, readingProgress = .3f),
            Book(id = 2, title = "我可是要做仙尊与魔帝的男人", author = "东方快鸟", filePath = "two.txt", format = BookFormat.TXT, readingProgress = .04f)
        )
        val vm = mockk<LibraryViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(LibraryUiState(books = books, allBooks = books, gridColumns = 2))
        every { vm.isRefreshing } returns MutableStateFlow(false)
        var details = 0L
        rule.setContent {
            FlowTheme(visualStyle = style) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    LibraryScreen(
                        onBookClick = { details = it }, onContinueReading = {}, onSearchClick = {}, onSettingsClick = {},
                        onNotesClick = {}, onReadingListsClick = {}, onOpdsClick = {}, viewModel = vm
                    )
                }
            }
        }
        val firstProgress = rule.onNodeWithText("30%", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val secondProgress = rule.onNodeWithText("4%", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val menus = books.map {
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.library_book_actions, it.title))
        }
        assertEquals(firstProgress.center.y, secondProgress.center.y, 1f)
        assertEquals(menus[0].fetchSemanticsNode().boundsInRoot.center.y, menus[1].fetchSemanticsNode().boundsInRoot.center.y, 1f)
        assertEquals(firstProgress.center.y, menus[0].fetchSemanticsNode().boundsInRoot.center.y, 1f)
        val covers = books.map {
            rule.onNodeWithContentDescription(
                rule.activity.getString(com.flowreader.core.R.string.flow_cover_description, it.title), useUnmergedTree = true
            ).fetchSemanticsNode().boundsInRoot
        }
        assertEquals(covers[0].top, covers[1].top, 1f)
        assertEquals(covers[0].height, covers[1].height, 1f)
        rule.onRoot().captureRoboImage("build/reports/0.3.2/$name.png")
        menus[0].performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.book_action_detail)).performClick()
        rule.runOnIdle { assertEquals(1L, details) }
    }

    @Test fun cleanPaperCoversFitReferenceTitlesAndSmallThumbnails() = gallery(false, AppVisualStyle.CLEAN_BLUE, "clean-covers")
    @Test fun cleanNightUsesTheSameBookArtWithinTheExistingNightTheme() = gallery(true, AppVisualStyle.CLEAN_BLUE, "clean-covers-night")
    @Test fun warmPaperStillUsesItsExistingGeneratedCover() = gallery(false, AppVisualStyle.WARM_PAPER, "warm-covers-unchanged")

    @Config(sdk = [34], qualifiers = "zh-rCN-w320dp-h891dp-420dpi")
    @Test fun coverLetteringStaysInsideTheTileAtLargeAccessibilityText() = gallery(false, AppVisualStyle.CLEAN_BLUE, "clean-covers-small-large-text", 1.6f)

    private fun gallery(night: Boolean, style: AppVisualStyle, name: String, scale: Float = 1f) {
        rule.setContent {
            FlowTheme(if (night) AppThemeMode.DARK else AppThemeMode.LIGHT, visualStyle = style) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        titles.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                pair.forEachIndexed { index, title ->
                                    BookCover(
                                        title, Modifier.weight(1f).aspectRatio(2f / 3f),
                                        author = if (index == 0) "未知作者" else "东方快鸟"
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(24, 48, 64).forEach { width ->
                                BookCover("缩略图", Modifier.size(width.dp, (width * 1.5f).dp), showTitleOnFallback = false)
                            }
                        }
                    }
                }
            }
        }
        titles.forEach { title ->
            rule.onNodeWithContentDescription(rule.activity.getString(com.flowreader.core.R.string.flow_cover_description, title)).assertIsDisplayed()
        }
        rule.onRoot().captureRoboImage("build/reports/0.3.2/$name.png")
    }

    @Ignore("Coil file loading does not complete reliably with the Robolectric PAUSED looper; verify the unchanged pipeline on a device")
    @Test fun anActualCoverFileStillTakesPrecedenceOverTheGeneratedArtwork() {
        val file = File(rule.activity.cacheDir, "real-cover-fixture.png")
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(22, 142, 106))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val previousLoader = Coil.imageLoader(rule.activity)
        val loaded = AtomicReference<coil.request.SuccessResult>()
        val softwareLoader = ImageLoader.Builder(rule.activity).allowHardware(false).eventListener(object : coil.EventListener {
            override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                loaded.set(result)
            }
        }).build()
        Coil.setImageLoader(softwareLoader)
        try {
            rule.setContent {
                FlowTheme {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                        BookCover("真实封面优先", Modifier.size(140.dp, 210.dp), coverPath = file.absolutePath)
                    }
                }
            }
            val description = rule.activity.getString(com.flowreader.core.R.string.flow_cover_description, "真实封面优先")
            rule.waitUntil(10_000) { loaded.get() != null }
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(file, loaded.get().request.data)
            assertFalse(rule.onNodeWithContentDescription(description).fetchSemanticsNode().config.isClearingSemantics)
            val pixels = loaded.get().drawable.toBitmap()
            assertEquals(android.graphics.Color.rgb(22, 142, 106), pixels.getPixel(pixels.width / 2, pixels.height / 2))
        } finally {
            Coil.setImageLoader(previousLoader)
            softwareLoader.shutdown()
        }
    }
}
