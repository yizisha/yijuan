package com.flowreader.app.util.epub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.caverock.androidsvg.SVG
import com.flowreader.app.domain.model.DocumentRole
import com.flowreader.app.domain.model.PublicationDocument
import com.flowreader.app.domain.model.PublicationManifest
import com.flowreader.app.domain.model.ReadingSection
import com.flowreader.app.domain.model.SectionKind
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.SpineOccurrence
import com.flowreader.app.ui.screens.reader.components.svgIntrinsicSize
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * S5 收尾：EPUB 里的静态 SVG 插图支持（coil-svg 2.7.0 → 渲染器 androidsvg 1.4）。
 *
 * ## 为什么不在单测里跑 `ImageLoader.execute()`
 *
 * 试过，会**挂死**：Coil 2 的 `execute()` 内部依赖 `Dispatchers.Main`，而 Robolectric 默认是
 * PAUSED looper（主线程消息不自动执行）→ 测试线程等主线程、主线程等不到 looper，永久等待
 * （实测 30 分钟无任何进展，只能杀进程退出）。因此本文件只覆盖**不经过 Coil 主调度**的两件事：
 *
 * 1. 直接调 androidsvg 的解析/渲染入口，验证静态渲染与安全边界；
 * 2. 验证 SVG 资源在真实导入链路里落盘并可解析成本机路径。
 *
 * **Coil `SvgDecoder` 的端到端解码属于真机验收项**（真实 looper 上不存在该死锁），
 * 已登记在交接文档；不得用本文件的绿灯冒充"真机已能显示 SVG"。
 *
 * ## 安全边界（结构性依据，下面有断言）
 *
 * coil-svg 的渲染器是 **androidsvg —— 纯静态 SVG 渲染器，没有 JS 引擎**：`<script>` 元素
 * 被解析但从不执行。外链资源（`<image xlink:href="https://…">`）只有在**显式提供**
 * `SVGExternalFileResolver` 时才会被解析；本工程从不提供，因此没有可用网络通路，
 * 外链一律解析失败而非发起请求。下面的测试验证"含脚本/外链的 SVG 仍能被纯静态解析与渲染"。
 */
