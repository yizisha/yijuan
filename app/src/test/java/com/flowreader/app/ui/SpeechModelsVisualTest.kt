package com.flowreader.app.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.domain.model.AppSettings
import com.flowreader.app.domain.model.AppThemeMode
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.app.ui.screens.settings.SpeechControlsSheet
import com.flowreader.app.ui.screens.settings.SpeechModelsSheet
import com.flowreader.app.ui.screens.settings.SpeechSettingsRow
import com.flowreader.app.ui.screens.settings.SpeechViewModel
import com.flowreader.app.util.OfflineSpeechCatalog
import com.flowreader.app.util.OfflineSpeechModels
import com.flowreader.app.util.TtsManager
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class SpeechModelsVisualTest {
    @Test fun selectedModelShowsPreparationAndPlaybackErrorsInTheModelSheet() = runBlocking {
        val real = host(true)
        val models = real.manager.models!!
        models.import(OfflineSpeechCatalog.models.first(), Uri.fromFile(fixture()))
        withTimeout(60_000) { models.state.first { it.busyId == null } }
        models.choose("xiaoya")
        val playback = MutableStateFlow(com.flowreader.app.util.TtsState(isSpeaking = true))
        val manager = mockk<TtsManager>(relaxed = true)
        every { manager.models } returns models
        every { manager.enabled } returns MutableStateFlow(true)
        every { manager.feedback } returns MutableStateFlow(null)
        every { manager.state } returns playback
        rule.setContent { FlowTheme(themeMode = AppThemeMode.LIGHT) { SpeechModelsSheet({}, SpeechViewModel(manager)) } }
        rule.onNodeWithText("正在加载或合成首句，请稍候…").assertIsDisplayed()
        rule.onNodeWithText("停止试听").assertIsDisplayed()
        capture("preview-preparing")
        rule.runOnIdle { playback.value = com.flowreader.app.util.TtsState(error = "离线朗读失败：测试音频输出错误") }
        rule.onNodeWithText("离线朗读失败：测试音频输出错误").assertIsDisplayed()
        capture("preview-error")
        real.manager.dispose()
    }
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun fixture() = File(System.getProperty("speech.fixture.dir"), "xiaoya.tar.bz2").also {
        org.junit.Assume.assumeTrue("Set speech.fixture.dir to verified publisher archives", it.isFile)
    }
    private fun host(enabled: Boolean): SpeechViewModel {
        val directory = File(rule.activity.cacheDir, "models-visual-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(rule.activity) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        val repo = mockk<SettingsRepository>(relaxed = true)
        every { repo.appSettings } returns MutableStateFlow(AppSettings(speechEnabled = enabled))
        return SpeechViewModel(TtsManager(context, repo, OfflineSpeechModels(context)))
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val file = File("build/0.3.8-preview/$name.png").apply { parentFile!!.mkdirs() }
        rule.onNode(isRoot() and SemanticsMatcher("visible root") { it.boundsInRoot.width > 0 }).captureRoboImage(file.absolutePath)
    }
    @Test fun lightModelPickerHasDirectDownloadAndSourceSelection() = picker(false, "models-light")
    @Test fun darkModelPickerKeepsTheExistingWarmPalette() = picker(true, "models-dark")
    private fun picker(dark: Boolean, name: String) {
        val viewModel = host(false)
        rule.setContent { FlowTheme(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) { SpeechModelsSheet({}, viewModel) } }
        rule.onAllNodesWithText("下载").onFirst().assertIsEnabled()
        rule.onNodeWithText("小雅 · 轻量中文").assertIsDisplayed()
        capture(name)
        rule.onNodeWithText("下载源 · 镜像 · ghfast.top⌄").performClick()
        rule.onNodeWithText("官方 · GitHub").assertIsDisplayed().performClick()
        rule.onNodeWithText("下载源 · 官方 · GitHub⌄").assertIsDisplayed()
        viewModel.manager.dispose()
    }
    @Test fun downloadedModelControlsHavePlaybackAndSpeedWithoutNativeInitialization() = runBlocking {
        val viewModel = host(true)
        val models = viewModel.manager.models!!
        models.import(OfflineSpeechCatalog.models.first(), Uri.fromFile(fixture()))
        withTimeout(60_000) { models.state.first { it.busyId == null } }
        assertTrue(models.state.value.message.orEmpty(), "xiaoya" in models.state.value.installed)
        models.choose("xiaoya")
        rule.setContent { FlowTheme(themeMode = AppThemeMode.LIGHT) { SpeechControlsSheet({}, {}, viewModel) } }
        rule.onNodeWithText("小雅 · 轻量中文").assertIsDisplayed()
        rule.onNodeWithText("开始朗读").assertIsDisplayed()
        rule.onNodeWithText("停止").assertIsDisplayed()
        capture("offline-controls")
        viewModel.manager.dispose()
    }
    @Test fun modelManagementIsAvailableEvenWhenSpeechIsOff() {
        val viewModel = host(false)
        rule.setContent { FlowTheme(themeMode = AppThemeMode.LIGHT) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { SpeechSettingsRow(viewModel) }
        } }
        rule.onNode(isToggleable()).assertIsOff()
        rule.onNodeWithText("语音模型").assertIsDisplayed()
        capture("speech-settings")
        rule.onNodeWithText("语音模型").performClick()
        rule.onNodeWithText("小雅 · 轻量中文").assertIsDisplayed()
        viewModel.manager.dispose()
    }
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w320dp-h640dp-320dpi")
    fun narrowScreenWithLargeTextKeepsInstalledModelActionsReachable() = runBlocking {
        val viewModel = host(false)
        val models = viewModel.manager.models!!
        models.import(OfflineSpeechCatalog.models.first(), Uri.fromFile(fixture()))
        withTimeout(60_000) { models.state.first { it.busyId == null } }
        assertTrue("xiaoya" in models.state.value.installed)
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                FlowTheme(themeMode = AppThemeMode.LIGHT) { SpeechModelsSheet({}, viewModel) }
            }
        }
        rule.onNodeWithText("删除").performScrollTo().assertIsDisplayed()
        val bounds = rule.onNodeWithText("删除").fetchSemanticsNode().boundsInRoot
        val right = rule.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.boundsInRoot.right }
        assertTrue(bounds.left >= 0 && bounds.right <= right)
        capture("models-narrow-large-text")
        viewModel.manager.dispose()
    }
}
