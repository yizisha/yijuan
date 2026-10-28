package com.flowreader.app.data.local.entity

import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.Bookmark
import com.flowreader.app.domain.model.LegacyPositionUnit
import com.flowreader.app.domain.model.RangeLocator
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.TextLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 实体层映射测试（主计划 5.2 / 第 9 节）。
 *
 * 这是最容易被忽略的一层：v10 已经加好了 `locatorJson` / `rangeLocatorJson` 列，
 * 但如果 `toDomain/fromDomain` 不读写它们，新坐标就**从未真正落库**，
 * 表结构看起来正确而功能完全没接线。本测试确保列真的通了。
 */
class BookmarkAnnotationLocatorMappingTest {

    // ------------------------------------------------------------ Bookmark

    @Test
    fun bookmarkRoundTripWritesAndReadsLocatorJson() {
        val locator = TextLocator(
            publicationId = "pub-1",
            revision = "rev-1",
            sectionId = "section_0",
            documentKey = "chapter:0",
            offsetUtf16 = 512,
            blockId = "b7"
        )
        val bookmark = Bookmark(
            bookId = 1L,
            chapterIndex = 0,
            position = 300,
            text = "书签",
            locator = locator,
            locatorQuality = ResolutionQuality.EXACT,
            legacyUnit = LegacyPositionUnit.SCROLL_PX
        )

        val entity = BookmarkEntity.fromDomain(bookmark)

        assertNotNull("locatorJson 列必须被真正写入", entity.locatorJson)
        assertEquals("EXACT", entity.locatorQuality)
        assertEquals("SCROLL_PX", entity.legacyUnit)

        val restored = entity.toDomain()
        assertNotNull("locator 必须能从列读回", restored.locator)
        assertEquals(512, restored.locator?.offsetUtf16)
        assertEquals("chapter:0", restored.locator?.documentKey)
        assertEquals("b7", restored.locator?.blockId)
        assertEquals(ResolutionQuality.EXACT, restored.locatorQuality)
        assertEquals(LegacyPositionUnit.SCROLL_PX, restored.legacyUnit)
        // 旧字段不得被新 locator 覆写。
        assertEquals(300, restored.position)
        assertEquals(0, restored.chapterIndex)
    }

    @Test
    fun legacyBookmarkWithoutLocatorStaysUnresolvedAndDoesNotClaimExact() {
        val entity = BookmarkEntity.fromDomain(
            Bookmark(
                bookId = 1L,
                chapterIndex = 3,
                position = 42,
                text = "旧书签",
                locator = null,
                locatorQuality = ResolutionQuality.EXACT, // 调用方谎报 EXACT，也必须被降级
                legacyUnit = LegacyPositionUnit.UNKNOWN
            )
        )

        assertNull("无 locator 时不得写出 locatorJson", entity.locatorJson)
        assertEquals(
            "无 locator 时质量必须被强制为 UNRESOLVED",
            "UNRESOLVED",
            entity.locatorQuality
        )

        val restored = entity.toDomain()
        assertNull(restored.locator)
        assertTrue(restored.isLegacyOnly)
        assertEquals(ResolutionQuality.UNRESOLVED, restored.locatorQuality)
        assertEquals(42, restored.position)
    }

    @Test
    fun corruptedLocatorJsonDegradesToUnresolvedInsteadOfCrashing() {
        // 数据库里可能有被截断/手改的 JSON：必须降级，不得崩溃、不得伪造坐标。
        val entity = BookmarkEntity(
            bookId = 1L,
            chapterIndex = 0,
            position = 10,
            text = "t",
            locatorJson = "{not valid json",
            locatorQuality = "EXACT",
            legacyUnit = "SCROLL_PX"
        )
        val restored = entity.toDomain()
        assertNull(restored.locator)
        assertEquals(ResolutionQuality.UNRESOLVED, restored.locatorQuality)
        assertEquals("旧字段必须保留", 10, restored.position)
    }

    @Test
    fun unknownLegacyUnitValueFallsBackSafely() {
        val entity = BookmarkEntity(
            bookId = 1L,
            chapterIndex = 0,
            position = 1,
            text = "t",
            legacyUnit = "SOMETHING_NEW_FROM_FUTURE"
        )
        assertEquals(
            "未知枚举值必须回落到 UNKNOWN，不能猜单位",
            LegacyPositionUnit.UNKNOWN,
            entity.toDomain().legacyUnit
        )
    }

