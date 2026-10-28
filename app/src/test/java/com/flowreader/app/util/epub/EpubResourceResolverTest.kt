package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.ContentErrorCode
import com.flowreader.app.util.epub.EpubResourceResolver.ResourceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 / 主计划 4.2 的**强制例子**逐条验收（T043—T047）。
 *
 * 计划原文给出的六条必须成立：
 * ```
 * Text/a.xhtml + ../Images/a+b.png      → Images/a+b.png
 * Text/a.xhtml + ../Images/a%20b.png    → Images/a b.png
 * Text/a.xhtml + #n1                    → 同文档 id n1
 * Text/b.xhtml + b.xhtml#%E6%B3%A81     → id 注1
 * Text/a.xhtml + ../../../x             → PATH_ESCAPE
 * Text/a.xhtml + %252e%252e             → 解码一次后是字面 %2e%2e，不能再当 ..
 * ```
 */
class EpubResourceResolverTest {

    private fun internalOf(base: String, href: String): ResourceTarget.Internal =
        EpubResourceResolver.resolve(base, href) as ResourceTarget.Internal

    private fun rejected(base: String, href: String): ResourceTarget.Rejected =
        EpubResourceResolver.resolve(base, href) as ResourceTarget.Rejected

    // ---------------------------------------------------------------- 计划强制例子

    @Test
    fun plusIsALiteralPlusNotASpace() {
        val target = internalOf("Text/a.xhtml", "../Images/a+b.png")
        assertEquals("Images/a+b.png", target.containerPath)
        assertNull(target.fragment)
    }

    @Test
    fun percentEncodedSpaceDecodesOnce() {
        val target = internalOf("Text/a.xhtml", "../Images/a%20b.png")
        assertEquals("Images/a b.png", target.containerPath)
    }

    @Test
    fun sameDocumentFragmentIsPreserved() {
        val target = internalOf("OEBPS/Text/a.xhtml", "#n1")
        // 关键修复（F05）：fragment 不再被 substringBefore('#') 丢掉。
        assertEquals("n1", target.fragment)
        assertEquals("OEBPS/Text/a.xhtml", target.containerPath)
    }

    @Test
    fun unicodeFragmentIsDecodedOnce() {
        val target = internalOf("OEBPS/Text/b.xhtml", "b.xhtml#%E6%B3%A81")
        assertEquals("注1", target.fragment)
        assertEquals("OEBPS/Text/b.xhtml", target.containerPath)
    }

    @Test
    fun escapingTheContainerRootIsRejected() {
        val target = rejected("OEBPS/Text/a.xhtml", "../../../x")
        assertEquals(ContentErrorCode.PATH_ESCAPE, target.code)
    }

    @Test
    fun doubleEncodedDotsAreNotTreatedAsDotSegments() {
        // %252e%252e 解码一次 → 字面 "%2e%2e"，不是 ".."，因此不会越界。
        val target = internalOf("OEBPS/Text/a.xhtml", "%252e%252e")
        assertEquals("OEBPS/Text/%2e%2e", target.containerPath)
    }

    // ---------------------------------------------------------------- 同文档多锚点（F05 根因）

    @Test
    fun twoAnchorsInOneDocumentStayDistinct() {
        // 旧实现把两者都变成 "OEBPS/Text/b.xhtml" 存进 Map，后一个覆盖前一个，
        // 目录里两个条目标题互相顶掉、且都无法精确定位。
        val one = internalOf("OEBPS/Text/b.xhtml", "b.xhtml#note1")
        val two = internalOf("OEBPS/Text/b.xhtml", "b.xhtml#note2")
        assertEquals(one.containerPath, two.containerPath)
        assertEquals("note1", one.fragment)
        assertEquals("note2", two.fragment)
        assertTrue("两个锚点必须可区分", one.fragment != two.fragment)
    }

    // ---------------------------------------------------------------- 编码与安全

    @Test
    fun decodedSegmentContainingSeparatorIsRejected() {
        // %2e%2e%2f 解码后是 "../" —— 若先解码再消解就会绕过越界检查。
        val target = rejected("OEBPS/Text/a.xhtml", "..%2f..%2fescape.png")
        assertEquals(ContentErrorCode.INVALID_URI, target.code)
    }

