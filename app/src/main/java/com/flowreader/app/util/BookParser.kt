package com.flowreader.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.flowreader.app.core.util.TxtCleaner
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TxtProcessingRules
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.app.util.epub.EpubDocumentParser
import com.flowreader.app.util.epub.EpubNavigationParser
import com.flowreader.app.util.epub.EpubPackageParser
import com.flowreader.app.util.epub.EpubZipContainer
import com.flowreader.app.util.epub.PublicationImportCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.*
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class ParseProgress {
    object Starting : ParseProgress()
    data class Reading(val bytesRead: Long, val totalBytes: Long) : ParseProgress()
    object Parsing : ParseProgress()
    object Saving : ParseProgress()
    object Complete : ParseProgress()
    data class Error(val message: String) : ParseProgress()
}

data class BookParseResult(
    val book: Book,
    val chapters: List<Chapter>,
    val pdfPageCount: Int = 0,
    val pdfFilePath: String? = null,
    /**
     * EPUB 新链路产物。**UI 不读它**，它只交给 `BookImporter` →
     * `PublicationImportCoordinator` 落成内容包并激活 revision（主计划 16 节第 2 条：
     * 不允许"生成了结构化数据却仍把 UI 交给旧路径"，所以旧解析路径已在本批删除）。
     */
    val publication: PublicationBundle? = null
)

/**
 * 一次导入的完整结构化产物。
 *
 * `chapters` 已经是从 [manifest] 的 sections 投影出来的显示层结果，
 * 因此 [manifest] 里的源坐标与 UI 看到的章节是**同一份内容**，不是两个事实源。
 */
data class PublicationBundle(
    val manifest: PublicationManifest,
    val documents: Map<String, StructuredDocument>,
    val toc: List<EpubNavigationParser.Node>,
    val diagnostics: List<String>
)