    // ------------------------------------------------------------ Annotation

    @Test
    fun annotationRoundTripWritesAndReadsRangeLocatorJson() {
        val rangeLocator = RangeLocator(
            publicationId = "pub-1",
            revision = "rev-1",
            ranges = listOf(
                SourceRange("chapter:0", 10, 40),
                SourceRange("chapter:0", 60, 95)
            ),
            quality = ResolutionQuality.CONTEXT_MATCH
        )
        val annotation = Annotation(
            bookId = 1L,
            chapterIndex = 0,
            startPosition = 10,
            endPosition = 40,
            selectedText = "选中的文字",
            note = "笔记",
            rangeLocator = rangeLocator,
            locatorQuality = ResolutionQuality.CONTEXT_MATCH
        )

        val entity = AnnotationEntity.fromDomain(annotation)

        assertNotNull("rangeLocatorJson 列必须被真正写入", entity.rangeLocatorJson)
        assertEquals("CONTEXT_MATCH", entity.locatorQuality)

        val restored = entity.toDomain()
        assertNotNull(restored.rangeLocator)
        assertEquals(2, restored.rangeLocator?.ranges?.size)
        assertEquals(SourceRange("chapter:0", 10, 40), restored.rangeLocator?.ranges?.get(0))
        assertEquals(SourceRange("chapter:0", 60, 95), restored.rangeLocator?.ranges?.get(1))
        assertEquals(ResolutionQuality.CONTEXT_MATCH, restored.locatorQuality)
        // 旧字段原样保留（主计划 5.4：不覆写、不删除、不自动扩缩）。
        assertEquals(10, restored.startPosition)
        assertEquals(40, restored.endPosition)
        assertEquals("笔记", restored.note)
    }

    @Test
    fun legacyAnnotationWithoutRangeLocatorKeepsOldFieldsIntact() {
        val entity = AnnotationEntity.fromDomain(
            Annotation(
                bookId = 1L,
                chapterIndex = 2,
                startPosition = 7,
                endPosition = 19,
                selectedText = "旧批注",
                note = "旧笔记"
            )
        )

        assertNull(entity.rangeLocatorJson)
        assertEquals("UNRESOLVED", entity.locatorQuality)

        val restored = entity.toDomain()
        assertNull(restored.rangeLocator)
        assertEquals(ResolutionQuality.UNRESOLVED, restored.locatorQuality)
        assertEquals(7, restored.startPosition)
        assertEquals(19, restored.endPosition)
        assertEquals("旧批注", restored.selectedText)
        assertEquals("旧笔记", restored.note)
    }

    @Test
    fun corruptedRangeLocatorJsonDegradesToUnresolved() {
        val entity = AnnotationEntity(
            bookId = 1L,
            chapterIndex = 0,
            startPosition = 0,
            endPosition = 5,
            selectedText = "x",
            rangeLocatorJson = """{"schemaVersion":1,"publicationId":"p"}"""
        )
        val restored = entity.toDomain()
        assertNull(restored.rangeLocator)
        assertEquals(ResolutionQuality.UNRESOLVED, restored.locatorQuality)
        assertFalse(restored.legacyRangeMatchesLocator())
    }

    @Test
    fun annotationColorAndTypeSurviveRoundTripAlongsideLocator() {
        val annotation = Annotation(
            bookId = 1L,
            chapterIndex = 0,
            startPosition = 0,
            endPosition = 3,
            selectedText = "abc",
            note = "",
            color = com.flowreader.app.domain.model.AnnotationColor.GREEN,
            type = com.flowreader.app.domain.model.AnnotationType.UNDERLINE,
            rangeLocator = RangeLocator(
                publicationId = "pub",
                revision = "rev",
                ranges = listOf(SourceRange("chapter:0", 0, 3))
            )
        )
        val restored = AnnotationEntity.fromDomain(annotation).toDomain()
        assertEquals(com.flowreader.app.domain.model.AnnotationColor.GREEN, restored.color)
        assertEquals(com.flowreader.app.domain.model.AnnotationType.UNDERLINE, restored.type)
        assertNotNull(restored.rangeLocator)
    }
}