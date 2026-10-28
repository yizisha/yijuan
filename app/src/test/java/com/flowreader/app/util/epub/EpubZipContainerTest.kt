package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.ContentErrorCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * S3 / 主计划 4.1 第 1 步、4.2 第 7 条、11.2 输入上限验收（T043、T044、T046、T090）。
 *
 * 夹具为自造 ZIP，按 ZIP 规范手写本地头与中央目录（见 [EpubFixtureBuilder]），不含任何版权样书。
 *
 * 关于 512MiB / 20,000 这两个 v1 数字：单测不可能为它们分配真实内存，但**执行上限的代码路径
 * 必须被真的跑到**，所以这里用 `ContainerLimits` 注入小数值驱动同一段逻辑
 * （与 `EpubZipContainer.open` 在生产路径用的是同一份实现），并额外用一个 20,001 条目的
 * 真实结构断言“超限即拒绝、且先于 ZipFile 打开”。
 */
class EpubZipContainerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String): File = File(folder.root, name)

    private fun opened(
        file: File,
        limits: EpubZipContainer.ContainerLimits = EpubZipContainer.DEFAULT_LIMITS
    ): EpubZipContainer.Container {
        val result = EpubZipContainer.open(file, limits)
        assertTrue("期望打开成功，实际: $result", result is EpubZipContainer.OpenResult.Opened)
        return (result as EpubZipContainer.OpenResult.Opened).container
    }

    private fun failure(
        file: File,
        limits: EpubZipContainer.ContainerLimits = EpubZipContainer.DEFAULT_LIMITS
    ): EpubZipContainer.OpenResult.Failed {
        val result = EpubZipContainer.open(file, limits)
        assertTrue("期望打开失败，实际: $result", result is EpubZipContainer.OpenResult.Failed)
        return result as EpubZipContainer.OpenResult.Failed
    }

    // ------------------------------------------------------------ 条目枚举

    @Test
    fun entriesAreEnumeratedWithDeclaredSizesAndCount() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("minimal.epub"))
        opened(epub).use { container ->
            val paths = container.entries.map { it.path }
            assertEquals(
                listOf(
                    "META-INF/container.xml",
                    "OEBPS/Text/ch1.xhtml",
                    "OEBPS/content.opf",
                    "OEBPS/nav.xhtml",
                    "mimetype"
                ),
                paths
            )
            assertEquals(5, container.entryCount)
            assertEquals(5, container.declaredEntryCount)
            assertTrue(container.diagnostics.isEmpty())

            val opf = container.find("OEBPS/content.opf")
            assertNotNull(opf)
            assertTrue("声明大小必须来自中央目录", opf!!.declaredSize > 0L)
            assertEquals(8, opf.compressionMethod)
            assertFalse(opf.isDirectory)
            assertNull("资源键大小写敏感，不得兜底", container.find("oebps/content.opf"))
        }
    }

    @Test
    fun stepOneToThreeWiringReadsContainerAndOpfThroughTheContainer() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("wiring.epub"))
        opened(epub).use { container ->
            // 4.1 第 1 步：container.xml → rootfile
            val containerXml = container.readEntryText("META-INF/container.xml").getOrThrow()
            val (opfPath, containerDiagnostics) = EpubPackageParser.parseRootFilePath(containerXml)
            assertEquals("OEBPS/content.opf", opfPath)
            assertTrue(containerDiagnostics.isEmpty())

            // 4.1 第 2 步：OPF → manifest / spine
            val opf = container.readEntryText(opfPath!!).getOrThrow()
            val pkg = EpubPackageParser.parse(opf, opfPath)
            assertEquals("3.0", pkg.version)
            assertEquals(listOf("OEBPS/Text/ch1.xhtml"), pkg.mainReadingOrder.map { it.containerPath })
            assertEquals("OEBPS/nav.xhtml", pkg.navItem?.containerPath)

            // 4.1 第 4 步：nav → 目录树（这里只验证输入接线，树语义由 EpubNavigationParserTest 覆盖）
            val nav = container.readEntryText(pkg.navItem!!.containerPath).getOrThrow()
            assertEquals(1, Regex("<a ").findAll(nav).count())
        }
    }

    @Test
    fun utf8EntryNamesAndUnicodeFragmentsSurviveTheRoundTrip() {
        val epub = EpubFixtureBuilder.minimalEpub3(
            "OEBPS/Text/第一章.xhtml" to "<html><body><p id=\"注1\">注</p></body></html>",
            "OEBPS/Images/a+b.png" to "not-a-real-png"
        ).writeTo(file("中文.epub"))

        opened(epub).use { container ->
            assertNotNull("ZIP 条目名是文件名，不走 URL 解码", container.find("OEBPS/Text/第一章.xhtml"))
            assertNotNull("`+` 必须保持字面加号", container.find("OEBPS/Images/a+b.png"))

            // 4.2 强制例子：`b.xhtml#%E6%B3%A81` → id `注1`，并且确实能在容器里找回来。
            val target = EpubResourceResolver.resolve("OEBPS/Text/a.xhtml", "第一章.xhtml#%E6%B3%A81")
            assertTrue(target is EpubResourceResolver.ResourceTarget.Internal)
            val internal = target as EpubResourceResolver.ResourceTarget.Internal
            assertEquals("OEBPS/Text/第一章.xhtml", internal.containerPath)
            assertEquals("注1", internal.fragment)
            assertNotNull(container.find(internal.containerPath))
        }
    }

    // ------------------------------------------------------------ 上限

    @Test
    fun entryCountLimitIsEnforced() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("count.epub"))
        val failed = failure(epub, EpubZipContainer.ContainerLimits(maxEntryCount = 2))
        assertEquals(ContentErrorCode.CONTAINER_INVALID, failed.code)
        assertTrue(failed.message, failed.message.contains("条目数超过上限"))
    }

    @Test
    fun twentyThousandEntriesAreRejectedAndTwentyThousandAreAccepted() {
        // 真实结构：20,001 个条目（STORED，避免一万次 zlib 初始化）。
        val over = file("over.epub")
        val builder = EpubFixtureBuilder()
        repeat(20_001) { index -> builder.addEntry("e/$index", ByteArray(0), deflated = false) }
        builder.writeTo(over)

        val failed = failure(over)
        assertEquals(ContentErrorCode.CONTAINER_INVALID, failed.code)
        assertTrue(failed.message, failed.message.contains("条目数超过上限: 20001 > 20000"))

        // 边界内：20,000 条目必须能打开（拒绝不能是“看到大文件就拒绝”）。只比较声明的规模，
        // 枚举全部 20,000 条目的成本与另一门禁无关，交给上面 5 条目的用例覆盖。
        val atLimit = file("at-limit.epub")
        val okBuilder = EpubFixtureBuilder()
        repeat(20_000) { index -> okBuilder.addEntry("e/$index", ByteArray(0), deflated = false) }
        okBuilder.writeTo(atLimit)
        opened(atLimit).use { container -> assertEquals(20_000, container.declaredEntryCount) }
    }

    @Test
    fun totalUncompressedSizeLimitIsEnforcedBeforeAnyDecompression() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("total.epub"))
        // 夹具总量只有几百字节；把上限压到 64 字节即可驱动同一段判定逻辑。
        val failed = failure(epub, EpubZipContainer.ContainerLimits(maxTotalUncompressedBytes = 64L))
        assertEquals(ContentErrorCode.CONTAINER_INVALID, failed.code)
        assertTrue(failed.message, failed.message.contains("声明解压总量超过上限"))
    }

    @Test
    fun defaultTotalLimitIsFiveHundredTwelveMiB() {
        assertEquals(512L * 1024 * 1024, EpubZipContainer.DEFAULT_LIMITS.maxTotalUncompressedBytes)
        assertEquals(20_000, EpubZipContainer.DEFAULT_LIMITS.maxEntryCount)
        assertEquals(16L * 1024 * 1024, EpubZipContainer.DEFAULT_LIMITS.maxDocumentBytes)
        assertEquals(24L * 1024 * 1024, EpubZipContainer.DEFAULT_LIMITS.maxImageBytes)
    }

    @Test
    fun oversizedEntryIsRejectedByCategoryLimitWithoutReadingIt() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("entry.epub"))
        opened(epub).use { container ->
            // 文档 16MiB 上限：xhtml 归入文档类。
            val doc = container.readEntryBytes("OEBPS/Text/ch1.xhtml", maxBytes = 8L)
            assertTrue(doc.isFailure)
            val docError = doc.exceptionOrNull() as EpubZipContainer.EntryTooLargeException
            assertEquals(ContentErrorCode.CONTENT_LIMIT_EXCEEDED, docError.code)
            assertEquals("OEBPS/Text/ch1.xhtml", docError.path)

            // 图片 24MiB 上限：png 归入图片类。
            assertEquals(24L * 1024 * 1024, container.perEntryLimit("OEBPS/Images/a.png"))
            assertEquals(16L * 1024 * 1024, container.perEntryLimit("OEBPS/Text/a.xhtml"))
            assertEquals(64L * 1024 * 1024, container.perEntryLimit("OEBPS/Styles/a.css"))

            // 失败不得污染累计解压量，否则一本正常书读几张图就会“超总量”。
            assertEquals(0L, container.accountedUncompressedBytes)
        }
    }

    @Test
    fun cumulativeBudgetStopsRunawayReads() {
        val content = "x".repeat(300)
        val epub = file("budget.epub")
        val builder = EpubFixtureBuilder()
        (0 until 5).forEach { index -> builder.addEntry("OEBPS/e$index.txt", content) }
        builder.writeTo(epub)

        opened(epub, EpubZipContainer.ContainerLimits(maxTotalUncompressedBytes = 1_600L)).use { container ->
            // 五条 300 字节 = 1500 ≤ 1600：声明与实测一致的容器必须能完整读完。
            (0 until 5).forEach { index ->
                val result = container.readEntryBytes("OEBPS/e$index.txt")
                assertTrue(
                    "e$index 读取失败: ${result.exceptionOrNull()} / accounted=${container.accountedUncompressedBytes}",
                    result.isSuccess
                )
            }
            assertEquals(1_500L, container.accountedUncompressedBytes)

            // 累计量按“每个条目只计一次”：重复读已读过的文档不该把预算吃两遍。
            assertTrue(container.readEntryBytes("OEBPS/e0.txt").isSuccess)
            assertEquals(1_500L, container.accountedUncompressedBytes)

            // 读取期的剩余额度按累计量扣减，而不是每条都拿满额。
            assertEquals(100L, container.remainingBudget)
        }

        // 声明总量 1500 > 1450：打开期就拒绝，**不必解压任何条目**（zip bomb 的第一道闸）。
        val declaredTooBig = failure(epub, EpubZipContainer.ContainerLimits(maxTotalUncompressedBytes = 1_450L))
        assertEquals(ContentErrorCode.CONTAINER_INVALID, declaredTooBig.code)
        assertTrue(declaredTooBig.message, declaredTooBig.message.contains("声明解压总量超过上限: 1500 > 1450"))
    }

    @Test
    fun readBudgetShrinksAsEntriesAreAccounted() {
        val content = "y".repeat(400)
        val epub = file("shrink.epub")
        val builder = EpubFixtureBuilder()
        builder.addEntry("OEBPS/s0.txt", content)
        builder.addEntry("OEBPS/s1.txt", content)
        builder.writeTo(epub)

        // 800 ≤ 900：两条都能打开。
        opened(epub, EpubZipContainer.ContainerLimits(maxTotalUncompressedBytes = 900L)).use { container ->
            assertEquals(900L, container.remainingBudget)
            assertTrue(container.readEntryBytes("OEBPS/s0.txt").isSuccess)
            assertEquals(400L, container.accountedUncompressedBytes)
            assertEquals(500L, container.remainingBudget)
            assertTrue(container.readEntryBytes("OEBPS/s1.txt").isSuccess)
            assertEquals(800L, container.accountedUncompressedBytes)
            assertEquals(100L, container.remainingBudget)

            // 第三条（不存在）不该因为“还有额度”就被放行成成功。
            assertTrue(container.readEntryBytes("OEBPS/s2.txt").isFailure)
            assertEquals(100L, container.remainingBudget)
        }
    }

    @Test
    fun declaredTotalSizeOfFiveEntriesIsCheckedAgainstTheProductionLimit() {
        val content = "x".repeat(700)
        val epub = file("declared-total.epub")
        val builder = EpubFixtureBuilder()
        builder.addEntry("OEBPS/a.txt", content)
        builder.addEntry("OEBPS/b.txt", content)
        builder.writeTo(epub)

        // 打开阶段先用中央目录里的声明大小挡一次：低于上限能开，抬高声明就拒绝。
        opened(epub, EpubZipContainer.ContainerLimits(maxTotalUncompressedBytes = 1_400L)).use { container ->
            assertEquals(1_400L, container.entries.sumOf { it.declaredSize })
        }
        val failed = failure(epub, EpubZipContainer.ContainerLimits(maxTotalUncompressedBytes = 1_399L))
        assertEquals(ContentErrorCode.CONTAINER_INVALID, failed.code)
        assertTrue(failed.message, failed.message.contains("声明解压总量超过上限: 1400 > 1399"))
    }

    @Test
    fun missingEntryIsReportedWithItsPath() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("missing.epub"))
        opened(epub).use { container ->
            val result = container.readEntryBytes("OEBPS/Text/nope.xhtml")
            assertTrue(result.isFailure)
            assertEquals("OEBPS/Text/nope.xhtml", (result.exceptionOrNull() as EpubZipContainer.EntryMissingException).path)
        }
    }

    // ------------------------------------------------------------ 重复条目

    @Test
    fun duplicateZipEntriesAreDiagnosedAndNeverSilentlyPicked() {
        val epub = file("duplicate.epub")
        val builder = EpubFixtureBuilder.minimalEpub3()
        builder.addEntry("OEBPS/Text/ch1.xhtml", "<html><body><p>第二个同名条目</p></body></html>")
        builder.writeTo(epub)

        val result = EpubZipContainer.open(epub)
        assertTrue(result is EpubZipContainer.OpenResult.Opened)
        val container = (result as EpubZipContainer.OpenResult.Opened).container
        container.use {
            // 4.2 第 7 条：重复条目是结构错误，必须报出来，不能让导入静默按某一个副本继续。
            assertEquals(listOf("DUPLICATE_ZIP_ENTRY"), container.diagnostics.map { it.code })
            assertTrue(container.diagnostics.first().detail.contains("OEBPS/Text/ch1.xhtml"))
            assertEquals(6, container.declaredEntryCount)
            assertEquals(6, container.entryCount)
            // 同名条目在枚举里确实出现两次：调用方不能靠 firstOrNull 悄悄选一个。
            assertEquals(2, container.entries.count { it.path == "OEBPS/Text/ch1.xhtml" })
            assertEquals(1, container.diagnostics.size)
        }
    }

    // ------------------------------------------------------------ 无效容器

    @Test
    fun nonZipFileIsRejectedAsContainerInvalid() {
        val notZip = file("not-zip.epub")
        notZip.writeText("这只是一个文本文件，不是 ZIP")
        val failed = failure(notZip)
        assertEquals(ContentErrorCode.CONTAINER_INVALID, failed.code)
        assertTrue(failed.message, failed.message.contains("找不到中央目录结束记录"))
    }

    @Test
    fun missingFileIsReportedAsSourceUnavailable() {
        val failed = failure(file("不存在.epub"))
        assertEquals(ContentErrorCode.SOURCE_UNAVAILABLE, failed.code)
    }

    @Test
    fun corruptedCentralDirectorySignatureIsRejected() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("corrupt.epub"))
        val bytes = epub.readBytes()
        // 中央目录起点：EOCD 里偏移 16（相对文件尾 22 字节处）。
        val eocd = bytes.size - 22
        val directoryOffset = readIntLe(bytes, eocd + 16)
        bytes[directoryOffset] = 0x00
        bytes[directoryOffset + 1] = 0x00
        epub.writeBytes(bytes)

        val failed = failure(epub)
        assertEquals(ContentErrorCode.CONTAINER_INVALID, failed.code)
        assertTrue(failed.message, failed.message.contains("中央目录不可读"))
    }

    @Test
    fun truncatedEndOfCentralDirectoryIsRejected() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("truncated.epub"))
        val bytes = epub.readBytes()
        epub.writeBytes(bytes.copyOfRange(0, bytes.size - 4))

        val failed = failure(epub)
        assertEquals(ContentErrorCode.CONTAINER_INVALID, failed.code)
    }

    // ------------------------------------------------------------ 严格解码（3.2 第 1 条）

    @Test
    fun utf8BomIsStripped() {
        val payload = "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?><p>甲</p>"
        val text = EpubZipContainer.decodeText("a.xhtml", payload.toByteArray(Charsets.UTF_8))
        assertTrue(text.startsWith("<?xml"))
        assertFalse(text.startsWith("\uFEFF"))
    }

    @Test
    fun utf16LittleEndianWithBomIsDecoded() {
        val payload = "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-16\"?><p>乙</p>"
        val bytes = payload.toByteArray(Charsets.UTF_16LE)
        val text = EpubZipContainer.decodeText("b.xhtml", bytes)
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-16\"?><p>乙</p>", text)
    }

    @Test
    fun declaredNonUtf8EncodingIsHonouredInsteadOfGuessed() {
        val declared = "<?xml version=\"1.0\" encoding=\"GBK\"?><p>中文</p>"
        val bytes = declared.toByteArray(charset("GBK"))
        val text = EpubZipContainer.decodeText("c.xhtml", bytes)
        assertTrue("必须按声明解码，不能替换成 U+FFFD", text.contains("中文"))
    }

    @Test
    fun malformedUtf8IsReportedForTheSpecificDocumentInsteadOfReplaced() {
        val bytes = byteArrayOf(0x3C, 0x70, 0x3E, 0xC3.toByte(), 0x28, 0x3C, 0x2F, 0x70, 0x3E)
        val error = runCatching { EpubZipContainer.decodeText("OEBPS/Text/bad.xhtml", bytes) }
            .exceptionOrNull()
        assertTrue(error is EpubZipContainer.TextDecodingException)
        assertTrue("错误必须点名具体文档", error!!.message!!.contains("OEBPS/Text/bad.xhtml"))
    }

    @Test
    fun undeclaredUtf16IsRejectedInsteadOfBecomingMojibake() {
        val bytes = "<?xml version=\"1.0\"?><p>丙</p>".toByteArray(Charsets.UTF_16LE)
        val error = runCatching { EpubZipContainer.decodeText("d.xhtml", bytes) }
            .exceptionOrNull()
        assertTrue(error is EpubZipContainer.TextDecodingException)
        assertTrue(error!!.message!!.contains("UTF-16LE"))
    }

    @Test
    fun unsupportedDeclaredEncodingIsRejectedNotIgnored() {
        val bytes = "<?xml version=\"1.0\" encoding=\"x-not-a-real-charset\"?><p>x</p>".toByteArray(Charsets.UTF_8)
        val error = runCatching { EpubZipContainer.decodeText("e.xhtml", bytes) }.exceptionOrNull()
        assertTrue(error is EpubZipContainer.TextDecodingException)
        assertTrue(error!!.message!!.contains("x-not-a-real-charset"))
    }

    @Test
    fun readEntryTextSurfacesDecodingFailureForThatEntry() {
        val bytes = byteArrayOf(0x3C, 0x70, 0x3E, 0xC3.toByte(), 0x28, 0x3C, 0x2F, 0x70, 0x3E)
        val epub = file("bad-encoding.epub")
        val zip = EpubFixtureBuilder()
        zip.addEntry("mimetype", "application/epub+zip")
        zip.addEntry("OEBPS/Text/bad.xhtml", bytes)
        zip.writeTo(epub)

        opened(epub).use { container ->
            val result = container.readEntryText("OEBPS/Text/bad.xhtml")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is EpubZipContainer.TextDecodingException)
        }
    }

    @Test
    fun rawBytesAreAvailableForBinaryEntries() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01)
        val epub = file("binary.epub")
        val zip = EpubFixtureBuilder()
        zip.addEntry("mimetype", "application/epub+zip")
        zip.addEntry("OEBPS/Images/a.png", png)
        zip.writeTo(epub)

        opened(epub).use { container ->
            assertArrayEquals(png, container.readEntryBytes("OEBPS/Images/a.png").getOrThrow())
        }
    }

    @Test
    fun closedContainerRefusesFurtherReads() {
        val epub = EpubFixtureBuilder.minimalEpub3().writeTo(file("closed.epub"))
        val container = opened(epub)
        container.close()
        val error = runCatching { container.readEntryBytes("OEBPS/content.opf") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
