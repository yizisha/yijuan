package com.flowreader.app.util

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test

class OfflineSpeechArchiveTest {
    @Test fun nativePcmCallbackExposesTheExactTypedJniMethod() {
        val callback = SpeechPcmCallback { samples -> if (samples.isNotEmpty()) 1 else 0 }
        val method = callback.javaClass.getMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
        assertEquals(1, method.invoke(callback, floatArrayOf(.25f)))
        assertEquals(0, method.invoke(callback, floatArrayOf()))
    }
    private val base = OfflineSpeechCatalog.models.first()
    private fun tar(entries: List<Pair<String, ByteArray>>, link: String? = null): File {
        val file = Files.createTempFile("speech-test-", ".tar.bz2").toFile()
        BZip2CompressorOutputStream(file.outputStream()).use { bzip ->
            TarArchiveOutputStream(bzip).use { output ->
                entries.forEach { (name, bytes) ->
                    val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                    output.putArchiveEntry(entry); output.write(bytes); output.closeArchiveEntry()
                }
                if (link != null) {
                    val entry = TarArchiveEntry("${base.directory}/link", '2'.code.toByte()).apply { linkName = link }
                    output.putArchiveEntry(entry); output.closeArchiveEntry()
                }
            }
        }
        return file
    }
    private fun files() = listOf(base.modelFile, "tokens.txt", "lexicon.txt").map { "${base.directory}/$it" to byteArrayOf(1, 2, 3) }
    private fun extract(file: File, model: OfflineSpeechModel = base.copy(expandedBytes = 9)): File {
        val staging = Files.createTempDirectory("speech-stage-").toFile()
        try { OfflineSpeechArchive.extract(file, staging, model) } catch (error: Exception) { staging.deleteRecursively(); throw error }
        return staging
    }
    @Test fun validArchiveKeepsOnlyFilesInsideTheStagingDirectory() {
        val file = tar(files())
        val output = extract(file)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(output, "${base.directory}/${base.modelFile}").readBytes())
        output.deleteRecursively(); file.delete()
    }
    @Test fun traversalWrongRootAndDuplicateEntriesAreRejected() {
        for (entry in listOf("${base.directory}/../escape", "other/tokens.txt", "${base.directory}/${base.modelFile}")) {
            val file = tar(files() + (entry to byteArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) { extract(file) }
            file.delete()
        }
    }
    @Test fun symbolicLinksAndOversizedPayloadsAreRejected() {
        val link = tar(files(), "../../escape")
        assertThrows(IllegalArgumentException::class.java) { extract(link) }
        val large = tar(files() + ("${base.directory}/extra" to ByteArray(20)))
        assertThrows(IllegalArgumentException::class.java) { extract(large) }
        link.delete(); large.delete()
    }
    @Test fun truncationAndMissingRequiredFilesAreRejected() {
        val file = tar(files().drop(1))
        assertThrows(IllegalArgumentException::class.java) { extract(file, base.copy(expandedBytes = 6)) }
        file.delete()
    }
    @Test fun boundedCopyStopsBeforeWritingOversizedDataAndPropagatesCancellation() {
        val file = Files.createTempFile("speech-copy-", ".tmp").toFile()
        assertThrows(IllegalArgumentException::class.java) { OfflineSpeechArchive.copy(ByteArrayInputStream(ByteArray(30)), file, 10) }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            OfflineSpeechArchive.copy(ByteArrayInputStream(byteArrayOf(1)), file, 5, { throw java.util.concurrent.CancellationException() })
        }
        file.delete()
    }
    @Test fun chunksPreserveEveryCharacterAndNeverSplitSurrogatePairs() {
        val text = "读书😀".repeat(150) + "。下一句！结尾"
        var offset = 0
        val result = StringBuilder()
        while (offset < text.length) {
            val end = SpeechTextChunks.end(text, offset, 17)
            assertTrue(end > offset && end - offset <= 17)
            assertFalse(end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate())
            result.append(text.substring(offset, end)); offset = end
        }
        assertEquals(text, result.toString())
    }
    @Test fun catalogMirrorsPinTheSamePublisherArchiveAndDefaultHasNoModelWeights() {
        assertEquals(3, OfflineSpeechCatalog.models.size)
        OfflineSpeechCatalog.models.forEach { model ->
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            SpeechDownloadSource.entries.forEach { source -> assertTrue(source.url(model).endsWith(model.origin)) }
            assertTrue(model.expandedBytes > model.bytes)
        }
        assertEquals(100, OfflineSpeechCatalog.find("kokoro")!!.voiceIds.size)
        assertEquals(3, SpeechTextChunks.end("你好。下句", 0))
    }
}
