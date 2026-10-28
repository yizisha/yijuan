package com.flowreader.app.util

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.flowreader.app.data.repository.SettingsRepositoryImpl
import com.flowreader.app.domain.model.AppSettings
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.plugin.api.SpeechPlugin
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechComponentLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val stored = MutableStateFlow(AppSettings())
    private val repository = mockk<SettingsRepository>(relaxed = true)
    private val engine = FakeSpeech()
    private val hosts = mutableListOf<TtsManager>()
    private var instances = 0
    private fun manager() = TtsManager(context, repository) { instances++; engine }.also { hosts += it }

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.appSettings } returns stored
        coEvery { repository.updateSpeechEnabled(any()) } coAnswers { stored.value = stored.value.copy(speechEnabled = firstArg()) }
    }
    @After fun cleanup() { hosts.forEach { it.dispose() }; Dispatchers.resetMain() }

    @Test fun defaultDisabledNeverConstructsOrAttachesAnEngine() = runTest(dispatcher) {
        val host = manager()
        runCurrent()
        assertTrue(host.loaded.value)
        assertFalse(host.enabled.value)
        host.speak("chapter")
        assertEquals(0, instances)
        assertEquals(0, engine.attached)
        assertEquals("请先在设置 → 应用中开启听书", host.state.value.error)
    }
    @Test fun enablingOnlyPersistsAndPlaybackStartsOnDemand() = runTest(dispatcher) {
        val host = manager()
        runCurrent()
        assertTrue(host.setEnabled(true).isSuccess)
        runCurrent()
        assertTrue(stored.value.speechEnabled)
        assertTrue(host.enabled.value)
        assertEquals(0, instances)
        host.speak("current chapter")
        runCurrent()
        assertEquals(1, instances)
        assertEquals("current chapter", engine.spoken)
        assertTrue(host.state.value.isSpeaking)
    }
    @Test fun disablingStopsAndReleasesPlaybackImmediately() = runTest(dispatcher) {
        val host = manager()
        runCurrent()
        host.setEnabled(true)
        host.speak("chapter")
        runCurrent()
        assertTrue(host.setEnabled(false).isSuccess)
        runCurrent()
        assertFalse(host.enabled.value)
        assertEquals(1, engine.released)
        assertEquals(TtsState(), host.state.value)
        host.speak("blocked")
        assertEquals(1, instances)
    }
    @Test fun failedSaveKeepsEnabledPlaybackAndReportsFeedback() = runTest(dispatcher) {
        val host = manager()
        runCurrent()
        host.setEnabled(true)
        host.speak("keep reading")
        runCurrent()
        coEvery { repository.updateSpeechEnabled(false) } throws java.io.IOException("disk full")
        assertTrue(host.setEnabled(false).isFailure)
        assertTrue(host.enabled.value)
        assertTrue(host.state.value.isSpeaking)
        assertEquals(0, engine.released)
        assertTrue(host.feedback.value!!.contains("disk full"))
        assertFalse(host.saving.value)
    }
    @Test fun lateCallbacksAfterDisablingAreIgnored() = runTest(dispatcher) {
        val host = manager()
        runCurrent()
        host.setEnabled(true)
        host.speak("chapter")
        runCurrent()
        val oldCallback = engine.listener!!
        host.setEnabled(false)
        oldCallback.onState(true, true, "late")
        runCurrent()
        assertEquals(TtsState(), host.state.value)
    }
    @Test fun reopeningUsesTheStoredEnableFlagWithoutStartingSpeech() = runTest(dispatcher) {
        val host = manager()
        runCurrent()
        host.setEnabled(true)
        host.dispose()
        val reopened = manager()
        runCurrent()
        assertTrue(reopened.enabled.value)
        assertEquals(0, instances)
    }
    @Test fun oldPluginInstallationRecordIsIgnoredAndNoFilesAreDeleted() = runTest(dispatcher) {
        val file = File(context.noBackupFilesDir, "speech-component/installed").apply { parentFile!!.mkdirs(); writeText("old installation") }
        val host = manager()
        runCurrent()
        assertFalse(host.enabled.value)
        host.setEnabled(true)
        host.speak("built in")
        runCurrent()
        assertEquals("built in", engine.spoken)
        assertEquals("old installation", file.readText())
    }
    @Test fun realPreferenceWritesSurviveRepositoryRecreationAndKeepReadingSettings() = runTest(dispatcher) {
        val directory = File(context.cacheDir, "builtin-speech-settings").apply { mkdirs() }
        val preferenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val isolated = object : ContextWrapper(context), com.flowreader.app.data.repository.SettingsDataStoreOwner {
            override val settingsDataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = preferenceScope) {
                File(filesDir, "datastore/settings.preferences_pb")
            }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
        }
        val real = SettingsRepositoryImpl(isolated)
        assertFalse(real.appSettings.first().speechEnabled)
        val before = real.appSettings.first().defaultReadingSettings
        real.updateSpeechEnabled(true)
        assertTrue(SettingsRepositoryImpl(isolated).appSettings.first().speechEnabled)
        assertEquals(before, real.appSettings.first().defaultReadingSettings)
        assertTrue(File(directory, "datastore/settings.preferences_pb").isFile)
        real.updateSpeechEnabled(false)
        assertFalse(SettingsRepositoryImpl(isolated).appSettings.first().speechEnabled)
        preferenceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private class FakeSpeech : SpeechPlugin {
        var attached = 0
        var released = 0
        var spoken: String? = null
        var listener: SpeechPlugin.Listener? = null
        override fun attach(context: Context, listener: SpeechPlugin.Listener) { attached++; this.listener = listener; listener.onState(true, false, null) }
        override fun speak(text: String) { spoken = text; listener?.onState(true, true, null) }
        override fun pause() { listener?.onState(true, false, null) }
        override fun stop() = pause()
        override fun shutdown() { released++; listener = null }
        override fun createControls(context: Context, foreground: Int, secondary: Int, accent: Int, requestContent: Runnable): View = View(context)
        override fun detachControls() = Unit
    }
}
