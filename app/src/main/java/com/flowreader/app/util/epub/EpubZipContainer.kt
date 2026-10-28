package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.ContentErrorCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB ZIP 容器 IO 适配层（主计划 4.1 第 1 步、4.2 第 7 条、11.2 输入上限）。
 *
 * 职责边界：**只做条目枚举与字节读取**。路径解引用在 [EpubResourceResolver]、OPF 结构在
 * [EpubPackageParser]、目录在 [EpubNavigationParser]，本类不重复它们的规则。
 *
 * 为什么需要这一层（旧 `BookParser.parseEpubStream` 的问题）：
 * 1. 旧实现直接 `zip.getEntry(href)`，**没有任何条目数/解压量上限**，也没有重复条目检测；
 *    一个 ZIP 炸弹或重复条目名的书会让导入把内存吃干净，或按“任选一个”静默读错文件。
 * 2. 旧实现用 `bufferedReader()`（平台默认编码）读 XHTML/OPF，遇到非 UTF-8 声明会静默替换成
 *    U+FFFD —— 正是 3.2 第 1 条禁止的「静默换编码」。本类提供严格解码，
 *    非法编码返回带 documentKey 的失败，而不是把乱码正文写进内容包。
 * 3. 大文件限制（计划 11.2：EPUB 文档 16MiB、单图片 24MiB、v1 总量 512MiB、条目 20,000）
 *    必须在**读取侧**执行，不能只信任 ZIP 头里声明的大小。
 *
 * 本类只依赖 JDK 的 `java.util.zip`，不引用 Android 类型，可在 JVM 上直接测试。
 */
object EpubZipContainer {

    /**
     * v1 输入上限（计划 11.2）。这些是本项目验收约定，实现不得私自放宽。
     *
     * 数值之所以做成可注入的 [ContainerLimits]：单测无法为 512MiB / 20,000 条目分配真实内存，
     * 但**执行这些上限的代码路径必须被真的跑到**。测试用小数值驱动同一段逻辑，
     * 生产一律使用 [DEFAULT_LIMITS]（不得在调用点传更宽的值）。
     */
    data class ContainerLimits(
        /** 条目数上限。`java.util.zip.ZipFile` 在约 65,535 条目处自身就会失败，这里更严。 */
        val maxEntryCount: Int = 20_000,

        /** 声明解压总量上限：512MiB。 */
        val maxTotalUncompressedBytes: Long = 512L * 1024 * 1024,

        /** 单个 XHTML/OPF/NCX 文本条目上限：16MiB（沿用 `BookParser.maxChapterReadBytes`）。 */
        val maxDocumentBytes: Long = 16L * 1024 * 1024,

        /** 单张图片上限：24MiB（沿用 `BookParser.maxSingleImageBytes`）。 */
        val maxImageBytes: Long = 24L * 1024 * 1024,

        /** 普通条目（CSS/字体/音频等）上限：64MiB。 */
        val maxOtherEntryBytes: Long = 64L * 1024 * 1024
    )

    /** 生产上限。导入链路只允许用这一份。 */
    val DEFAULT_LIMITS = ContainerLimits()

    /** 读中央目录时要回扫的文件尾部大小。 */
    const val END_OF_CENTRAL_DIRECTORY_SEARCH_BYTES = 66_000L

    /**
     * 用户可读的失败结果。**没有“空书但声称成功”的返回值**（计划 4.1 末段）。
     */
    sealed interface OpenResult {
        val diagnostics: List<Diagnostic>

        data class Opened(
            val container: Container,
            override val diagnostics: List<Diagnostic> = emptyList()
        ) : OpenResult

        data class Failed(
            val code: ContentErrorCode,
            val message: String,
            override val diagnostics: List<Diagnostic> = emptyList()
        ) : OpenResult
    }

    /** 与 [EpubPackageParser.Diagnostic] 同形，便于直接并入解析诊断流。 */
    data class Diagnostic(val code: String, val detail: String)

    /** ZIP 条目的只读元数据。 */
    data class Entry(
        /** ZIP 条目名原样，大小写敏感；不做 URL 解码（4.2 第 3 条末句）。 */
        val path: String,
        /** ZipFile 报告的未压缩大小；目录为 0。 */
        val declaredSize: Long,
        val compressionMethod: Int,
        val isDirectory: Boolean
    )

