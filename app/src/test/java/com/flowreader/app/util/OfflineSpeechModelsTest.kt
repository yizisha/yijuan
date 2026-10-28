package com.flowreader.app.util

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineSpeechModelsTest {
    @Test fun aPendingDownloadDoesNotBlockSavingPlaybackPreferences() = runBlocking {
        val context = isolated()
        val models = OfflineSpeechModels(context)
        val root = File(context.noBackupFilesDir, "offline-speech")
        val stale = File(root, "download-00000000-0000-0000-0000-000000000000.partial").apply { writeText("interrupted") }
        val retained = File(root, "other.txt").apply { writeText("keep") }
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        models.begin(OfflineSpeechCatalog.models.first()) {
            entered.complete(Unit)
            gate.await()
        }
        try {
            withTimeout(5000) { entered.await() }
            assertFalse(stale.exists())
            assertEquals("keep", retained.readText())
            withTimeout(5000) { models.choose(null, rate = 1.4f) }
            assertEquals(1.4f, models.state.value.rate)
            assertNotNull(models.state.value.busyId)
        } finally {
            models.cancelDownload()
            finished(models)
        }
    }
    private fun isolated(): Context {
        val original = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(original.cacheDir, "speech-model-test-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(original) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
    }
    private fun packageFile() = File(System.getProperty("speech.fixture.dir"), "xiaoya.tar.bz2").also {
        org.junit.Assume.assumeTrue("Set speech.fixture.dir to verified publisher archives", it.isFile)
    }
    private suspend fun finished(models: OfflineSpeechModels) = withTimeout(60_000) { models.state.first { it.busyId == null } }
    @Test fun freshInstallHasNoModelAndDoesNotCreateBookDatabase() {
        val context = isolated()
        val models = OfflineSpeechModels(context)
        assertTrue(models.state.value.installed.isEmpty())
        assertNull(models.state.value.selected)
        assertFalse(context.noBackupFilesDir.walk().any { it.extension == "onnx" })
    }
    @Test fun realPublisherPackageCanBeImportedChosenVerifiedAndReopened() = runBlocking {
        val context = isolated()
        val models = OfflineSpeechModels(context)
        val model = OfflineSpeechCatalog.models.first()
        models.import(model, Uri.fromFile(packageFile()))
        val state = finished(models)
        assertTrue(state.message.orEmpty(), model.id in state.installed)
        models.verifyFiles(model) {}
        models.choose(model.id, rate = 1.3f)
        val reopened = OfflineSpeechModels(context)
        assertEquals(model.id, reopened.state.value.selected)
        assertEquals(1.3f, reopened.state.value.rate)
        assertTrue(model.id in reopened.state.value.installed)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { models.remove(model) } }
        models.choose(null)
        models.remove(model)
        assertTrue(models.state.value.installed.isEmpty())
        assertFalse(models.folder(model).exists())
    }
    @Test fun failedImportKeepsTheWorkingInstallationAndSelection() = runBlocking {
        val context = isolated()
        val models = OfflineSpeechModels(context)
        val good = OfflineSpeechCatalog.models.first()
        models.import(good, Uri.fromFile(packageFile()))
        assertTrue(good.id in finished(models).installed)
        models.choose(good.id)
        val bad = File(context.noBackupFilesDir, "bad.tar.bz2").apply { writeText("<html>not a model</html>") }
        models.import(OfflineSpeechCatalog.models[1], Uri.fromFile(bad))
        val state = finished(models)
        assertEquals(good.id, state.selected)
        assertEquals(setOf(good.id), state.installed)
        assertTrue(state.message.orEmpty().contains("校验失败"))
        models.verifyFiles(good) {}
    }
    @Test fun corruptedWeightIsRejectedBeforeNativeCodeRuns() = runBlocking {
        val models = OfflineSpeechModels(isolated())
        val model = OfflineSpeechCatalog.models.first()
        models.import(model, Uri.fromFile(packageFile()))
        assertTrue(model.id in finished(models).installed)
        File(models.folder(model), model.modelFile).appendText("corruption")
        assertThrows(IllegalArgumentException::class.java) { models.verifyFiles(model) {} }
        Unit
    }
    @Test fun cancellationLeavesNoSelectedOrInstalledPartialModel() = runBlocking {
        val models = OfflineSpeechModels(isolated())
        val model = OfflineSpeechCatalog.models.first()
        models.import(model, Uri.fromFile(packageFile()))
        models.cancelDownload()
        finished(models)
        assertNull(models.state.value.selected)
        assertTrue(models.state.value.installed.isEmpty())
        assertFalse(File(models.folder(model), ".complete").exists())
    }
    @Test fun missingModelCannotBeSelectedAndDoesNotOverwriteChoices() = runBlocking {
        val models = OfflineSpeechModels(isolated())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { models.choose("kokoro") } }
        assertNull(models.state.value.selected)
    }
    @Test fun otherCatalogPackagesCanBeImportedAndVerified() = runBlocking {
        for (model in OfflineSpeechCatalog.models.drop(1)) {
            val models = OfflineSpeechModels(isolated())
            val name = if (model.id == "chaowen") "chaowen.tar.bz2" else "kokoro-int8-multi-lang-v1_1.tar.bz2"
            val fixture = File(System.getProperty("speech.fixture.dir"), name)
            org.junit.Assume.assumeTrue("Set speech.fixture.dir to verified publisher archives", fixture.isFile)
            models.import(model, Uri.fromFile(fixture))
            withTimeout(180_000) { models.state.first { it.busyId == null } }
            assertTrue(models.state.value.message.orEmpty(), model.id in models.state.value.installed)
            models.verifyFiles(model) {}
            models.choose(model.id)
            assertEquals(model.id, models.state.value.selected)
        }
    }
}
