package com.flowreader.app.util

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.flowreader.plugin.tts.SystemSpeechPlugin
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemSpeechPluginTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var plugin: SystemSpeechPlugin
    private val events = mutableListOf<Triple<Boolean, Boolean, String?>>()
    @Before fun setup() {
        ShadowTextToSpeech.reset()
        ShadowTextToSpeech.addLanguageAvailability(context.resources.configuration.locales[0])
        plugin = SystemSpeechPlugin()
        plugin.attach(context) { ready, speaking, error -> events += Triple(ready, speaking, error) }
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun longTextContinuesPastTheOld3800CharacterCutoff() {
        val content = "A".repeat(8001)
        plugin.speak(content)
        shadowOf(Looper.getMainLooper()).idle()
        val spoken = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).spokenTextList
        assertEquals(content, spoken.joinToString(""))
        assertTrue(spoken.all { it.length <= 3800 })
        assertFalse(events.last().second)
    }
    @Test fun chunksDoNotSplitEmojiSurrogates() {
        val content = "A".repeat(3799) + "😀" + "B".repeat(4000)
        plugin.speak(content)
        shadowOf(Looper.getMainLooper()).idle()
        val spoken = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).spokenTextList
        assertEquals(content, spoken.joinToString(""))
        assertTrue(spoken.none { Character.isHighSurrogate(it.last()) })
    }
    @Test fun chineseTextSelectsChineseVoiceAndUsesShortContinuousChunks() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        val content = "中文阅读测试。".repeat(300)
        plugin.speak(content)
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertEquals("zh", shadow.currentLanguage.language)
        assertEquals(content, shadow.spokenTextList.joinToString(""))
        assertTrue(shadow.spokenTextList.all { it.length <= 600 })
    }
    @Test fun availableOfflineVoiceIsPreferredOverNetworkVoice() {
        val locale = context.resources.configuration.locales[0]
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("network", locale, 500, 100, true, emptySet()))
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("local", locale, 300, 100, false, emptySet()))
        plugin.shutdown()
        plugin = SystemSpeechPlugin()
        plugin.attach(context) { ready, playing, error -> events += Triple(ready, playing, error) }
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("local", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).currentVoice.name)
    }
    @Test fun installedChineseRegionalVoiceWorksEvenWhenDefaultChineseLocaleIsUnavailable() {
        ShadowTextToSpeech.addVoice(android.speech.tts.Voice("zh-local", Locale.TAIWAN, 300, 100, false, emptySet()))
        plugin.speak("这是中文正文。")
        shadowOf(Looper.getMainLooper()).idle()
        val engine = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertEquals("zh-local", engine.currentVoice.name)
        assertEquals("这是中文正文。", engine.lastSpokenText)
        assertNull(events.last().third)
    }
    @Test fun errorIncludesCodeAndReconnectReleasesTheFailedEngine() {
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        val controls = plugin.createControls(context, 0xff222222.toInt(), 0xff666666.toInt(), 0xff28679d.toInt()) { } as LinearLayout
        org.robolectric.util.ReflectionHelpers.setField(plugin, "playing", true)
        org.robolectric.util.ReflectionHelpers.setField(plugin, "activeId", "failure")
        shadowOf(engine).utteranceProgressListener.onError("failure", TextToSpeech.ERROR_NETWORK)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events.last().third!!.contains(TextToSpeech.ERROR_NETWORK.toString()))
        assertFalse(events.last().second)
        val retry = controls.getChildAt(5) as Button
        assertEquals(android.view.View.VISIBLE, retry.visibility)
        retry.performClick()
        assertTrue(shadowOf(engine).isShutdown)
        val restarted = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotSame(engine, restarted)
        shadowOf(restarted).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(events.last().first)
        assertNull(events.last().third)
    }
    @Test fun pauseInvalidatesLateCompletionCallbacks() {
        plugin.speak("A".repeat(8000))
        plugin.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).spokenTextList.size)
        assertFalse(events.last().second)
    }
    @Test fun stopClearsOldChapterAndRequestsCurrentTextOnPlay() {
        plugin.speak("old chapter")
        plugin.stop()
        var requested = false
        val controls = plugin.createControls(context, 0xff222222.toInt(), 0xff666666.toInt(), 0xff28679d.toInt()) { requested = true }
        val buttons = (controls as LinearLayout).getChildAt(3) as LinearLayout
        (buttons.getChildAt(0) as Button).performClick()
        assertTrue(requested)
    }
    @Test fun shutdownStopsAndReleasesSystemEngine() {
        plugin.shutdown()
        assertTrue(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).isShutdown)
        plugin.speak("after shutdown")
        assertNull(shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
    }
    @Config(sdk = [26])
    @Test fun android26UsesTheBundledEngineAndPlaybackControls() {
        val controls = plugin.createControls(context, 0xff222222.toInt(), 0xff666666.toInt(), 0xff28679d.toInt()) { }
        assertTrue(controls is LinearLayout)
        plugin.speak("Android 8 read aloud")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Android 8 read aloud", shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).lastSpokenText)
    }
    @Test fun unsupportedLanguageReportsErrorWithoutPlaying() {
        ShadowTextToSpeech.reset()
        plugin = SystemSpeechPlugin()
        plugin.attach(context) { ready, speaking, error -> events += Triple(ready, speaking, error) }
        plugin.speak("waiting for engine")
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(events.last().first)
        assertFalse(events.last().second)
        assertNotNull(events.last().third)
        plugin.speak("do not queue forever after an initialization failure")
        assertFalse(events.last().second)
        assertNotNull(events.last().third)
        val controls = plugin.createControls(context, 0xff222222.toInt(), 0xff666666.toInt(), 0xff28679d.toInt()) { }
        assertEquals(android.view.View.VISIBLE, (controls as LinearLayout).getChildAt(4).visibility)
    }
}
