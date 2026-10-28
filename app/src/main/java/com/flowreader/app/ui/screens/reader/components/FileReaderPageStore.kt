package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.domain.model.SourceRange
import com.flowreader.feature.reader.ChapterPaginator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Disposable, bounded local layouts. Never contains authoritative reading progress or notes. */
class FileReaderPageStore(
    private val directory: File,
    private val maxEntries: Int = 32,
    private val maxBytes: Long = 16L * 1024 * 1024
) : ReaderPageStore {
    private fun file(key: ReaderPageKey) = File(directory, contentFingerprint("layout-v1:$key") + ".pages")

    @Synchronized
    override fun get(key: ReaderPageKey): List<List<ChapterPaginator.Fragment>>? {
        val target = file(key)
        if (!target.isFile) return null
        return runCatching {
            target.inputStream().use { raw ->
                DataInputStream(LimitedInput(BufferedInputStream(GZIPInputStream(raw)), MAX_DECODED_BYTES)).use { input ->
                    require(input.readInt() == MAGIC)
                    val count = input.readInt().also { require(it in 0..MAX_PAGES) }
                    List(count) {
                        val fragmentCount = input.readInt().also { require(it in 1..MAX_FRAGMENTS) }
                        List(fragmentCount) {
                            val text = input.readText()
                            val start = input.readInt().also { require(it >= 0) }
                            val indent = input.readBoolean()
                            val title = input.readBoolean()
                            val heading = input.readBoolean()
                            val rangeCount = input.readInt().also { require(it in 0..MAX_FRAGMENTS) }
                            val ranges = List(rangeCount) { SourceRange(input.readText(), input.readInt(), input.readInt()) }
                            val block = if (input.readBoolean()) input.readText() else null
                            ChapterPaginator.Fragment(text, start, indent, title, heading, ranges, block)
                        }
                    }.also {
                        require(input.read() == -1)
                        target.setLastModified(System.currentTimeMillis())
                    }
                }
            }
        }.getOrElse {
            target.delete()
            null
        }
    }

    @Synchronized
    override fun put(key: ReaderPageKey, pages: List<List<ChapterPaginator.Fragment>>) {
        directory.mkdirs()
        val target = file(key)
        val temporary = File(directory, target.name + ".tmp")
        try {
            require(pages.size <= MAX_PAGES)
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(temporary.outputStream()))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(pages.size)
                pages.forEach { page ->
                    require(page.size in 1..MAX_FRAGMENTS)
                    output.writeInt(page.size)
                    page.forEach { fragment ->
                        output.writeText(fragment.text)
                        output.writeInt(fragment.paragraphStart)
                        output.writeBoolean(fragment.indent)
                        output.writeBoolean(fragment.isTitle)
                        output.writeBoolean(fragment.isHeading)
                        output.writeInt(fragment.sourceRanges.size)
                        fragment.sourceRanges.forEach { range ->
                            output.writeText(range.documentKey)
                            output.writeInt(range.startUtf16)
                            output.writeInt(range.endUtf16Exclusive)
                        }
                        output.writeBoolean(fragment.blockId != null)
                        fragment.blockId?.let { output.writeText(it) }
                    }
                }
            }
            if (temporary.length() > maxBytes) return
            if (!temporary.renameTo(target)) {
                target.delete()
                check(temporary.renameTo(target))
            }
            val files = directory.listFiles { file -> file.name.endsWith(".pages") }.orEmpty().sortedByDescending { it.lastModified() }
            var bytes = 0L
            files.forEachIndexed { index, cached ->
                bytes += cached.length()
                if (index >= maxEntries || bytes > maxBytes) cached.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    private fun DataInputStream.readText(): String {
        val size = readInt().also { require(it in 0..MAX_TEXT_BYTES) }
        // Preserve exact UTF-16 source fragments, including a legacy split between surrogates.
        return CharArray(size) { readChar() }.concatToString()
    }

    private fun DataOutputStream.writeText(text: String) {
        require(text.length <= MAX_TEXT_BYTES)
        writeInt(text.length)
        text.forEach { writeChar(it.code) }
    }

    private class LimitedInput(input: java.io.InputStream, private val maximum: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) checkCount(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, length).also { if (it > 0) checkCount(it) }
        private fun checkCount(added: Int) {
            count += added
            if (count > maximum) throw IOException("Layout cache exceeds decode limit")
        }
    }

    companion object {
        private const val MAGIC = 0x594A5031
        private const val MAX_PAGES = 20_000
        private const val MAX_FRAGMENTS = 10_000
        private const val MAX_TEXT_BYTES = 2 * 1024 * 1024
        private const val MAX_DECODED_BYTES = 32L * 1024 * 1024
    }
}
