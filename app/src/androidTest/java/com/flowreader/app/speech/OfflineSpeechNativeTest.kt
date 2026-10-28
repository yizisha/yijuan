package com.flowreader.app.speech

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flowreader.app.data.repository.SettingsRepositoryImpl
import com.flowreader.app.util.OfflineSpeechCatalog
import com.flowreader.app.util.OfflineSpeechModels
import com.flowreader.app.util.TtsManager
import com.flowreader.app.util.SpeechDownloadSource
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only against the separate .speechcheck application; never the user's reader package. */
@RunWith(AndroidJUnit4::class)
class OfflineSpeechNativeTest {
    @Test fun kokoroActuallyCompletesPlaybackWithTwoChineseVoices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assume.assumeTrue(context.packageName == "com.flowreader.app.speechcheck")
        val fixture = File(context.cacheDir, "kokoro-fixture.tar.bz2")
        org.junit.Assume.assumeTrue("Push the official Kokoro archive into the isolated test app cache", fixture.isFile)
        val models = OfflineSpeechModels(context)
        val model = OfflineSpeechCatalog.models.first { it.kokoro }
        models.import(model, Uri.fromFile(fixture))
        withTimeout(120_000) { models.state.first { it.busyId == null } }
        assertTrue(models.state.value.message.orEmpty(), model.id in models.state.value.installed)
        models.choose(model.id)
        val settings = SettingsRepositoryImpl(context)
        settings.updateSpeechEnabled(true)
        val manager = TtsManager(context, settings, models)
        val playbackMetrics = org.json.JSONArray()
        try {
            withTimeout(10_000) { manager.loaded.first { it } }
            for (voice in listOf(3, 102)) {
                val playbackStartedAt = android.os.SystemClock.elapsedRealtime()
                manager.setOfflineVoice(voice, 1f)
                val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
                var heard = false
                val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    manager.state.collect { state ->
                        if (state.error != null) finished.completeExceptionally(AssertionError(state.error))
                        if (state.isReady && state.isSpeaking) heard = true
                        if (heard && !state.isSpeaking) finished.complete(Unit)
                    }
                }
                try {
                    manager.speak("你好。")
                    withTimeout(180_000) { finished.await() }
                    assertTrue("No audio playback state for voice $voice", heard)
                    playbackMetrics.put(JSONObject().put("voice", voice)
                        .put("completedPlayback", true)
                        .put("elapsedMillis", android.os.SystemClock.elapsedRealtime() - playbackStartedAt))
                } finally { watcher.cancel() }
            }
            File(context.filesDir, "kokoro-playback.json").writeText(JSONObject()
                .put("package", context.packageName).put("abi", android.os.Build.SUPPORTED_ABIS[0])
                .put("modelSha256", model.sha256).put("playback", playbackMetrics).toString())
        } finally { manager.shutdown() }
    }
    @Test fun httpsMirrorDownloadsAndVerifiesInsideTheApplication() = runBlocking {
        val original = ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assume.assumeTrue("Use -PspeechSmoke=true for the isolated speech fixture", original.packageName == "com.flowreader.app.speechcheck")
        val isolated = File(original.noBackupFilesDir, "network-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(original) {
            override fun getNoBackupFilesDir() = isolated
            override fun getApplicationContext(): Context = this
        }
        val models = OfflineSpeechModels(context)
        val model = OfflineSpeechCatalog.models.first()
        val start = android.os.SystemClock.elapsedRealtime()
        models.download(model, SpeechDownloadSource.GHFAST)
        withTimeout(150_000) { models.state.first { it.busyId == null } }
        File(original.filesDir, "speech-download.json").writeText(JSONObject()
            .put("source", SpeechDownloadSource.GHFAST.url(model)).put("message", models.state.value.message)
            .put("installed", model.id in models.state.value.installed)
            .put("elapsedMillis", android.os.SystemClock.elapsedRealtime() - start).put("sha256", model.sha256).toString())
        assertTrue(models.state.value.message.orEmpty(), model.id in models.state.value.installed)
        models.verifyFiles(model) {}
    }
    @Test fun realJniModelImportSynthesisAndPlaybackLifecycle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assume.assumeTrue("Use -PspeechSmoke=true for the isolated speech fixture", context.packageName == "com.flowreader.app.speechcheck")
        val fixture = File(context.cacheDir, "xiaoya.tar.bz2")
        InstrumentationRegistry.getInstrumentation().context.assets.open("xiaoya.tar.bz2").use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val models = OfflineSpeechModels(context)
        val model = OfflineSpeechCatalog.models.first()
        models.import(model, Uri.fromFile(fixture))
        withTimeout(90_000) { models.state.first { it.busyId == null } }
        assertTrue(models.state.value.message.orEmpty(), model.id in models.state.value.installed)
        models.choose(model.id)
        val folder = models.folder(model)
        val native = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
            model = File(folder, model.modelFile).path, lexicon = File(folder, "lexicon.txt").path, tokens = File(folder, "tokens.txt").path
        ), numThreads = 2), ruleFsts = listOf("phone.fst", "date.fst", "number.fst").joinToString(",") { File(folder, it).path }))
        val start = android.os.SystemClock.elapsedRealtime()
        val audio = native.generate("欢迎使用页间。故事从这里开始。", 0, 1f)
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        assertEquals(22050, audio.sampleRate)
        assertTrue(audio.samples.size > 1000)
        assertTrue(audio.samples.any { kotlin.math.abs(it) > .001f })
        val wav = File(context.filesDir, "speech-native.wav")
        assertTrue(audio.save(wav.path))
        File(context.filesDir, "speech-native.json").writeText(JSONObject()
            .put("package", context.packageName).put("model", model.id).put("sampleRate", audio.sampleRate)
            .put("samples", audio.samples.size).put("durationSeconds", audio.samples.size.toDouble() / audio.sampleRate)
            .put("generateMillis", elapsed).put("abi", android.os.Build.SUPPORTED_ABIS[0]).toString())
        native.release()
        val settings = SettingsRepositoryImpl(context)
        settings.updateSpeechEnabled(false)
        val manager = TtsManager(context, settings, models)
        try {
            withTimeout(10_000) { manager.loaded.first { it } }
            assertFalse(manager.enabled.value)
            assertTrue(manager.setEnabled(true).isSuccess)
            manager.speak("欢迎使用页间，故事从这里开始。")
            withTimeout(30_000) { manager.state.first { it.isReady && it.isSpeaking || it.error != null } }
            assertNull(manager.state.value.error)
            manager.pause()
            assertFalse(manager.state.value.isSpeaking)
            manager.resumeOffline()
            withTimeout(45_000) { manager.state.first { it.isReady && !it.isSpeaking || it.error != null } }
            assertNull(manager.state.value.error)
            manager.stop()
            assertTrue(manager.setEnabled(false).isSuccess)
            assertFalse(manager.enabled.value)
        } finally { manager.shutdown() }
    }
}
