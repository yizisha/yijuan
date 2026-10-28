package com.flowreader.app.ui

import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.core.designsystem.reader.ReaderPalettes
import com.flowreader.app.domain.model.AppSettings
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.model.ReaderPaletteId
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.app.ui.screens.reader.components.ReaderControls
import com.flowreader.app.ui.screens.settings.SpeechControlsSheet
import com.flowreader.app.ui.screens.settings.SpeechSettingsRow
import com.flowreader.app.ui.screens.settings.SpeechViewModel
import com.flowreader.app.util.TtsManager
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpeechPluginVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun settingsDefaultOffCanBeEnabledWithoutStartingTheEngine() = settings(false, "settings-off")
    @Test fun nightSettingsKeepTheCurrentWarmCharcoalColors() = settings(true, "settings-off-dark")
    @Test fun builtinPlaybackHasNoDownloadOrImportForm() = playback(false, "speech-controls")
    @Test fun nightPlaybackUsesExistingWarmGold() = playback(true, "speech-controls-dark")
    @Test fun disabledReaderHidesOnlyTheHeadphoneEntry() = header(false, "reader-disabled")
    @Test fun enabledReaderRestoresTheHeadphoneEntry() = header(true, "reader-enabled")

    private fun host(enabled: Boolean): Pair<TtsManager, MutableStateFlow<AppSettings>> {
        ShadowTextToSpeech.reset()
        val stored = MutableStateFlow(AppSettings(speechEnabled = enabled))
        val repository = mockk<SettingsRepository>(relaxed = true)
        every { repository.appSettings } returns stored
        coEvery { repository.updateSpeechEnabled(any()) } coAnswers { stored.value = stored.value.copy(speechEnabled = firstArg()) }
        val manager = TtsManager(rule.activity.applicationContext, repository)
        shadowOf(Looper.getMainLooper()).idle()
        return manager to stored
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val file = File("build/0.3.5-preview/$name.png").apply { parentFile?.mkdirs() }
        rule.onRoot().captureRoboImage(file.absolutePath)
    }
    private fun settings(dark: Boolean, name: String) {
        val (manager, stored) = host(false)
        rule.setContent {
            FlowTheme(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { SpeechSettingsRow(SpeechViewModel(manager)) }
            }
        }
        rule.onNode(isToggleable()).assertIsOff().assertIsEnabled()
        capture(name)
        rule.onNode(isToggleable()).performClick()
        rule.waitForIdle()
        assertTrue(stored.value.speechEnabled)
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        manager.dispose()
    }
    private fun playback(dark: Boolean, name: String) {
        val (manager, _) = host(true)
        ShadowTextToSpeech.addLanguageAvailability(rule.activity.resources.configuration.locales[0])
        rule.setContent {
            FlowTheme(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                SpeechControlsSheet(onDismiss = {}, onRequestContent = { manager.speak("从当前章节开始朗读。") }, viewModel = SpeechViewModel(manager))
            }
        }
        rule.runOnIdle {
            shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        rule.onNodeWithText("听书").assertIsDisplayed()
        rule.onNodeWithText("HTTPS 插件下载地址").assertDoesNotExist()
        rule.onNodeWithText("导入插件").assertDoesNotExist()
        capture(name)
        manager.dispose()
    }
    private fun header(enabled: Boolean, name: String) {
        var taps = 0
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                ReaderControls(visible = true, bookTitle = "页间", chapterTitle = "第一章", currentChapter = 1, totalChapters = 10,
                    progressProvider = { .1f }, isTtsPlaying = false, ttsVisible = enabled, palette = ReaderPalettes.of(ReaderPaletteId.PAPER),
                    chapterTitleAt = { "第一章" }, onBackClick = {}, onPreviousChapterClick = {}, onNextChapterClick = {},
                    onChapterClick = {}, onBookmarkClick = {}, onAddBookmark = {}, onTtsClick = { taps++ },
                    onAnnotationClick = {}, onSearchClick = {}, onImmersiveClick = {}, onShareClick = {},
                    onSettingsClick = {}, onNightModeToggle = {}, onProgressCommit = {}, onDismiss = {})
            }
        }
        rule.mainClock.advanceTimeBy(200)
        val headphones = rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_tts_play))
        if (enabled) { headphones.assertIsDisplayed().performClick(); assertEquals(1, taps) }
        else headphones.assertDoesNotExist()
        rule.onNodeWithText("设置").assertIsDisplayed()
        capture(name)
    }
}
