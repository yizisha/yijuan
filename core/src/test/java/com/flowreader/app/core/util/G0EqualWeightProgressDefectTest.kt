package com.flowreader.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G0 复现夹具：**等权章节进度**（主计划 F03 / R3 / T069、T072）。
 *
 * 这里是**缺陷特征描述测试**（characterization test），断言的是当前代码的真实行为，因此现在全部
 * 通过——它存在的意义是把缺陷钉死在可执行代码里，S9 修正 `ReadingProgress` 后必须把这些断言
 * **翻转**成主计划 10.2 的 golden 值。计划明确要求“不能用只断言函数存在的测试冒充复现”，
 * 而断言错误数值同样是不可接受的，所以每一条都写清了「当前值」与「应有值」。
 *
 * 主计划 10.2 golden：两章有效字数 100 与 900，总量 W=1000，前缀和 P=[0,100]。
 * - 第一章开头 u=0 → (0+0)/1000 = 0%
 * - 第二章开头 u=0 → (100+0)/1000 = 10%
 * - 第二章读到第 400 个有效字 → (100+400)/1000 = 50%
 * - 第二章末尾 → 100%
 */
class G0EqualWeightProgressDefectTest {

    @Test
    fun chapterTwoStartIsReportedAsFiftyPercentInsteadOfTenPercent() {
        // 当前实现：(index + inside) / totalChapters = (1 + 0) / 2 = 0.5
        val actual = ReadingProgress.fraction(chapterIndex = 1, chapterFraction = 0f, totalChapters = 2)
        assertEquals("当前实现按章节等权", 0.5f, actual, 0.0001f)

        // S9 之后必须变成 10%（第二章开头），而不是 50%。
        val required = 0.10f
        assertEquals(1, countMismatch(actual, required))
    }

    @Test
    fun sliderInverseMappingLandsOnTheWrongChapter() {
        // 滑杆拖到 10%：当前 (0.10 * 2).toInt() = 0 → 指向**第一章**，而 10% 正是第二章的开头。
        val actual = ReadingProgress.chapterAt(fraction = 0.10f, totalChapters = 2)
        assertEquals("当前实现按章节数逆推", 0, actual)

        // S9 之后应指向第二章（index 1）。
        val required = 1
        assertEquals(1, countMismatch(actual, required))
    }

    @Test
    fun aLongChapterAndAShortChapterAdvanceTheBarEqually() {
        // 100 字与 900 字的章节，各自读完后进度增量相同（都是 50%），与计划 10.2 的 10%/90% 不符。
        val afterShort = ReadingProgress.fraction(chapterIndex = 0, chapterFraction = 1f, totalChapters = 2)
        val afterLong = ReadingProgress.fraction(chapterIndex = 1, chapterFraction = 1f, totalChapters = 2)
        assertEquals(0.5f, afterShort, 0.0001f)
        assertEquals(1.0f, afterLong, 0.0001f)
        // 真实权重下：读完 100 字应是 10%，读完 900 字才到 100%。
        val requiredAfterShort = 0.10f
        assertEquals(1, countMismatch(afterShort, requiredAfterShort))
    }

    private fun countMismatch(actual: Float, required: Float): Int = if (kotlin.math.abs(actual - required) < 0.0001f) 0 else 1

    private fun countMismatch(actual: Int, required: Int): Int = if (actual == required) 0 else 1
}