    @Test
    fun malformedPercentEscapeIsRejected() {
        assertEquals(ContentErrorCode.INVALID_URI, rejected("a.xhtml", "x%ZZ.png").code)
        assertEquals(ContentErrorCode.INVALID_URI, rejected("a.xhtml", "x%2.png").code)
    }

    @Test
    fun invalidUtf8IsRejectedInsteadOfReplaced() {
        // %FF 不是合法 UTF-8 起始字节：必须报错，不能静默变成 U+FFFD。
        assertEquals(ContentErrorCode.INVALID_URI, rejected("a.xhtml", "x%FF.png").code)
    }

    @Test
    fun caseIsSignificant() {
        val target = internalOf("Text/a.xhtml", "../Images/A.PNG")
        // 大小写敏感，不做小写化，也不做 basename 兜底匹配。
        assertEquals("Images/A.PNG", target.containerPath)
    }

    @Test
    fun containerRootRelativePathDoesNotTouchTheHostFileSystem() {
        val target = internalOf("OEBPS/Text/a.xhtml", "/META-INF/container.xml")
        assertEquals("META-INF/container.xml", target.containerPath)
    }

    @Test
    fun hostAbsolutePathsAreRejected() {
        assertEquals(ContentErrorCode.PATH_ESCAPE, rejected("a.xhtml", "C:/Windows/x.png").code)
    }

    // ---------------------------------------------------------------- query 与 fragment 分离

    @Test
    fun queryIsSeparatedFromResourceIdentity() {
        val target = internalOf("Text/a.xhtml", "../Images/a.png?v=2")
        // 资源身份里不含 query，否则 `a.png?v=2` 会被当成文件名去找 ZIP 条目。
        assertEquals("Images/a.png", target.containerPath)
        assertEquals("v=2", target.query)
    }

    @Test
    fun bareHashMeansDocumentStart() {
        val target = internalOf("OEBPS/Text/a.xhtml", "a.xhtml#")
        assertNull("单独的 # 表示文档起点，不应产生空 fragment", target.fragment)
    }

    @Test
    fun fragmentIsNotLowercased() {
        val target = internalOf("OEBPS/Text/a.xhtml", "a.xhtml#NoteA")
        assertEquals("NoteA", target.fragment)
    }

    // ---------------------------------------------------------------- 外部与禁止 scheme

    @Test
    fun externalLinksAreRecognisedButNotLoaded() {
        val http = EpubResourceResolver.resolve("a.xhtml", "https://example.com/x")
        assertTrue(http is ResourceTarget.External)
        assertEquals("https", (http as ResourceTarget.External).scheme)

        val mail = EpubResourceResolver.resolve("a.xhtml", "mailto:a@b.c")
        assertEquals("mailto", (mail as ResourceTarget.External).scheme)
    }

    @Test
    fun dangerousSchemesAreRefused() {
        listOf("javascript:alert(1)", "file:///etc/passwd", "content://x/y", "intent://x", "data:text/html;base64,AA")
            .forEach { href ->
                val target = EpubResourceResolver.resolve("a.xhtml", href)
                assertTrue("$href 必须被拒绝", target is ResourceTarget.Rejected)
                assertEquals(ContentErrorCode.UNSUPPORTED_MEDIA, (target as ResourceTarget.Rejected).code)
            }
    }

    @Test
    fun blankAndEmptyReferencesAreRejected() {
        assertEquals(ContentErrorCode.INVALID_URI, rejected("a.xhtml", "").code)
        assertEquals(ContentErrorCode.INVALID_URI, rejected("a.xhtml", "   ").code)
    }

    @Test
    fun baseDirectoryIsTheReferencingDocumentDirectory() {
        // OPF 在 OEBPS/、XHTML 在 OEBPS/Text/ —— 同一个相对 href 得到不同结果。
        assertEquals("OEBPS/toc.xhtml", internalOf("OEBPS/content.opf", "toc.xhtml").containerPath)
        assertEquals("OEBPS/Text/toc.xhtml", internalOf("OEBPS/Text/a.xhtml", "toc.xhtml").containerPath)
        assertEquals("OEBPS/nav.xhtml", internalOf("OEBPS/nav.xhtml", "nav.xhtml").containerPath)
    }
}
