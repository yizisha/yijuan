package com.flowreader.app.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.flowreader.app.core.designsystem.theme.FlowTheme
import com.flowreader.app.domain.model.*
import com.flowreader.app.domain.repository.BackupPreview
import com.flowreader.app.domain.repository.RestoreMode
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.app.ui.screens.settings.*
import com.github.takahirom.roborazzi.captureRoboImage
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupVisualTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        rule.waitForIdle()
        val file = File("build/0.3.5-preview/$name.png").apply { parentFile!!.mkdirs() }
        rule.onRoot().captureRoboImage(file.absolutePath)
    }
    private fun preferences(): SettingsViewModel {
        val repository = mockk<SettingsRepository>(relaxed = true)
        every { repository.appSettings } returns MutableStateFlow(AppSettings())
        every { repository.getDailyReadingGoal() } returns MutableStateFlow(30)
        every { repository.getWeeklyReadingGoal() } returns MutableStateFlow(210)
        every { repository.getMonthlyReadingGoal() } returns MutableStateFlow(900)
        every { repository.isOnboardingCompleted() } returns MutableStateFlow(true)
        every { repository.getCustomFontPath() } returns MutableStateFlow(null)
        every { repository.getLibraryViewMode() } returns MutableStateFlow(LibraryViewMode.GRID)
        return SettingsViewModel(rule.activity.applicationContext, repository, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    }
    @Test fun dataPageKeepsGroupedBackupAndPrivacyControls() {
        val model = preferences()
        rule.setContent { FlowTheme(themeMode = AppThemeMode.LIGHT) { DataSettingsScreen({}, model) } }
        rule.onNodeWithText("完整备份").assertExists()
        rule.onNodeWithText("本地自动备份").assertExists()
        capture("backup-settings")
    }
    private val preview = BackupPreview("sample", 1791936000000L, 12, 5, 8, 3, 156, 2, true, false)
    @Test fun replacementCannotRunWithoutExplicitConfirmationAndMergeIsDefault() {
        var called: RestoreMode? = null
        rule.setContent { FlowTheme(themeMode = AppThemeMode.LIGHT) { BackupRestoreDialog(preview, {}, { mode, _ -> called = mode }) } }
        capture("restore-preview")
        rule.onNodeWithText("替换当前书库").performClick()
        rule.onNodeWithText("开始恢复").assertIsNotEnabled()
        rule.onNodeWithText("我已确认替换当前书库").performClick()
        rule.onNodeWithText("开始恢复").assertIsEnabled().performClick()
        assertEquals(RestoreMode.REPLACE, called)
    }
    @Test fun nightRestoreKeepsExistingWarmColors() {
        rule.setContent { FlowTheme(themeMode = AppThemeMode.DARK) { BackupRestoreDialog(preview, {}, { _, _ -> }) } }
        capture("restore-preview-dark")
    }
    @Test fun restrictedSharedLocationExplainsItsLimitAndOffersSavingACopy() {
        rule.setContent {
            FlowTheme(themeMode = AppThemeMode.LIGHT) {
                androidx.compose.material3.Surface {
                    com.flowreader.app.ui.screens.bookdetail.BookFileLocationContent(
                        readingPath = "/data/user/0/com.flowreader.app/files/books/书籍.txt",
                        originalLocation = "内部存储/Android/data/nu.gpu.nagram/files/Telegram/Telegram Files/书籍.txt",
                        savedCopyLocation = null, loaded = true, busy = false, message = null, canOpenOriginal = false,
                        originalDirectoryMessage = "共享文件只授予文件读取权限，没有可浏览的父目录。可保存副本到下载或书籍目录。",
                        onCopy = {}, onOpenOriginal = {}, onOpenSavedCopy = {}, onAssociate = {}, onSaveCopy = {}
                    )
                }
            }
        }
        rule.onNodeWithText("打开所在目录").assertDoesNotExist()
        rule.onNodeWithText("保存副本到本地").assertIsEnabled()
        capture("restricted-file-location")
    }
}
