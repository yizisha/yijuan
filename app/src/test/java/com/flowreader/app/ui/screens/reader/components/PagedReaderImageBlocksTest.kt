package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.util.epub.ChapterImageRef
import com.flowreader.feature.reader.ChapterPaginator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5：规范源文本里的 U+FFFC 必须变成**独立的图片块**，而文本段之间的偏移必须连续。
 *
 * 偏移一断，批注 / 书签 / 进度就会整体错位；图片块漏掉，插图就不显示（旧行为）。
 * 这里锁定的是「章内坐标」这条底线，而不是渲染长相（真机才能看）。
 */
class PagedReaderImageBlocksTest {

    @Test
    fun inlineImageBecomesItsOwnBlockAndOffsetsStayContinuous() {
        val content = "前文￼后文"
        val blocks = buildChapterBlocks(
            title = "T",
            content = content,
            indentEnabled = true,
            images = listOf(ChapterImageRef(offsetUtf16 = 2, absolutePath = "/abs/a.jpg", alt = null))
        )

        assertEquals(4, blocks.size)
        assertEquals("T", blocks[0].text)
        assertEquals("前文", blocks[1].text)
        assertEquals(0, blocks[1].offset)
        assertEquals("[IMG:/abs/a.jpg]", blocks[2].text)
        assertEquals(2, blocks[2].offset)
        assertFalse("图片块不是段落，不能参与缩进", blocks[2].paragraph)
        assertEquals("后文", blocks[3].text)
        assertEquals("图片占一个偏移，后面的文本必须从 +1 继续", 3, blocks[3].offset)
    }

    @Test
    fun textBlocksNeverContainTheObjectMarker() {
        val blocks = buildChapterBlocks(
            title = "T",
            content = "甲￼乙￼丙",
            indentEnabled = false,
            images = listOf(
                ChapterImageRef(1, "/a.jpg", null),
                ChapterImageRef(3, "/b.jpg", null)
            )
        )

        val text = blocks.filter { !it.isTitle && it.paragraph }.joinToString("") { it.text }
        assertFalse("图片块以外的文本里不得残留 U+FFFC", text.contains('\uFFFC'))
        assertEquals("甲乙丙", text)
        assertEquals(listOf("[IMG:/a.jpg]", "[IMG:/b.jpg]"), blocks.filter { !it.paragraph }.map { it.text })
    }

    @Test
    fun missingResourceStillGetsItsSlotSoTheUiCanShowAPlaceholder() {
        val blocks = buildChapterBlocks(
            title = "T",
            content = "前￼后",
            indentEnabled = false,
            images = listOf(ChapterImageRef(1, null, alt = "封面"))
        )

        val image = blocks.single { !it.paragraph }
        // 空路径 = ReaderImage 显示"损坏"占位（INV10：不得静默丢图）
        assertEquals("[IMG:]", image.text)
        assertEquals(1, image.offset)
    }

    @Test
    fun chapterWithoutImagesKeepsPreviousBehaviour() {
        val blocks = buildChapterBlocks("T", "前￼后", indentEnabled = false, images = emptyList())

        assertTrue("没有图片索引时不得凭空插入图片块", blocks.none { !it.paragraph })
    }

    @Test
    fun imageKeyTracksBothOffsetAndResolution() {
        assertEquals("", imageKeyOf(emptyList()))
        assertEquals("5:/a.jpg", imageKeyOf(listOf(ChapterImageRef(5, "/a.jpg", null))))
        // 同一偏移但资源没解析出来，必须是不同的键（否则会复用一个没图的旧排版）
        assertTrue(
            imageKeyOf(listOf(ChapterImageRef(5, "/a.jpg", null))) !=
                imageKeyOf(listOf(ChapterImageRef(5, null, null)))
        )
    }

    // ---------------------------------------------------------------- 分页器不得切开图片块

    @Test
    fun completeImageMarkerIsMeasuredFromTheImage() {
        val height = measureImageAwareText("[IMG:/abs/a.jpg]", isTitle = false) { 321 }

        assertEquals(321, height)
    }

    @Test
    fun everyTruncatedMarkerPrefixIsReportedAsUnfittable() {
        // 0.2.9 真机缺陷：分页器把图片块当文本切开 -> 片段不以 ] 结尾 ->
        // 退回按普通段落测量 -> "成功"切出一段绝对路径并被当正文渲染。
        //
        // 0.2.10 的修复**漏掉了比 "[IMG:" 更短的前缀**，而 findSplit 是二分搜索，
        // 真的会试探 "[IMG" / "[IM" / "[I" / "["。真机现象因此变成
        // 正文里出现 "[IMG" 与 ":/data/user/0/…" 两段文字（分到两页）。
        val prefixes = listOf("[", "[I", "[IM", "[IMG", "[IMG:", "[IMG:/data", "[IMG:/abs/a.jpg")
        prefixes.forEach { prefix ->
            val height = measureImageAwareText(prefix, isTitle = false) { 321 }
            assertEquals("前缀 \"$prefix\" 必须报放不下", UNSPLITTABLE_IMAGE_HEIGHT_PX, height)
            assertTrue("必须远大于一页，分页器才会整块换页", height!! > 10_000)
        }
        assertTrue("短于 [IMG: 的前缀也必须被识别", isImageMarkerFragment("[IMG"))
        assertFalse("普通文本不能被误判", isImageMarkerFragment("正文"))
    }

    @Test
    fun theRuleLeavesPlainTextAndTitlesAlone() {
        assertNull(measureImageAwareText("普通段落", isTitle = false) { 321 })
        // 标题即使以 [IMG: 开头也按标题走（章标题不可能是图片块）
        assertNull(measureImageAwareText("[IMG:x]", isTitle = true) { 321 })
    }

    @Test
    fun anImageThatDoesNotFitTheRemainingPageMovesWholeToTheNextPage() {
        val blocks = buildChapterBlocks(
            title = "T",
            content = "文字￼",
            indentEnabled = false,
            images = listOf(ChapterImageRef(offsetUtf16 = 2, absolutePath = "/abs/a.jpg", alt = null))
        )
        // 与生产代码同一条测量规则：完整标记按图高，被切开的标记报"放不下"。
        val paginator = ChapterPaginator { text, _, _, isTitle ->
            measureImageAwareText(text, isTitle) { 100 } ?: 60
        }

        // 一页 200：标题 60 + 正文 60 之后只剩 80，放不下 100 的图。
        // 这样才会**真的走到 findSplit** —— 0.2.10 的测试用了"剩余为 0"的几何，
        // findSplit 根本没被调用，于是漏掉了这个缺陷（教训：测试要走到那条分支）。
        val pages = paginator.paginate(blocks, pageHeightPx = 200, gapPx = 0)

        assertEquals("图放不下整页剩余空间时应换页，而不是被切开", 2, pages.size)
        assertEquals(listOf("T", "文字"), pages[0].map { it.text })
        assertEquals("[IMG:/abs/a.jpg]", pages[1].single().text)
        // 任何一页都不允许出现"被切开的图片标记"（那就是真机上泄漏成路径文字的样子）
        assertTrue(
            "分页结果里不得有被切开的图片标记",
            pages.flatten().none { isImageMarkerFragment(it.text) && !it.text.endsWith("]") }
        )
    }
}
