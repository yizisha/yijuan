package com.flowreader.app.util

import android.net.Uri
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.util.epub.PublicationImportCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an import produced: the row id and whether it was new or a refresh of an existing book. */
data class ImportOutcome(
    val bookId: Long,
    val isNew: Boolean,
    /** EPUB 激活的 revision；null 表示走了非内容引擎路径（TXT/PDF/…）或激活失败。 */
    val activatedRevision: String? = null,
    /** 激活失败的原因码（诊断用）。 */
    val activationFailure: String? = null
)

/**
 * Idempotent single-file import, shared by the picker, the "open with" intent and the folder
 * scanner.
 *
 * `BookParser.copyFileToInternal` writes to `filesDir/books/<original name>`, so importing the same
 * file twice overwrites the same bytes. The previous implementation added a *second* row pointing at
 * them, which is why the shelf could show a book twice; this refreshes the existing row instead,
 * keeping its reading progress, group and added time.
 *
 * 内容引擎接线（主计划 5.3 / 第 16 节第 2 条）：EPUB 的解析产物在拿到真实 `bookId` 之后，
 * 由 [PublicationImportCoordinator] 走"文件已提交 → 单事务激活"的协议落库。
 * 激活失败**不回滚书本身**：书仍可读（legacy 章节已写入），只是 `contentState` 不变成 READY，
 * 这样用户不会因为内容包问题而丢书（5.3 第 6 条）。
 */
@Singleton
class BookImporter @Inject constructor(
    private val bookParser: BookParser,
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val publicationCoordinator: PublicationImportCoordinator,
    private val fileLocations: BookFileLocationStore
) {

    suspend fun importSingle(uri: Uri, categoryId: Long? = null): Result<ImportOutcome> = withContext(Dispatchers.IO) {
        // 同路径重导入必须复用原来的 publicationId（3.1：移动文件/重新解析都不改变身份）。
        //
        // 这里**只拷一次**文件并把路径复用下去：copyFileToInternal 是「按文件名覆盖式拷贝」，
        // 没有存在性检查，路径又完全确定（books/<文件名>）。此前先拷一次查重、解析后再拷一次，
        // 每次导入都把整本书多拷一遍——大书（几十 MB）时这是导入慢的主要来源之一。
        val internalPath = runCatching { bookParser.copyFileToInternal(uri) }.getOrNull()
        val existingByPath = internalPath?.let {
            runCatching { bookRepository.getBookByPath(it) }.getOrNull()
        }
        val publicationId = existingByPath?.publicationId ?: java.util.UUID.randomUUID().toString()

        bookParser.parseBook(uri, publicationId, internalPath).mapCatching { result ->
            // PDF 走的是自己的落盘路径；其余格式复用上面那次拷贝的结果。
            val resolvedPath = result.pdfFilePath ?: internalPath
            val measuredSize = resolvedPath?.let { java.io.File(it).takeIf { file -> file.isFile }?.length() }
            val parsed = result.book.copy(filePath = resolvedPath ?: "", fileSize = measuredSize ?: result.book.fileSize)
            val existing = resolvedPath?.let { bookRepository.getBookByPath(it) }
            val bundle = result.publication

            val bookId = if (existing != null) {
                bookRepository.updateBook(
                    parsed.copy(
                        id = existing.id,
                        categoryId = existing.categoryId ?: categoryId,
                        currentChapter = existing.currentChapter,
                        currentPosition = existing.currentPosition,
                        readingProgress = existing.readingProgress,
                        lastReadTime = existing.lastReadTime,
                        addedTime = existing.addedTime,
                        tags = existing.tags
                    )
                )
                chapterRepository.deleteChaptersByBookId(existing.id)
                chapterRepository.insertChapters(result.chapters.map { it.copy(bookId = existing.id) })
                existing.id
            } else {
                val inserted = bookRepository.insertBook(parsed.copy(categoryId = categoryId))
                chapterRepository.insertChapters(result.chapters.map { it.copy(bookId = inserted) })
                inserted
            }

            // Device-local URI grants are kept separately from the portable book backup.
            resolvedPath?.let { path -> runCatching { fileLocations.recordOriginal(path, uri) } }

            if (bundle == null) {
                return@mapCatching ImportOutcome(bookId, isNew = existing == null)
            }

            // 文件侧已在解析阶段提交为不可变 revision 目录，这里只做数据库侧激活。
            val prepared = prepareFromBundle(publicationId, bundle)
            if (prepared == null) {
                return@mapCatching ImportOutcome(
                    bookId = bookId,
                    isNew = existing == null,
                    activationFailure = "STAGED_PACKAGE_UNAVAILABLE"
                )
            }
            when (val activation = publicationCoordinator.activate(bookId, prepared, bundle.toc)) {
                is PublicationImportCoordinator.Activation.Activated ->
                    ImportOutcome(bookId, isNew = existing == null, activatedRevision = activation.revision)

                is PublicationImportCoordinator.Activation.Failed ->
                    ImportOutcome(
                        bookId = bookId,
                        isNew = existing == null,
                        activationFailure = "${activation.code}: ${activation.detail}"
                    )
            }
        }
    }

    /**
     * `BookParser` 已经把内容包写到 `revisions/<revision>/`，这里把它重新描述成一次
     * [PublicationImportCoordinator.Prepared]，供激活使用。
     *
     * 之所以不把 `Prepared` 一路从解析带到这里：`Prepared` 内含 staging 目录与文件句柄语义，
     * 而解析发生在拿到 `bookId` 之前；跨阶段只传**不可变数据**（manifest + 文档 + 目录）。
     */
    private fun prepareFromBundle(publicationId: String, bundle: PublicationBundle): PublicationImportCoordinator.Prepared? {
        val files = publicationCoordinator.existingRevisionFiles(publicationId, bundle.manifest.revision)
            ?: return null
        return PublicationImportCoordinator.Prepared.ofFiles(
            manifest = bundle.manifest,
            documents = bundle.documents,
            toc = bundle.toc,
            files = files,
            diagnostics = bundle.diagnostics
        )
    }
}