@RunWith(RobolectricTestRunner::class)
class SvgSupportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val publicationId = "pub-svg-0001"
    private val revision = "rev-svg-1"
    private val documentKey = "OEBPS/Text/ch1.xhtml"

    @After
    fun tearDown() {
        File(context.filesDir, "books/$publicationId").deleteRecursively()
        File(context.cacheDir, "svg-test").deleteRecursively()
        File(context.cacheDir, "svg-size").deleteRecursively()
    }

    private fun svgFile(name: String, svg: String): File =
        File(context.cacheDir, "svg-test/$name").apply {
            parentFile?.mkdirs()
            writeText(svg)
        }

    // ------------------------------------------------------------ 静态渲染（androidsvg 直调）

    @Test
    fun staticSvgParsesAndRenders() {
        val file = svgFile(
            "plain.svg",
            """
            <svg xmlns="http://www.w3.org/2000/svg" width="120" height="80" viewBox="0 0 120 80">
              <rect x="0" y="0" width="120" height="80" fill="#336699"/>
              <circle cx="60" cy="40" r="20" fill="#ffffff"/>
            </svg>
            """.trimIndent()
        )

        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        assertNotNull("静态 SVG 必须能解析", svg)
        assertEquals(120f, svg.documentWidth, 0.5f)
        assertEquals(80f, svg.documentHeight, 0.5f)
        // renderToPicture 会执行整套静态绘制管线；能返回即证明渲染路径成立。
        assertNotNull(svg.renderToPicture())
    }

    @Test
    fun scriptAndExternalReferencesAreIgnoredNotExecuted() {
        // 恶意夹具：内联脚本 + 外链图片。androidsvg 无 JS 引擎（脚本被忽略），
        // 且未注册 SVGExternalFileResolver（外链无解析器可用 → 不会发起任何网络请求）。
        val file = svgFile(
            "malicious.svg",
            """
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
                 width="100" height="100" viewBox="0 0 100 100">
              <script>alert('should never run');</script>
              <rect width="100" height="100" fill="#222222"/>
              <image xlink:href="https://example.invalid/tracker.png" x="0" y="0" width="10" height="10"/>
            </svg>
            """.trimIndent()
        )

        // 解析与渲染都必须正常完成：脚本既不执行也不报错，外链既不下载也不把失败传播出去。
        val svg = file.inputStream().use { SVG.getFromInputStream(it) }
        assertNotNull("含脚本/外链的静态 SVG 仍应被静态渲染", svg)
        assertNotNull(svg.renderToPicture())
        assertEquals(100f, svg.documentWidth, 0.5f)
    }

    @Test
    fun brokenSvgThrowsForTheCallerToHandleInsteadOfCrashingTheApp() {
        val file = svgFile("broken.svg", "<svg width='10' height='10'><rect")

        // 解析器抛出可捕获异常 —— Coil 的 SvgDecoder 会把它转成 ErrorResult，
        // 渲染层显示占位（ReaderImage 的 error 槽位），不会炸掉阅读器。
        val thrown = runCatching { file.inputStream().use { SVG.getFromInputStream(it) } }.exceptionOrNull()
        assertNotNull("坏 SVG 必须是可捕获的异常，而不是崩溃或静默成功", thrown)
        assertTrue(thrown is Exception)
    }

    // ------------------------------------------------------------ 占位几何：svgIntrinsicSize

    @Test
    fun intrinsicSizePrefersWidthHeightThenFallsBackToViewBox() {
        val dir = File(context.cacheDir, "svg-size").apply { mkdirs() }

        fun sizeFor(svg: String): Pair<Float, Float>? {
            val file = File(dir, "s${System.nanoTime()}.svg").apply { writeText(svg) }
            return svgIntrinsicSize(file.absolutePath)
        }

        assertEquals(120f to 80f, sizeFor("""<svg width="120" height="80" viewBox="0 0 10 10"/>"""))
        // width/height 带单位也可解析。
        assertEquals(512f to 256f, sizeFor("""<svg width="512pt" height="256mm"/>"""))
        // 百分比不可用 → 回退 viewBox。
        assertEquals(300f to 150f, sizeFor("""<svg width="100%" height="100%" viewBox="0 0 300 150"/>"""))
        // 什么都没有 → null（调用方退回占位高度）。
        assertNull(sizeFor("""<svg xmlns="http://www.w3.org/2000/svg"/>"""))
        assertNull(sizeFor("not svg at all"))
    }

    // ------------------------------------------------------------ 导入链路：SVG 资源落盘

    @Test
    fun svgResourcesSurviveTheImportAndResolveToLocalPaths() {
        val store = PublicationStore(context)
        val body = """<p>正文</p>"""
        val document = EpubDocumentParser.parse(
            documentKey,
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>"""
        )
        val manifest = PublicationManifest(
            publicationId = publicationId,
            revision = revision,
            normalizationVersion = 1,
            parserSchemaVersion = 1,
            sourceHash = "svg-hash",
            manifestRelativePath = "files/books/$publicationId/revisions/$revision",
            spineOccurrences = listOf(
                SpineOccurrence(
                    occurrenceId = ReadingManifestBuilder.occurrenceId("OEBPS/content.opf", 0),
                    ordinal = 0,
                    documentKey = documentKey,
                    linear = true,
                    mediaType = "application/xhtml+xml",
                    counted = true
                )
            ),
            documents = listOf(
                PublicationDocument(
                    documentKey = documentKey,
                    ordinal = 0,
                    linear = true,
                    role = DocumentRole.MAIN,
                    textLengthUtf16 = document.textLengthUtf16,
                    mainTextUnits = document.mainTextUnits,
                    manifestRelativePath = "documents/x.json",
                    sourceHash = "doc-hash"
                )
            ),
            sections = listOf(
                ReadingSection(
                    sectionId = "s-0",
                    ordinal = 0,
                    title = "第 1 章",
                    aliases = emptyList(),
                    kind = SectionKind.NORMAL,
                    ranges = listOf(SourceRange(documentKey, 0, document.textLengthUtf16)),
                    mainTextUnits = document.mainTextUnits
                )
            ),
            tocTargetSections = emptyMap(),
            totalMainTextUnits = document.mainTextUnits,
            resources = emptyList()
        )

        val staged = store.stage(
            manifest = manifest,
            documents = mapOf(documentKey to document),
            sourceBytes = "<epub/>".toByteArray(),
            resourceBytes = mapOf("OEBPS/Images/fig.svg" to "<svg/>".toByteArray())
        ).getOrThrow()
        store.commitFiles(staged).getOrThrow()

        // 媒体类型识别（5.2）：svg 必须标成 image/svg+xml。
        assertEquals("image/svg+xml", staged.resources.single().mediaType)
        val index = store.readPackageIndex(publicationId, revision)!!
        val resolved = index.resourcePaths["OEBPS/Images/fig.svg"]
        assertNotNull("SVG 资源必须能从包索引解析成本机路径", resolved)
        assertTrue(File(resolved!!).isFile)
    }
}