@Singleton
class BookParser @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val publicationCoordinator: PublicationImportCoordinator
) {
    private val bufferSize = 8192

    // Keep chapter rows safely below Android CursorWindow limits. Large TXT/Markdown
    // files without headings used to be stored as one huge chapter and could crash
    // when Room read the content back for the reader screen.
    private val maxChapterChars = 8_000

    /** Single EPUB XHTML entry cap; a pathological 100MB+ book must not blow up memory. */
    private val maxChapterReadBytes = 16L * 1024 * 1024

    /** Single embedded image cap; oversized art is skipped instead of loaded. */
    private val maxSingleImageBytes = 24L * 1024 * 1024

    /** Whole-document cap for TXT / Markdown / FB2 / MOBI single-file formats. */
    private val maxWholeFileBytes = 128L * 1024 * 1024

    suspend fun parseBook(uri: Uri, publicationId: String? = null, localCopyPath: String? = null): Result<BookParseResult> =
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(Exception("无法打开文件"))

                val fileName = getFileName(uri)
                val format = detectFormat(fileName)
                val fileSize = getFileSize(uri)

                when (format) {
                    BookFormat.EPUB -> {
                        val coverInputStream = context.contentResolver.openInputStream(uri)
                            ?: inputStream
                        val coverPath = extractEpubCover(coverInputStream, fileName.removeSuffix(".epub"))
                        val result = parseEpubViaContentEngine(
                            inputStream = inputStream,
                            fileName = fileName,
                            fileSize = fileSize,
                            publicationId = publicationId ?: java.util.UUID.randomUUID().toString()
                        )
                        result.map { it.copy(book = it.book.copy(coverPath = coverPath)) }
                    }

                    BookFormat.TXT -> parseTxtStream(inputStream, fileName, fileSize)
                    BookFormat.MARKDOWN -> parseMarkdownStream(inputStream, fileName, fileSize)
                    BookFormat.PDF -> {
                        inputStream.close()
                        val result = parsePdfStream(uri, fileName, fileSize, localCopyPath)
                        result.map { it.copy(book = it.book.copy(coverPath = extractPdfCover(Uri.fromFile(File(it.book.filePath))))) }
                    }

                    BookFormat.FB2 -> parseFb2Stream(inputStream, fileName, fileSize)
                    BookFormat.MOBI -> parseMobiStream(inputStream, fileName, fileSize)
                    BookFormat.COMIC -> parseComicImageStream(inputStream, fileName, fileSize)
                    BookFormat.UNKNOWN -> {
                        if (fileName.endsWith(".zip", ignoreCase = true) || fileName.endsWith(".cbz", ignoreCase = true)) {
                            parseComicZipStream(inputStream, fileName, fileSize)
                        } else {
                            Result.failure(Exception("不支持的格式: $format"))
                        }
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }.map { it.withWordCount() }

    /**
     * Stamps the character count onto the parsed book.
     *
     * Measured here, once, instead of at display time: 书籍详情 shows it immediately and the reader
     * would otherwise have to walk every chapter's text. Whitespace is excluded so the number
     * matches what readers call 字数.
     */
    private fun BookParseResult.withWordCount(): BookParseResult {
        val count = chapters.sumOf { chapter ->
            chapter.content.count { !it.isWhitespace() }
        }
        return copy(book = book.copy(wordCount = count, totalChapters = chapters.size))
    }

    fun parseBookWithProgress(uri: Uri) = flow {
        emit(ParseProgress.Starting)

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileName = getFileName(uri)
                val format = detectFormat(fileName)
                val fileSize = getFileSize(uri)

                when (format) {
                    BookFormat.EPUB -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        val result = parseEpubViaContentEngine(
                            inputStream = inputStream,
                            fileName = fileName,
                            fileSize = fileSize,
                            publicationId = java.util.UUID.randomUUID().toString()
                        )
                        emit(ParseProgress.Parsing)
                        result
                    }
                    BookFormat.TXT -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        val result = parseTxtStream(inputStream, fileName, fileSize)
                        emit(ParseProgress.Parsing)
                        result
                    }
                    BookFormat.PDF -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        inputStream.close()
                        val result = parsePdfStream(uri, fileName, fileSize)
                        emit(ParseProgress.Parsing)
                        result
                    }
                    BookFormat.MARKDOWN -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        val result = parseMarkdownStream(inputStream, fileName, fileSize)
                        emit(ParseProgress.Parsing)
                        result
                    }
                    BookFormat.FB2 -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        val result = parseFb2Stream(inputStream, fileName, fileSize)
                        emit(ParseProgress.Parsing)
                        result
                    }
                    BookFormat.MOBI -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        val result = parseMobiStream(inputStream, fileName, fileSize)
                        emit(ParseProgress.Parsing)
                        result
                    }
                    BookFormat.COMIC -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        val result = parseComicImageStream(inputStream, fileName, fileSize)
                        emit(ParseProgress.Parsing)
                        result
                    }
                    BookFormat.UNKNOWN -> {
                        emit(ParseProgress.Reading(0, fileSize))
                        val result = if (fileName.endsWith(".zip", ignoreCase = true) || fileName.endsWith(".cbz", ignoreCase = true)) {
                            parseComicZipStream(inputStream, fileName, fileSize)
                        } else {
                            Result.failure(Exception("不支持的格式: $format"))
                        }
                        emit(ParseProgress.Parsing)
                        result
                    }
                }.onSuccess {
                    emit(ParseProgress.Complete)
                }.onFailure {
                    emit(ParseProgress.Error(it.message ?: "解析失败"))
                }
            } ?: throw Exception("无法打开文件")
        } catch (e: Exception) {
            emit(ParseProgress.Error(e.message ?: "解析失败"))
        }
    }.flowOn(Dispatchers.IO)

    private fun detectFormat(fileName: String): BookFormat {
        return detectFormatStatic(fileName)
    }

    companion object {
        fun detectFormatStatic(fileName: String): BookFormat {
            return when {
                fileName.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
                fileName.endsWith(".txt", ignoreCase = true) -> BookFormat.TXT
                fileName.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
                fileName.endsWith(".md", ignoreCase = true) || fileName.endsWith(".markdown", ignoreCase = true) -> BookFormat.MARKDOWN
                fileName.endsWith(".fb2", ignoreCase = true) || fileName.endsWith(".fb2.zip", ignoreCase = true) -> BookFormat.FB2
                // .azw is DRM-free MOBI; an encrypted one is rejected by MobiParser, not decrypted.
                fileName.endsWith(".mobi", ignoreCase = true) ||
                    fileName.endsWith(".prc", ignoreCase = true) ||
                    fileName.endsWith(".azw", ignoreCase = true) -> BookFormat.MOBI
                isComicImageName(fileName) -> BookFormat.COMIC
                fileName.endsWith(".cbz", ignoreCase = true) -> BookFormat.UNKNOWN
                else -> BookFormat.UNKNOWN
            }
        }

        fun isComicImageName(fileName: String): Boolean {
            val lower = fileName.lowercase(Locale.ROOT)
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
        }

        fun naturalComicSortKey(name: String): List<String> =
            Regex("\\d+|\\D+").findAll(name.lowercase(Locale.ROOT)).map { match ->
                val value = match.value
                value.toLongOrNull()?.toString()?.padStart(12, '0') ?: value
            }.toList()
    }

    /**
     * The display name the picker reports, resolved off the main thread. Callers need this before
     * parsing to tell a book apart from a `.zip` of books.
     */
    suspend fun displayName(uri: Uri): String = withContext(Dispatchers.IO) { getFileName(uri) }

    /**
     * `ContentResolver.query` returns nothing for `file://` URIs, which is how batch-extracted ZIP
     * entries and OPDS downloads arrive. Falling back to the path keeps their titles and sizes
     * real instead of every one of them importing as "未知书籍" with size 0.
     */
    private fun getFileName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (displayNameIndex >= 0) {
                    cursor.getString(displayNameIndex)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "未知书籍"
    }

    private fun getFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    val size = cursor.getLong(sizeIndex)
                    if (size > 0) return size
                }
            }
        }
        return uri.path?.let { path -> File(path).takeIf { it.isFile }?.length() } ?: 0L
    }

    /**
     * EPUB 导入（主计划 4.1 / 第 16 节第 2 条）。
     *
     * **旧路径已删除**：这里不再是 `htmlToFormattedText` + `[IMG:绝对路径]`。
     * 现在按 4.1 的七步流水线走独立可测组件：
     * `EpubZipContainer` → `EpubPackageParser` → `EpubNavigationParser` → `EpubDocumentParser`
     * → `ReadingManifestBuilder` → `PublicationStore`（文件侧）→ `PublicationImportCoordinator`（数据库侧）。
     *
     * 因此：
     * - 正文以规范源文本 + 结构块保存，粗体/斜体/链接/列表不再被压成文本（修 F09）；
     * - 图片是结构对象（`resourceId` 摘要命名），不再产生 `[IMG:…]` 字符串标记（修 F10/F11）；
     * - 目录保留层级与 `<span>` 卷头，同一 XHTML 的多个锚点各自成为目录目标（修 F05/F06）；
     * - 章节来自 manifest 的 sections，**不再按 8,000 字物理分片**，所以没有 `(1)(2)` 伪章节（修 F13）；
     * - 卷字段由目录树的卷分组投影得到，不再丢失（修 F08）。
     *
     * 返回的 `chapters` 是**显示层投影**（内容来自同一个 manifest 的源区间），
     * 与 [PublicationBundle.manifest] 不是两个事实源。
     */
    private fun parseEpubViaContentEngine(
        inputStream: InputStream,
        fileName: String,
        fileSize: Long,
        publicationId: String
    ): Result<BookParseResult> {
        val title = fileName.removeSuffix(".epub")
        val workFile = File(context.cacheDir, "epub-in/${sha256Hex(publicationId.toByteArray())}.epub")
        return try {
            workFile.parentFile?.mkdirs()
            inputStream.use { input ->
                FileOutputStream(workFile).use { output -> input.copyTo(output) }
            }

            // revision 由原始字节 + 规范化/解析器版本组成（3.1：资源或规范源文本改变必须变更）。
            val revision = sha256Hex(
                workFile.readBytes() +
                    byteArrayOf(1) +
                    "${StructuredDocument.CURRENT_NORMALIZATION_VERSION}:${StructuredDocument.CURRENT_SCHEMA_VERSION}"
                        .toByteArray(Charsets.UTF_8)
            )

            val prepared = publicationCoordinator.prepareFromContainer(
                publicationId = publicationId,
                revision = revision,
                containerFile = workFile
            ).getOrElse { error ->
                return Result.failure(Exception("EPUB 解析失败: ${error.message}", error))
            }

            val chapters = publicationCoordinator.projectChapters(
                manifest = prepared.manifest,
                documents = prepared.documents,
                toc = prepared.toc,
                bookId = 0L
            )
            if (chapters.isEmpty()) {
                return Result.failure(Exception("未找到可解析的章节内容"))
            }

            val metadata = readEpubMetadata(prepared.manifest.revision, workFile)
            return Result.success(
                BookParseResult(
                    book = Book(
                        title = title,
                        author = metadata.first,
                        filePath = "",
                        coverPath = null,
                        description = metadata.second,
                        fileSize = fileSize,
                        format = BookFormat.EPUB,
                        totalChapters = chapters.size
                    ),
                    chapters = chapters,
                    publication = PublicationBundle(
                        manifest = prepared.manifest,
                        documents = prepared.documents,
                        toc = prepared.toc,
                        diagnostics = prepared.diagnostics
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 工作副本只用于本次解析；内容包已经在 revisions/ 下有可验证的 source 副本。
            workFile.delete()
        }
    }

    /**
     * 从 OPF 读 `dc:creator` / `dc:description`。
     *
     * 只做元数据；正文一律走内容引擎。读不到就返回占位值，**不因为元数据缺失而让导入失败**。
     */
    private fun readEpubMetadata(revision: String, containerFile: File): Pair<String, String> {
        if (!containerFile.isFile) return "未知作者" to ""
        return try {
            val opened = EpubZipContainer.open(containerFile)
            if (opened !is EpubZipContainer.OpenResult.Opened) return "未知作者" to ""
            opened.container.use { container ->
                val containerXml = container.readEntryText("META-INF/container.xml").getOrNull()
                    ?: return "未知作者" to ""
                val opfPath = EpubPackageParser.parseRootFilePath(containerXml).first
                    ?: return "未知作者" to ""
                val opfXml = container.readEntryText(opfPath).getOrNull()
                    ?: return "未知作者" to ""
                val document = Jsoup.parse(opfXml, "", org.jsoup.parser.Parser.xmlParser())
                val creator = document.select("creator").firstOrNull()?.text()?.trim().orEmpty()
                val description = document.select("description").firstOrNull()?.text()?.trim().orEmpty()
                (creator.ifEmpty { "未知作者" }) to description
            }
        } catch (e: Exception) {
            // 元数据失败不影响导入。
            "未知作者" to ""
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
    private fun extractEpubCover(inputStream: InputStream, bookTitle: String): String? {
        return try {
            val tempFile = File(context.cacheDir, "temp_epub_${System.currentTimeMillis()}.zip")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            ZipInputStream(FileInputStream(tempFile)).use { zipInput ->
                var entry = zipInput.nextEntry
                var coverImage: ByteArray? = null
                var coverName: String? = null

                while (entry != null) {
                    val lowerName = entry.name.lowercase()
                    if (lowerName.contains("cover") && (lowerName.endsWith(".jpg") || lowerName.endsWith(".png") || lowerName.endsWith(".jpeg"))) {
                        coverImage = zipInput.readBytes()
                        coverName = entry.name
                        entry = zipInput.nextEntry
                    } else if (lowerName.endsWith(".opf")) {
                        val opfContent = zipInput.bufferedReader().readText()
                        val coverMeta = Regex("item[^>]*href=\"([^\"]+cover[^\"]*\\.(jpg|png|jpeg))\"", RegexOption.IGNORE_CASE)
                            .find(opfContent)
                        if (coverMeta != null) {
                            val coverPath = coverMeta.groupValues[1]
                            val zip = java.util.zip.ZipFile(tempFile)
                            val coverEntry = zip.getEntry(coverPath)
                            if (coverEntry != null) {
                                coverImage = zip.getInputStream(coverEntry).readBytes()
                            }
                            zip.close()
                        }
                    }
                    entry = zipInput.nextEntry
                }

                if (tempFile.exists()) {
                    tempFile.delete()
                }

                coverImage?.let { saveCoverImage(it, bookTitle) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPdfCover(uri: Uri): String? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount > 0) {
                        renderer.openPage(0).use { page ->
                            val (coverWidth, coverHeight) = pdfBitmapSize(page.width, page.height, 384)
                            val bitmap = Bitmap.createBitmap(
                                coverWidth,
                                coverHeight,
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val coversDir = File(context.filesDir, "covers")
                            if (!coversDir.exists()) {
                                coversDir.mkdirs()
                            }

                            val fileName = "pdf_cover_${System.currentTimeMillis()}.jpg"
                            val file = File(coversDir, fileName)

                            FileOutputStream(file).use { fos ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                            }

                            bitmap.recycle()
                            file.absolutePath
                        }
                    } else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun htmlToFormattedTextForProjection(
        element: Element,
        imageHrefs: Map<String, String>,
        baseDir: String = ""
    ): String {
        val sb = StringBuilder()
        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> {
                    val text = node.text()
                    if (text.isNotBlank()) sb.append(text)
                }
                is Element -> {
                    val tag = node.tagName()
                    when {
                        tag == "br" -> sb.appendLine()
                        tag.startsWith("h") && tag.length == 2 && tag[1] in '1'..'6' -> {
                            sb.appendLine()
                            sb.append("## ")
                            sb.append(htmlToFormattedTextForProjection(node, imageHrefs, baseDir))
                            sb.appendLine()
                        }
                        tag == "b" || tag == "strong" -> {
                            // Emphasis markers are deliberately NOT emitted: the reader renders
                            // plain text in both page modes, so `**` leaked into the page as
                            // literal asterisks (「首段被切成黑体加粗的章节名」).
                            sb.append(htmlToFormattedTextForProjection(node, imageHrefs, baseDir))
                        }
                        tag == "i" || tag == "em" -> {
                            sb.append(htmlToFormattedTextForProjection(node, imageHrefs, baseDir))
                        }
                        tag == "img" || tag == "image" -> {
                            // `<img src>` 的路径解析现在只由 `EpubResourceResolver` 一处负责（4.2）。
                            // 本投影仅服务 MOBI/FB2 的 HTML 转文本，没有包内资源表，
                            // 所以按容量上限明确留一个不带路径的占位诊断，**不再猜路径**
                            // （旧的 basename 兜底正是 F11 的成因）。
                            val rawSrc = node.attr("src").ifBlank { node.attr("xlink:href") }
                            if (rawSrc.isNotBlank()) {
                                sb.appendLine()
                                sb.append("[IMG-UNRESOLVED:$rawSrc]")
                                sb.appendLine()
                            }
                        }
                        tag == "p" || tag == "div" -> {
                            val inner = htmlToFormattedTextForProjection(node, imageHrefs, baseDir).trim()
                            if (inner.isNotBlank()) {
                                sb.appendLine(inner)
                                sb.appendLine()
                            }
                        }
                        tag == "span" || tag == "a" || tag == "li" -> {
                            sb.append(htmlToFormattedTextForProjection(node, imageHrefs, baseDir))
                        }
                        tag == "ul" || tag == "ol" -> {
                            sb.appendLine()
                            sb.append(htmlToFormattedTextForProjection(node, imageHrefs, baseDir))
                            sb.appendLine()
                        }
                        else -> {
                            sb.append(htmlToFormattedTextForProjection(node, imageHrefs, baseDir))
                        }
                    }
                }
            }
        }
        return sb.toString()
    }
    private suspend fun parseTxtStream(inputStream: InputStream, fileName: String, fileSize: Long): Result<BookParseResult> {
        return try {
            val rules = runCatching { settingsRepository.appSettings.first().txtProcessing }
                .getOrDefault(TxtProcessingRules())
            val rawText = inputStream.readCappedTextNotNull(maxWholeFileBytes)
            val text = if (rules.hasAnyCleaning) TxtCleaner.clean(rawText, rules) else TxtCleaner.normalize(rawText)

            val title = fileName.removeSuffix(".txt").removeSuffix(".TXT")
            val chapters = mutableListOf<Chapter>()

            val lines = text.lines()
            val author = TxtChapterDetector.extractAuthor(lines) ?: "未知作者"
            val patterns = TxtChapterDetector.selectBestPatterns(lines)
            val customPatterns = TxtCleaner.compileChapterRegex(rules.customChapterRegex)
                ?.let { listOf(it) }
                .orEmpty()
            // When the user insists on their own regex, the built-in tiers are skipped so a book
            // whose headings the heuristics misread can be re-split deterministically.
            val activePatterns = if (rules.customRegexOnly && customPatterns.isNotEmpty()) emptyList() else patterns

            var currentVolume: String? = null
            var currentChapterTitle = "前言"
            var currentContent = StringBuilder()
            var chapterIndex = 0

            if (activePatterns.isNotEmpty() || customPatterns.isNotEmpty()) {
                for (line in lines) {
                    val match = TxtChapterDetector.matchHeading(line, activePatterns, customPatterns)
                    if (match.isHeading) {
                        // Flush whatever is pending before the heading changes the context.
                        if (currentContent.isNotBlank()) {
                            chapterIndex = appendChunkedChapter(
                                chapters = chapters,
                                title = currentChapterTitle,
                                content = currentContent.toString().trim(),
                                startPosition = 0,
                                startIndex = chapterIndex,
                                volume = currentVolume
                            )
                        }
                        currentContent = StringBuilder()
                        if (match.isVolume) {
                            // A volume heading is a group label: record it so subsequent chapters
                            // carry this volume. Any text between this volume heading and the first
                            // chapter is the volume's preface (卷序).
                            currentVolume = match.title
                            currentChapterTitle = "卷序"
                        } else {
                            currentChapterTitle = match.title
                        }
                    } else {
                        currentContent.appendLine(line)
                    }
                }

                if (currentContent.isNotBlank()) {
                    appendChunkedChapter(
                        chapters = chapters,
                        title = currentChapterTitle,
                        content = currentContent.toString().trim(),
                        startPosition = 0,
                        startIndex = chapterIndex,
                        volume = currentVolume
                    )
                }
            }

            if (chapters.isEmpty()) {
                appendChunkedChapter(
                    chapters = chapters,
                    title = "全部内容",
                    content = text,
                    startPosition = 0
                )
            }

            Result.success(
                BookParseResult(
                    book = Book(
                        title = title,
                        author = author,
                        filePath = "",
                        fileSize = fileSize,
                        format = BookFormat.TXT,
                        totalChapters = chapters.size
                    ),
                    chapters = chapters
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveCoverImage(imageData: ByteArray, bookTitle: String): String? {
        return try {
            val coversDir = File(context.filesDir, "covers")
            if (!coversDir.exists()) {
                coversDir.mkdirs()
            }

            val fileName = bookTitle.replace(Regex("[^a-zA-Z0-9]"), "_") + ".jpg"
            val file = File(coversDir, fileName)

            FileOutputStream(file).use { fos ->
                fos.write(imageData)
            }

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun copyFileToInternal(uri: Uri): String? {
        return try {
            val booksDir = File(context.filesDir, "books")
            if (!booksDir.exists()) {
                booksDir.mkdirs()
            }

            val fileName = getFileName(uri)
            val file = File(booksDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun parsePdfStream(uri: Uri, fileName: String, fileSize: Long, localCopyPath: String? = null): Result<BookParseResult> {
        return try {
            val internalPath = localCopyPath ?: copyFileToInternal(uri)
                ?: return Result.failure(Exception("无法保存PDF文件"))

            val pageCount = ParcelFileDescriptor.open(File(internalPath), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
            require(pageCount > 0) { "PDF 没有可阅读的页面" }

            val title = fileName.substringBeforeLast('.')

            val chapters = (0 until pageCount).map { pageIndex ->
                Chapter(
                    bookId = 0,
                    index = pageIndex,
                    title = "第 ${pageIndex + 1} 页",
                    content = "",
                    startPosition = pageIndex,
                    endPosition = pageIndex
                )
            }

            Result.success(
                BookParseResult(
                    book = Book(
                        title = title,
                        author = "未知作者",
                        filePath = internalPath,
                        fileSize = fileSize,
                        format = BookFormat.PDF,
                        totalChapters = pageCount
                    ),
                    chapters = chapters,
                    pdfPageCount = pageCount,
                    pdfFilePath = internalPath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseComicImageStream(inputStream: InputStream, fileName: String, fileSize: Long): Result<BookParseResult> {
        return try {
            val title = fileName.substringBeforeLast('.')
            val directory = File(context.filesDir, "comics/${System.currentTimeMillis()}_${sanitizeFileName(title)}")
            if (!directory.mkdirs() && !directory.isDirectory) {
                return Result.failure(Exception("无法创建漫画目录"))
            }
            val image = File(directory, uniqueComicFileName(fileName, 0))
            inputStream.use { input -> FileOutputStream(image).use { output -> input.copyTo(output) } }

            val chapter = comicChapter(index = 0, title = "第 1 页", imagePath = image.absolutePath)
            Result.success(
                BookParseResult(
                    book = Book(
                        title = title,
                        author = "未知作者",
                        filePath = directory.absolutePath,
                        coverPath = image.absolutePath,
                        fileSize = fileSize,
                        format = BookFormat.COMIC,
                        totalChapters = 1
                    ),
                    chapters = listOf(chapter),
                    pdfFilePath = directory.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseComicZipStream(inputStream: InputStream, fileName: String, fileSize: Long): Result<BookParseResult> {
        val tempFile = File(context.cacheDir, "temp_comic_${System.currentTimeMillis()}.zip")
        return try {
            inputStream.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } }

            val title = fileName.removeSuffixIgnoreCase(".zip").removeSuffixIgnoreCase(".cbz")
            val directory = File(context.filesDir, "comics/${System.currentTimeMillis()}_${sanitizeFileName(title)}")
            if (!directory.mkdirs() && !directory.isDirectory) {
                return Result.failure(Exception("无法创建漫画目录"))
            }

            val imageEntries = ZipFile(tempFile).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .mapNotNull { entry -> ZipImportRules.safeComicImageName(entry.name, isDirectory = false)?.let { entry.name to it } }
                    .sortedWith { left, right -> compareNatural(left.second, right.second) }
                    .take(ZipImportRules.MAX_ENTRIES)
                    .toList()
            }
            if (imageEntries.isEmpty()) {
                directory.deleteRecursively()
                return Result.failure(Exception("压缩包中没有支持的图片"))
            }

            val images = mutableListOf<File>()
            ZipFile(tempFile).use { zip ->
                imageEntries.forEachIndexed { index, (entryName, name) ->
                    val target = File(directory, uniqueComicFileName(name, index))
                    val entry = zip.getEntry(entryName) ?: return@forEachIndexed
                    val written = zip.getInputStream(entry).use { input -> copyCapped(input, target, ZipImportRules.MAX_ENTRY_BYTES) }
                    if (written > 0) images.add(target) else target.delete()
                }
            }
            if (images.isEmpty()) {
                directory.deleteRecursively()
                return Result.failure(Exception("压缩包中的图片超过大小限制"))
            }

            val chapters = images.mapIndexed { index, image -> comicChapter(index, "第 ${index + 1} 页", image.absolutePath) }
            Result.success(
                BookParseResult(
                    book = Book(
                        title = title.ifBlank { fileName.substringBeforeLast('.') },
                        author = "未知作者",
                        filePath = directory.absolutePath,
                        coverPath = images.firstOrNull()?.absolutePath,
                        fileSize = fileSize,
                        format = BookFormat.COMIC,
                        totalChapters = chapters.size
                    ),
                    chapters = chapters,
                    pdfFilePath = directory.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile.delete()
        }
    }

    private fun comicChapter(index: Int, title: String, imagePath: String): Chapter =
        Chapter(
            bookId = 0,
            index = index,
            title = title,
            content = "[COMIC:$imagePath]",
            startPosition = index,
            endPosition = index + 1
        )

    /**
     * Reads the whole stream into memory up to [limit] bytes; null when the stream is larger
     * (the caller then skips the entry or fails the parse with a clear message).
     */
    private fun readCappedBytes(stream: InputStream, limit: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun readCappedText(stream: InputStream, limit: Long): String? =
        readCappedBytes(stream, limit)?.toString(Charsets.UTF_8)

    private fun InputStream.readCappedTextNotNull(limit: Long): String =
        readCappedText(this, limit) ?: throw IllegalStateException("文件过大：超过 ${limit / 1024 / 1024}MB 上限")

    private fun InputStream.readCappedBytesNotNull(limit: Long): ByteArray =
        readCappedBytes(this, limit) ?: throw IllegalStateException("文件过大：超过 ${limit / 1024 / 1024}MB 上限")

    private fun copyCapped(source: InputStream, target: File, limit: Long): Long {
        var total = 0L
        val buffer = ByteArray(bufferSize)
        FileOutputStream(target).use { output ->
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                total += read
                if (total > limit) return -1
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun uniqueComicFileName(name: String, index: Int): String {
        val extension = name.substringAfterLast('.', "jpg").lowercase(Locale.ROOT).takeIf { it.length <= 5 } ?: "jpg"
        return index.toString().padStart(5, '0') + ".$extension"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "comic" }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

    private fun compareNatural(left: String, right: String): Int {
        val leftKey = naturalComicSortKey(left)
        val rightKey = naturalComicSortKey(right)
        val size = minOf(leftKey.size, rightKey.size)
        for (i in 0 until size) {
            val result = leftKey[i].compareTo(rightKey[i])
            if (result != 0) return result
        }
        return leftKey.size.compareTo(rightKey.size)
    }

    private fun appendChunkedChapter(
        chapters: MutableList<Chapter>,
        title: String,
        content: String,
        startPosition: Int,
        startIndex: Int = chapters.size,
        volume: String? = null
    ): Int {
        if (content.isBlank()) return startIndex

        var nextIndex = startIndex
        var offset = 0
        val trimmedContent = content.trim()
        while (offset < trimmedContent.length) {
            val rawEnd = minOf(offset + maxChapterChars, trimmedContent.length)
            val splitEnd = if (rawEnd < trimmedContent.length) {
                trimmedContent.lastIndexOf('\n', rawEnd - 1).takeIf { it > offset + maxChapterChars / 2 } ?: rawEnd
            } else {
                rawEnd
            }
            val chunk = trimmedContent.substring(offset, splitEnd).trim()
            if (chunk.isNotEmpty()) {
                val partNumber = nextIndex - startIndex + 1
                chapters.add(
                    Chapter(
                        bookId = 0,
                        index = nextIndex,
                        title = if (offset == 0 && splitEnd >= trimmedContent.length) title else "$title ($partNumber)",
                        content = chunk,
                        startPosition = startPosition + offset,
                        endPosition = startPosition + splitEnd,
                        // F08：本函数一直接收 `volume`，却在构造 Chapter 时没传下去，卷信息在
                        // 这一行被静默丢弃，导致目录里所有分卷都退化成扁平章。计划 4.4 要求
                        // 卷信息必须随章节落到持久层。
                        volume = volume
                    )
                )
                nextIndex++
            }
            offset = splitEnd
            while (offset < trimmedContent.length && trimmedContent[offset].isWhitespace()) {
                offset++
            }
        }
        return nextIndex
    }

    /**
     * FB2 (v53). `.fb2.zip` is the common distribution form, so the archive wrapper is unwrapped
     * here rather than pushed onto the user as "unsupported format".
     */
    private fun parseFb2Stream(inputStream: InputStream, fileName: String, fileSize: Long): Result<BookParseResult> {
        return try {
            val xml = inputStream.use { stream ->
                if (fileName.endsWith(".zip", ignoreCase = true)) {
                    readFirstZipEntryText(stream)
                } else {
                    readCappedBytes(stream, maxWholeFileBytes)?.decodeXml()
                        ?: return Result.failure(Exception("文件过大：FB2 超过 ${maxWholeFileBytes / 1024 / 1024}MB 上限"))
                }
            } ?: return Result.failure(Exception("无法读取 FB2 内容"))

            Fb2Parser.parse(xml).map { book ->
                val fallbackTitle = fileName.removeSuffix(".zip").removeSuffix(".fb2")
                val chapters = mutableListOf<Chapter>()
                var index = 0
                book.sections.forEach { section ->
                    index = appendChunkedChapter(
                        chapters = chapters,
                        title = section.title,
                        content = section.content,
                        startPosition = 0,
                        startIndex = index
                    )
                }
                BookParseResult(
                    book = Book(
                        title = book.title.ifBlank { fallbackTitle },
                        author = book.author.ifBlank { "未知作者" },
                        filePath = "",
                        coverPath = book.coverImage?.let { saveCoverImage(it, book.title.ifBlank { fallbackTitle }) },
                        description = book.description,
                        fileSize = fileSize,
                        format = BookFormat.FB2,
                        totalChapters = chapters.size
                    ),
                    chapters = chapters
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** MOBI / PRC / AZW (v53), read-only. DRM-protected files are declined, never decrypted. */
    private fun parseMobiStream(inputStream: InputStream, fileName: String, fileSize: Long): Result<BookParseResult> {
        return try {
            val bytes = inputStream.use { it.readCappedBytesNotNull(maxWholeFileBytes) }
            MobiParser.parse(bytes).map { book ->
                val fallbackTitle = fileName.substringBeforeLast('.')
                val document = Jsoup.parse(book.html)
                document.select("script, style").remove()
                val text = htmlToFormattedTextForProjection(document.body(), emptyMap()).trim()

                val chapters = mutableListOf<Chapter>()
                appendChunkedChapter(
                    chapters = chapters,
                    title = book.title.ifBlank { fallbackTitle },
                    content = text,
                    startPosition = 0
                )

                BookParseResult(
                    book = Book(
                        title = book.title.ifBlank { fallbackTitle },
                        author = book.author.ifBlank { "未知作者" },
                        filePath = "",
                        description = book.description,
                        fileSize = fileSize,
                        format = BookFormat.MOBI,
                        totalChapters = chapters.size
                    ),
                    chapters = chapters
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readFirstZipEntryText(stream: InputStream): String? {
        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".fb2", ignoreCase = true)) {
                    return readCappedBytes(zip, maxWholeFileBytes)?.decodeXml()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** FB2 files ship in UTF-8 or windows-1251; trust the XML declaration over a blind UTF-8 read. */
    private fun ByteArray.decodeXml(): String {
        val head = String(this, 0, minOf(size, 200), Charsets.ISO_8859_1)
        val declared = Regex("encoding=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(this, charset)
    }

    private fun parseMarkdownStream(inputStream: InputStream, fileName: String, fileSize: Long): Result<BookParseResult> {
        return try {
            val text = inputStream.readCappedTextNotNull(maxWholeFileBytes)

            val title = fileName.removeSuffix(".md")
            val chapters = mutableListOf<Chapter>()

            val lines = text.lines()
            val chapterPattern = Regex("^#{1,6}\\s+(.+)|^##?\\s+.+")

            var currentChapterTitle = "前言"
            var currentContent = StringBuilder()
            var chapterIndex = 0

            for (line in lines) {
                val trimmedLine = line.trim()
                if (chapterPattern.containsMatchIn(trimmedLine) && trimmedLine.length < 100) {
                    if (currentContent.isNotBlank()) {
                        chapterIndex = appendChunkedChapter(
                            chapters = chapters,
                            title = currentChapterTitle,
                            content = currentContent.toString().trim(),
                            startPosition = 0,
                            startIndex = chapterIndex
                        )
                    }
                    currentChapterTitle = trimmedLine.replace(Regex("^#{1,6}\\s*"), "")
                    currentContent = StringBuilder()
                } else {
                    currentContent.appendLine(line)
                }
            }

            if (currentContent.isNotBlank()) {
                appendChunkedChapter(
                    chapters = chapters,
                    title = currentChapterTitle,
                    content = currentContent.toString().trim(),
                    startPosition = 0,
                    startIndex = chapterIndex
                )
            }

            if (chapters.isEmpty()) {
                appendChunkedChapter(
                    chapters = chapters,
                    title = "全部内容",
                    content = text,
                    startPosition = 0
                )
            }

            Result.success(
                BookParseResult(
                    book = Book(
                        title = title,
                        author = "未知作者",
                        filePath = "",
                        fileSize = fileSize,
                        format = BookFormat.MARKDOWN,
                        totalChapters = chapters.size
                    ),
                    chapters = chapters
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
