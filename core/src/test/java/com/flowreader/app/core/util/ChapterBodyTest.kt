package com.flowreader.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterBodyTest {

    @Test
    fun stripsAnExactCopyOfTheTitle() {
        val body = "第999章 世尊保佑，行善积德\n\n孟景舟听俩人介绍对方的时候，来历一个比一个惊人。"
        val stripped = ChapterBody.stripLeadingTitleEcho(body, "第999章 世尊保佑，行善积德")
        assertTrue(stripped.startsWith("孟景舟"))
    }

    @Test
    fun stripsATwoLineEchoOfNumberAndSubtitle() {
        // The reported shape: the heading lines were `<h3>第999章</h3><strong>世尊保佑，行善积德</strong>`.
        val body = "## 第999章\n**世尊保佑，行善积德**\n\n孟景舟听俩人介绍对方的时候，来历一个比一个惊人。"
        val stripped = ChapterBody.stripLeadingTitleEcho(body, "第999章 世尊保佑，行善积德")
        assertTrue(stripped.startsWith("孟景舟"))
    }

    @Test
    fun stripsAnIndentedHeadingWithFullWidthSpaces() {
        val body = "　　序 章　关于幻想杀手少年的故事\n\n正文第一段。"
        val stripped = ChapterBody.stripLeadingTitleEcho(body, "序章 关于幻想杀手少年的故事")
        assertTrue(stripped.startsWith("正文第一段"))
    }

    @Test
    fun keepsARealFirstParagraph() {
        val body = "孟景舟听俩人介绍对方的时候，来历一个比一个惊人。\n\n第二段。"
        assertEquals(body, ChapterBody.stripLeadingTitleEcho(body, "第999章 世尊保佑，行善积德"))
    }

    @Test
    fun neverStripsMoreThanTwoLines() {
        // Three leading lines that are all substrings of the title: only the first two go, so a
        // body that legitimately starts with a short line stays intact.
        val title = "第一章 开端 上"
        val body = "第一章\n开端\n上\n\n正文。"
        val stripped = ChapterBody.stripLeadingTitleEcho(body, title)
        assertTrue(stripped.startsWith("上"))
        assertTrue(stripped.contains("正文。"))
    }

    @Test
    fun emptyTitleLeavesTheBodyUntouched() {
        val body = "正文。"
        assertEquals(body, ChapterBody.stripLeadingTitleEcho(body, ""))
    }
}