    /** 严格文本解码失败。 */
    class TextDecodingException(message: String) : IOException(message)

    /**
     * 打开容器。调用方负责 [Container.close]。
     *
     * 顺序固定：文件存在 → 条目数上限 → ZIP64 拒绝 → 解压总量上限 → 重复条目。
     * 任一步失败都不返回半开的容器。
     */
    fun open(file: File, limits: ContainerLimits = DEFAULT_LIMITS): OpenResult {
        if (!file.isFile) {
            return OpenResult.Failed(ContentErrorCode.SOURCE_UNAVAILABLE, "文件不存在或不可读: ${file.name}")
        }

        val central = try {
            readCentralDirectoryEntries(file)
        } catch (e: IOException) {
            return OpenResult.Failed(ContentErrorCode.CONTAINER_INVALID, "ZIP 中央目录不可读: ${e.message}")
        }

        central ?: return OpenResult.Failed(
            ContentErrorCode.CONTAINER_INVALID,
            "不是有效的 ZIP 容器：找不到中央目录结束记录"
        )

        if (central.size > limits.maxEntryCount) {
            return OpenResult.Failed(
                ContentErrorCode.CONTAINER_INVALID,
                "条目数超过上限: ${central.size} > ${limits.maxEntryCount}"
            )
        }

        if (central.any { it.declaredSize == ZIP64_SENTINEL }) {
            return OpenResult.Failed(
                ContentErrorCode.CONTAINER_INVALID,
                "ZIP64 条目不受支持（条目声明大小未知）"
            )
        }

        val declaredTotal = central.sumOf { it.declaredSize }
        if (declaredTotal > limits.maxTotalUncompressedBytes) {
            return OpenResult.Failed(
                ContentErrorCode.CONTAINER_INVALID,
                "声明解压总量超过上限: $declaredTotal > ${limits.maxTotalUncompressedBytes}"
            )
        }

        val zipFile = try {
            ZipFile(file)
        } catch (e: IOException) {
            return OpenResult.Failed(ContentErrorCode.CONTAINER_INVALID, "无法打开 ZIP: ${e.message}")
        }

        // 中央目录里同名条目出现多次 → 结构错误，**不能 firstOrNull 静默任选**（4.2 第 7 条）。
        val duplicates = central.groupBy { it.path }.filterValues { it.size > 1 }.keys.sorted()
        val diagnostics = duplicates.map {
            Diagnostic("DUPLICATE_ZIP_ENTRY", "ZIP 中同名条目重复: $it")
        }

        return OpenResult.Opened(Container(file, zipFile, limits, diagnostics, central.size), diagnostics)
    }

