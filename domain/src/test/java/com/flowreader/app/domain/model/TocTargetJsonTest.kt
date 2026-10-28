package com.flowreader.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S8b `toc_nodes.targetJson` 解析测试。
 *
 * 一条坏记录不能影响其它节点，更不能让整本书打不开目录——因此所有失败路径都必须**返回 null**。
 */
class TocTargetJsonTest {

    @Test
    fun parsesSectionIdDocumentKeyAndFragment() {
        val json = """{"documentKey":"OEBPS/Text/ch1.xhtml","fragment":"a1","sectionId":"sec-1"}"""
        assertEquals("sec-1", TocTargetJson.sectionIdOf(json))
        assertEquals("OEBPS/Text/ch1.xhtml", TocTargetJson.documentKeyOf(json))
        assertEquals("a1", TocTargetJson.fragmentOf(json))
    }

    @Test
    fun jsonNullFieldsBecomeNullNotTheLiteralString() {
        // flattenToc 用 JSONObject.NULL 写无值字段，落库是字面量 null。
        val json = """{"documentKey":"a.xhtml","fragment":null,"sectionId":null}"""
        assertNull(TocTargetJson.sectionIdOf(json))
        assertNull(TocTargetJson.fragmentOf(json))
        assertEquals("a.xhtml", TocTargetJson.documentKeyOf(json))
    }

    @Test
    fun missingFieldReturnsNull() {
        assertNull(TocTargetJson.sectionIdOf("""{"documentKey":"a.xhtml"}"""))
    }

    @Test
    fun nullAndBlankInputReturnNull() {
        assertNull(TocTargetJson.sectionIdOf(null))
        assertNull(TocTargetJson.sectionIdOf(""))
        assertNull(TocTargetJson.sectionIdOf("   "))
    }

    @Test
    fun malformedJsonReturnsNullInsteadOfThrowing() {
        // 关键：坏数据不得抛异常。
        assertNull(TocTargetJson.sectionIdOf("{不是 JSON"))
        assertNull(TocTargetJson.sectionIdOf("[]"))
        assertNull(TocTargetJson.sectionIdOf("""{"sectionId":"sec-1"""))
    }

    @Test
    fun unicodeInSectionIdIsPreserved() {
        // 中文/日文/俄文都不得被损坏（真实 EPUB 的锚点 id 里会出现）。
        val json = """{"sectionId":"中文-章节-секция"}"""
        assertEquals("中文-章节-секция", TocTargetJson.sectionIdOf(json))
    }

    @Test
    fun escapedQuoteInsideSectionIdIsDecoded() {
        // 用字符码拼出带转义的 JSON，避免测试里的反斜杠转义本身出错。
        val quote = 34.toChar()
        val backslash = 92.toChar()
        val json = "{" + quote + "sectionId" + quote + ":" + quote +
            "a" + backslash + quote + "b" + quote + "}"
        assertEquals("a" + quote + "b", TocTargetJson.sectionIdOf(json))
    }

    @Test
    fun emptySectionIdIsTreatedAsNoTarget() {
        // 空字符串不是合法 section：必须当作「无目标」，否则目录项会变成点不开却又可点的死节点。
        assertNull(TocTargetJson.sectionIdOf("""{"sectionId":""}"""))
    }
}
