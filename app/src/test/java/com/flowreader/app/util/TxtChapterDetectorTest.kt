package com.flowreader.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtChapterDetectorTest {

    @Test
    fun testChineseNumberedChapters() {
        val cases = listOf(
            "第一章 初出茅庐",
            "第1章 初入江湖",
            "第一百二十章 风起云涌",
            "第一千二百三十四章 大结局",
            "第两百五十章 激战",
            "第〇章 序曲",
            "第 10 章 间隔空格",
            "第一回 桃园三结义",
            "第二十回 曹操煮酒论英雄",
            "第1节 概念",
            "第3幕 冲突",
            "第12话 转校生",
            "第一折 惊变"
        )
        for (case in cases) {
            val match = TxtChapterDetector.matchHeading(case, TxtChapterDetector.TIER1_PATTERNS)
            assertTrue("Should match chapter: $case", match.isHeading)
        }
    }

    @Test
    fun testSpecialChapters() {
        val specialCases = listOf(
            "序章：以“女”与“欲”写就的爱",
            "序言",
            "楔子",
            "引子",
            "尾声",
            "后记",
            "番外篇『平静的酒店日记』 前篇",
            "番外篇『平静的酒店日记』 后篇",
            "终章",
            "大结局",
            "完本感言",
            "上架感言"
        )
        for (case in specialCases) {
            val match = TxtChapterDetector.matchHeading(case, TxtChapterDetector.TIER1_PATTERNS)
            assertTrue("Should match special section: $case", match.isHeading)
        }
    }

    @Test
    fun testVolumes() {
        val volumeCases = listOf(
            "第一卷 魔法学院",
            "卷二 帝国风云",
            "分卷一 异界之旅"
        )
        for (case in volumeCases) {
            val match = TxtChapterDetector.matchHeading(case, TxtChapterDetector.TIER1_PATTERNS)
            assertTrue("Should match volume: $case", match.isHeading)
            assertTrue("Should be identified as volume: $case", match.isVolume)
        }
    }

    @Test
    fun testBracketedAndWesternChapters() {
        val bracketCases = listOf(
            "【第一章 醒来】",
            "[Chapter 1] The Beginning",
            "〔第10章〕 突破",
            "Chapter 1 Prologue",
            "CHAPTER IV The Fall",
            "Act 2 Scene 1",
            "Part 3 The Climax"
        )
        for (case in bracketCases) {
            val match = TxtChapterDetector.matchHeading(case, TxtChapterDetector.TIER1_PATTERNS)
            assertTrue("Should match: $case", match.isHeading)
        }
    }

    @Test
    fun testRejectDialogueAndSentences() {
        val invalidCases = listOf(
            "“第一章是我最喜欢的章节。”",
            "「那第一次闭上眼睛时……」",
            "『第一卷写得真好』",
            "第一章结束时，所有人都沉默了。",
            "第10章里提到过这个细节，大家记得吗？",
            "我第一次见到她的时候，天空正下着雨。",
            "这是一个很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长的段落超过四十五个字绝对不可能是章节标题的文本内容。"
        )
        for (case in invalidCases) {
            val match = TxtChapterDetector.matchHeading(case, TxtChapterDetector.TIER1_PATTERNS)
            assertFalse("Should NOT match as heading: $case", match.isHeading)
        }
    }

    @Test
    fun testNumberingFallbackTier2() {
        val lines = listOf(
            "01 赤裸裸的真相",
            "02 刺青之女",
            "03 秘密行动"
        )
        val patterns = TxtChapterDetector.selectBestPatterns(lines)
        assertEquals(TxtChapterDetector.TIER2_PATTERNS, patterns)
        for (line in lines) {
            val match = TxtChapterDetector.matchHeading(line, patterns)
            assertTrue("Should match under Tier 2: $line", match.isHeading)
        }
    }

    @Test
    fun testExtractAuthor() {
        val lines = listOf(
            "美满家庭的人妻对女儿的朋友沉迷不已的故事",
            "作者：入间人间",
            "插画：猫屋敷ぷしお"
        )
        val author = TxtChapterDetector.extractAuthor(lines)
        assertNotNull(author)
        assertEquals("入间人间", author)
    }

    @Test
    fun testBracketedVolumes() {
        val bracketedVolumeCases = listOf(
            "【第一卷 远行】",
            "【第一卷】山河初见",
            "【卷二】",
            "〔分卷一〕",
            "（第3卷 决战）",
            "[第1卷：远行]"
        )
        for (case in bracketedVolumeCases) {
            val match = TxtChapterDetector.matchHeading(case, TxtChapterDetector.TIER1_PATTERNS)
            assertTrue("Should match: $case", match.isHeading)
            assertTrue("Should be volume: $case", match.isVolume)
            assertEquals(TxtChapterDetector.HeadingKind.VOLUME, match.kind)
        }
    }

    @Test
    fun testHeadingKindClassification() {
        val chapterMatch = TxtChapterDetector.matchHeading("第一章 起航", TxtChapterDetector.TIER1_PATTERNS)
        assertEquals(TxtChapterDetector.HeadingKind.CHAPTER, chapterMatch.kind)

        val volumeMatch = TxtChapterDetector.matchHeading("第一卷 序章群雄", TxtChapterDetector.TIER1_PATTERNS)
        assertEquals(TxtChapterDetector.HeadingKind.VOLUME, volumeMatch.kind)

        val specialMatch = TxtChapterDetector.matchHeading("序章 轮回", TxtChapterDetector.TIER1_PATTERNS)
        assertEquals(TxtChapterDetector.HeadingKind.SPECIAL_SECTION, specialMatch.kind)

        val noneMatch = TxtChapterDetector.matchHeading("“第一章真好看。”", TxtChapterDetector.TIER1_PATTERNS)
        assertEquals(TxtChapterDetector.HeadingKind.NONE, noneMatch.kind)
    }

    @Test
    fun testNonVolumeContainingJuanCharacter() {
        // Words containing 卷 that are NOT volumes
        val chapterWithJuan = TxtChapterDetector.matchHeading("第一章 卷土重来", TxtChapterDetector.TIER1_PATTERNS)
        assertTrue(chapterWithJuan.isHeading)
        assertFalse(chapterWithJuan.isVolume)
        assertEquals(TxtChapterDetector.HeadingKind.CHAPTER, chapterWithJuan.kind)

        val nonHeadingJuan = TxtChapterDetector.matchHeading("画卷中的少女缓缓睁开眼睛", TxtChapterDetector.TIER1_PATTERNS)
        assertFalse(nonHeadingJuan.isHeading)
        assertEquals(TxtChapterDetector.HeadingKind.NONE, nonHeadingJuan.kind)
    }
}