    /**
     * 一次打开的容器。**不是线程安全的**：由单个导入任务独占使用，并在完成后关闭。
     *
     * 累计解压量按“每个条目只计一次声明大小”执行，因此重复读取同一文档不会误触总量上限，
     * 但每个条目的实际读取字节仍严格按声明大小校验（防 ZIP 炸弹）。
     *
     * 注意：本类**不**实现 [OpenResult]（曾经实现过）。让 `Container` 也成为一种 `OpenResult`
     * 会让 `when (open(file)) { Failed -> …; Opened -> … }` 不再穷尽，调用方被迫写 `else`，
     * 从而在类型层面失去"失败必须先处理"的保证。
     */
    class Container internal constructor(
        val file: File,
        private val zipFile: ZipFile,
        val limits: ContainerLimits = DEFAULT_LIMITS,
        /** 打开时累计的诊断（重复条目等）。读取期的失败走返回值，不污染这一份。 */
        val diagnostics: List<Diagnostic> = emptyList(),
        /** 中央目录里声明的条目数：即使 [ZipFile] 折叠了重复条目，也能报告真实规模。 */
        val declaredEntryCount: Int = 0
    ) : AutoCloseable {

        private val accountedPaths = HashSet<String>()
        private var accountedBytes = 0L
        private var closed = false

        /** 当前累计的解压量（只统计已实际读取过的条目）。 */
        val accountedUncompressedBytes: Long get() = accountedBytes

        /** 剩余的解压额度。读取期每个条目都按它扣减，避免“每条都拿满额”。 */
        val remainingBudget: Long get() = limits.maxTotalUncompressedBytes - accountedBytes

        /** 全部条目，按中央目录顺序。 */
        val entries: List<Entry> by lazy(LazyThreadSafetyMode.NONE) {
            ensureOpen()
            zipFile.entries().asSequence().sortedBy { it.name }.map { it.toEntry() }.toList()
        }

        val entryCount: Int get() = entries.size

        fun find(path: String): Entry? = entries.firstOrNull { it.path == path }

        /** 文本条目的分类上限：文档 16MiB、图片 24MiB、其它 64MiB。 */
        fun perEntryLimit(path: String): Long = when {
            isImagePath(path) -> limits.maxImageBytes
            isDocumentPath(path) -> limits.maxDocumentBytes
            else -> limits.maxOtherEntryBytes
        }

        /**
         * 读取条目字节，执行条目上限与总解压量上限。
         *
         * @param maxBytes 调用方给出的更严上限；0 表示使用按扩展名分类的默认上限。
         */
        fun readEntryBytes(path: String, maxBytes: Long = 0L): Result<ByteArray> {
            ensureOpen()
            val limit = if (maxBytes > 0L) maxBytes else perEntryLimit(path)
            val zipEntry = zipFile.getEntry(path)
                ?: return Result.failure(EntryMissingException(path))

            val declared = zipEntry.size
            if (declared > limit) {
                return Result.failure(
                    EntryTooLargeException(path, declared, limit, ContentErrorCode.CONTENT_LIMIT_EXCEEDED)
                )
            }
            // 剩余额度只对新条目扣减：已计过一次的条目再次读取不再占用额度，
            // 否则「读过的文档重新读一遍」会凭空消耗额度，把正常书判成超限。
            val alreadyCounted = accountedPaths.contains(path)
            if (!alreadyCounted && declared > remainingBudget) {
                return Result.failure(
                    EntryTooLargeException(path, declared, remainingBudget, ContentErrorCode.CONTENT_LIMIT_EXCEEDED)
                )
            }

            val bytes = try {
                readCapped(zipFile.getInputStream(zipEntry), declared, limit)
            } catch (e: IOException) {
                return Result.failure(e)
            } ?: return Result.failure(
                EntryTooLargeException(path, declared, limit, ContentErrorCode.CONTENT_LIMIT_EXCEEDED)
            )

            if (accountedPaths.add(path)) {
                accountedBytes += bytes.size.toLong()
            }
            return Result.success(bytes)
        }

        /**
         * 读取并按 XML 声明/BOM 严格解码。
         *
         * 失败时抛出 [TextDecodingException]，其中包含 documentKey —— 3.2 第 1 条要求
         * 「失败报告具体文档，不静默换编码」。
         */
        fun readEntryText(path: String, maxBytes: Long = 0L): Result<String> =
            readEntryBytes(path, maxBytes).mapCatching { decodeText(path, it) }

        /** 强制关闭窗口（`open` 的条目流都可能仍持有句柄）。 */
        override fun close() {
            if (closed) return
            closed = true
            try {
                zipFile.close()
            } catch (e: IOException) {
                // 关闭失败不影响已经读到的内容，也不允许它掩盖导入结果。
            }
        }

        private fun ensureOpen() {
            check(!closed) { "容器已关闭: ${file.name}" }
        }

        private fun ZipEntry.toEntry(): Entry = Entry(
            path = name,
            declaredSize = if (size < 0L) 0L else size,
            compressionMethod = method,
            isDirectory = isDirectory
        )
    }

    /** 条目在容器中不存在。 */
    class EntryMissingException(val path: String) :
        IOException("容器中不存在条目: $path")

    /** 条目超过大小上限。【code】统一为 [ContentErrorCode.CONTENT_LIMIT_EXCEEDED]。 */
    class EntryTooLargeException(
        val path: String,
        val actualBytes: Long,
        val limitBytes: Long,
        val code: ContentErrorCode
    ) : IOException("条目超过上限: $path ($actualBytes > $limitBytes)")

    // ------------------------------------------------------------------ 文本解码

