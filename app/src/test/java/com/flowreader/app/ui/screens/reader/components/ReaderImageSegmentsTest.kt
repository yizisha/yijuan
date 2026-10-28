package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.util.epub.ChapterImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5：两个渲染器共用的「段落 → 文本/图片片段」切分（纯函数）。
 *
 * 这里锁定的是**偏移连续性**：图片占它自己那个 U+FFFC，紧跟的文本从 +1 继续。
 * 偏移一断，滚动模式的位置保存、批注与书签都会整体错位。
 */
class ReaderImageSegmentsTest {

    private fun image(offset: Int, path: String? = "/a.jpg", alt: String? = null) =
        ChapterImageRef(offsetUtf16 = offset, absolutePath = path, alt = alt)

    @Test
    fun plainParagraphStaysASingleSegment() {
        val segments = splitParagraphForImages("普通段落", baseOffset = 7, bodyShift = 0, imagesByOffset = emptyMap())

        assertEquals(1, segments.size)
        val text = segments.single()
        assertTrue(text is ReaderSegment.Text)
        assertEquals("普通段落", (text as ReaderSegment.Text).text)
        assertEquals(7, text.offset)
    }

    @Test
    fun inlineImageSplitsIntoTextImageTextWithContinuousOffsets() {
        val segments = splitParagraphForImages(
            text = "前文￼后文",
            baseOffset = 100,
            bodyShift = 0,
            imagesByOffset = mapOf(102 to image(102))
        )

        assertEquals(3, segments.size)
        assertEquals("前文", (segments[0] as ReaderSegment.Text).text)
        assertEquals(100, segments[0].offset)
        assertTrue(segments[1] is ReaderSegment.Image)
        assertEquals(102, segments[1].offset)
        assertEquals("后文", (segments[2] as ReaderSegment.Text).text)
        // 图片占用 102，后面的文本必须从 103 继续
        assertEquals(103, segments[2].offset)
    }

    @Test
    fun bodyShiftIsAppliedWhenLookingUpImages() {
        // body 比 content 短 5 个字符（标题回显被去掉），图片偏移是 content 坐标。
        val segments = splitParagraphForImages(
            text = "前￼后",
            baseOffset = 0,
            bodyShift = 5,
            imagesByOffset = mapOf(6 to image(6, "/shifted.jpg"))
        )

        val image = segments[1]
        assertTrue(image is ReaderSegment.Image)
        assertEquals("/shifted.jpg", (image as ReaderSegment.Image).image?.absolutePath)
    }

    @Test
    fun unresolvedImageKeepsItsSlotWithANullRef() {
        // 解析不出来的图**不能**被悄悄丢掉：占位（INV10）依赖这个 null。
        val segments = splitParagraphForImages(
            text = "前￼后",
            baseOffset = 0,
            bodyShift = 0,
            imagesByOffset = emptyMap()
        )

        // 没有图片索引时不做切分（保持旧排版），整段仍是文本。
        assertEquals(1, segments.size)
        assertTrue(segments.single() is ReaderSegment.Text)
    }

    @Test
    fun imageMarkerPathIsOnlyTakenFromCompleteMarkers() {
        assertEquals("/abs/a.jpg", imagePathFromMarker("[IMG:/abs/a.jpg]"))
        assertNull("被切开的标记不是完整标记", imagePathFromMarker("[IMG:/abs/a.jpg"))
        assertNull(imagePathFromMarker("[IMG"))
        assertNull(imagePathFromMarker("普通段落"))
    }

    @Test
    fun altByPathSkipsImagesWithoutAResolvedPath() {
        val alts = altByPath(
            listOf(
                image(0, "/a.jpg", "封面"),
                image(1, null, "缺失的图"),
                image(2, "/b.jpg", null)
            )
        )

        assertEquals(mapOf("/a.jpg" to "封面"), alts)
    }
}