    private val XML_DECLARATION = Regex("""<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
    private val CHARSET_FORBIDDEN = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    /**
     * 严格解码一个 XML/文本条目（3.2 第 1 条）。
     *
     * 1. BOM（UTF-8 / UTF-16LE / UTF-16BE）优先；
     * 2. 否则按无 BOM 的 ASCII 超集扫描 XML 声明里的 `encoding`；
     * 3. 都没有则按 UTF-8。
     *
     * 解码器统一 `REPORT`，**不允许**把非法字节静默替换成 U+FFFD；声明了本机不支持的编码同样报错。
     * 标签前的 NUL 是 UTF-16 被当成 UTF-8 读的典型症状，单独识别并给出可操作提示。
     */
    fun decodeText(documentKey: String, bytes: ByteArray): String {
        val bom = detectBom(bytes)
        val charset: Charset
        val payload: ByteArray
        if (bom != null) {
            charset = bom.first
            payload = bytes.copyOfRange(bom.second, bytes.size)
        } else {
            val declared = declaredEncoding(bytes)
            if (declared != null && declared.equals("utf-16", ignoreCase = true)) {
                throw TextDecodingException("$documentKey: 声明 utf-16 但缺少 BOM，无法确定字节序")
            }
            if (declared != null) {
                charset = try {
                    Charset.forName(declared)
                } catch (e: IllegalArgumentException) {
                    throw TextDecodingException("$documentKey: 声明了本机不支持的编码 $declared")
                }
            } else {
                if (bytes.size > 4 && bytes[0] == 0x3C.toByte() && bytes[1] == 0x00.toByte()) {
                    throw TextDecodingException("$documentKey: 疑似 UTF-16LE 却缺少 BOM")
                }
                if (bytes.size > 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x3C.toByte()) {
                    throw TextDecodingException("$documentKey: 疑似 UTF-16BE 却缺少 BOM")
                }
                charset = Charsets.UTF_8
            }
            payload = bytes
        }

        val text = try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(payload)).toString()
        } catch (e: CharacterCodingException) {
            throw TextDecodingException("$documentKey: 按 ${charset.name()} 解码失败（非法或截断的字节序列）")
        }

        if (CHARSET_FORBIDDEN.containsMatchIn(text)) {
            throw TextDecodingException("$documentKey: 文本含 XML 非法控制字符，编码声明与实际字节不一致")
        }
        return text
    }

    private fun detectBom(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8 to 3

        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charsets.UTF_16LE to 2

        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charsets.UTF_16BE to 2

        else -> null
    }

    /** 在字节层面定位 XML 声明里的 encoding，返回值未经校验。 */
    private fun declaredEncoding(bytes: ByteArray): String? {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 256))
        val ascii = buildString(head.size) {
            head.forEach { byte -> append(if (byte in 0x20..0x7E) byte.toInt().toChar() else ' ') }
        }
        return XML_DECLARATION.find(ascii)?.groupValues?.get(1)
    }

    // ------------------------------------------------------------------ 中央目录

    internal data class CentralEntry(val path: String, val declaredSize: Long)

    /**
     * 扫描 ZIP 中央目录，用于**在打开前**执行条目数上限、重复条目检测和声明总量上限。
     *
     * `java.util.zip.ZipFile` 无法报告重复条目（同名后者覆盖前者），所以这里直接读结构。
     * 返回 null 表示找不到 EOCD（不是 ZIP），抛 [IOException] 表示结构损坏。
     */
    internal fun readCentralDirectoryEntries(file: File): List<CentralEntry>? {
        val length = file.length()
        if (length < EOCD_MIN_BYTES) return null
        val tailLength = minOf(length, END_OF_CENTRAL_DIRECTORY_SEARCH_BYTES).toInt()
        val tail = littleEndian(readRange(file, length - tailLength, tailLength))

        var eocdIndex = -1
        var i = tailLength - EOCD_MIN_BYTES
        while (i >= 0) {
            if (tail.getInt(i) == EOCD_SIGNATURE) {
                eocdIndex = i
                break
            }
            i--
        }
        if (eocdIndex < 0) return null
        if (eocdIndex + EOCD_MIN_BYTES > tailLength) {
            throw IOException("中央目录结束记录被截断")
        }

        val entryCount = tail.getShort(eocdIndex + 10).toInt() and 0xFFFF
        val directorySize = tail.getInt(eocdIndex + 12).toLong() and 0xFFFFFFFFL
        val directoryOffset = tail.getInt(eocdIndex + 16).toLong() and 0xFFFFFFFFL
        if (directoryOffset == 0xFFFFFFFFL || entryCount == 0xFFFF) {
            throw IOException("ZIP64 容器不受支持")
        }
        if (directoryOffset + directorySize > length) {
            throw IOException("中央目录越出文件末尾")
        }

        val buffer = littleEndian(readRange(file, directoryOffset, directorySize.toInt()))
        val result = ArrayList<CentralEntry>(minOf(entryCount, 4096))
        // 以 EOCD 声明的条目数为界：中央目录里多出来的尾巴不作为条目。
        while (result.size < entryCount && buffer.remaining() >= CENTRAL_ENTRY_MIN_BYTES) {
            if (buffer.getInt() != CENTRAL_ENTRY_SIGNATURE) {
                throw IOException("中央目录条目签名不正确")
            }
            buffer.short // version made by
            buffer.short // version needed
            buffer.short // general purpose flag
            buffer.short // compression method
            buffer.short // mod time
            buffer.short // mod date
            buffer.int // crc32
            buffer.int // compressed size
            val uncompressedSize = buffer.int.toLong() and 0xFFFFFFFFL
            val nameLength = buffer.short.toInt() and 0xFFFF
            val extraLength = buffer.short.toInt() and 0xFFFF
            val commentLength = buffer.short.toInt() and 0xFFFF
            buffer.short // disk number start
            buffer.short // internal attributes
            buffer.int // external attributes
            buffer.int // local header offset
            if (buffer.remaining() < nameLength + extraLength + commentLength) {
                throw IOException("中央目录条目被截断")
            }
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            buffer.position(buffer.position() + extraLength + commentLength)
            result.add(CentralEntry(decodeEntryName(nameBytes), uncompressedSize))
        }
        return result
    }

    private fun decodeEntryName(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    /**
     * ZIP 的所有多字节字段都是**小端**，而 `ByteBuffer.wrap` 默认是大端。
     *
     * 少了这一行，签名比较会永远失败：真实的（以及夹具的）EPUB 都会被判成「不是有效的 ZIP 容器」。
     * 这是自动化用例抓到的实现缺陷，务必保留这个包装函数而不是在各处手写 `ByteBuffer.wrap`。
     */
    private fun littleEndian(bytes: ByteArray): java.nio.ByteBuffer =
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    private fun readRange(file: File, offset: Long, length: Int): ByteArray {
        if (length < 0) throw IOException("请求的读取长度非法: $length")
        val result = ByteArray(length)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(result)
        }
        return result
    }

    /**
     * 读满一个条目，且**以声明大小为硬界**。
     *
     * 返回 null 表示越界：ZIP 头声明 1KiB 却解压出 1GiB 时立即失败，不把内存吃光（ZIP 炸弹）。
     */
    private fun readCapped(stream: InputStream, declaredSize: Long, limit: Long): ByteArray? {
        val expected = if (declaredSize in 1..limit) declaredSize.toInt() else 0
        val out = ByteArrayOutputStream(if (expected > 0) expected else 64 * 1024)
        stream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (declaredSize > 0 && total > declaredSize) return null
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /** XHTML/OPF/NCX 等文本条目。 */
    private fun isDocumentPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") ||
            lower.endsWith(".opf") || lower.endsWith(".ncx") || lower.endsWith(".xml") ||
            lower.endsWith(".txt")
    }

    private fun isImagePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".svg") ||
            lower.endsWith(".bmp") || lower.endsWith(".avif")
    }

    private const val EOCD_SIGNATURE = 0x06054B50
    private const val CENTRAL_ENTRY_SIGNATURE = 0x02014B50
    private const val CENTRAL_ENTRY_MIN_BYTES = 46
    private const val EOCD_MIN_BYTES = 22
    private const val ZIP64_SENTINEL = 0xFFFFFFFFL
}
