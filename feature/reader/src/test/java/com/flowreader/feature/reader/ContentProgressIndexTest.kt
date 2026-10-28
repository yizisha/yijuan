package com.flowreader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1 Gate G1：进度权重、文本量口径、速度与有效时间的纯逻辑验收。
 *
 * 直接使用主计划 10.2 的 golden：两章有效字数 100 与 900。
 */
class ContentProgressIndexTest {

    private fun index(vararg weights: Long): ContentProgressIndex {
        return ContentProgressIndex.of(weights.mapIndexed { i, w -> "s$i" to w })
    }

    // ---------------------------------------------------------------- 10.2 golden

    @Test
    fun goldenOneHundredAndNineHundredUnits() {
        val progress = index(100L, 900L)
        assertEquals(1000L, progress.totalUnits)

        // 第一章开头 = 0%
        assertEquals(0.0, progress.progress(0, 0.0), 0.0001)
        // 第二章开头 = 10%（旧实现给 50%）
        assertEquals(0.10, progress.progress(1, 0.0), 0.0001)
        // 第二章读到第 400 个有效字 = 50%
        assertEquals(0.50, progress.progress(1, 400.0 / 900.0), 0.0001)
        // 第二章末尾 = 100%
        assertEquals(1.0, progress.progress(1, 1.0), 0.0001)
    }

    @Test
    fun remainingUnitsShrinkAsReadingAdvances() {
        val progress = index(100L, 900L)
        assertEquals(1000L, progress.remainingUnits(0, 0.0))
        assertEquals(900L, progress.remainingUnits(0, 1.0))
        assertEquals(500L, progress.remainingUnits(1, 400.0 / 900.0))
        assertEquals(0L, progress.remainingUnits(1, 1.0))
    }

    @Test
    fun sliderInverseMappingUsesCumulativeWeights() {
        val progress = index(100L, 900L)
        // 10% 正是第二章开头 —— 旧实现按章数逆推会落到第一章。
        assertEquals(1, progress.sectionAt(0.10))
        assertEquals(0, progress.sectionAt(0.0))
        assertEquals(0, progress.sectionAt(0.0999))
        assertEquals(1, progress.sectionAt(0.5))
        assertEquals(1, progress.sectionAt(1.0))
        // 第二章内的比例：50% 进度 → 第二章的 (500-100)/900
        assertEquals(400.0 / 900.0, progress.withinFractionAt(0.5), 0.0001)
    }

    @Test
    fun zeroWeightSectionsAreSkippedByTheSliderButKeepTheirOrdinal() {
        val progress = index(100L, 0L, 900L)
        assertEquals(1000L, progress.totalUnits)
        // 零权重段（纯结构页）不会被滑杆选中，但序号仍然存在且可导航。
        assertEquals(2, progress.sectionAt(0.5))
        assertEquals(1, progress.sectionIdAt(1)?.let { 1 } ?: 0)
        assertEquals(0L, progress.unitsOf(1))
    }

    @Test
    fun anEmptyIndexReportsNotReadyInsteadOfZeroPercent() {
        val empty = ContentProgressIndex.EMPTY
        assertFalse("索引未就绪时不能假装是 0%", empty.isReady)
        assertEquals(0.0, empty.progress(0, 0.5), 0.0001)
        assertTrue(ContentProgressIndex.of(listOf("a" to 0L)).isReady.not())
    }

    @Test
    fun progressIsClampedToZeroOne() {
        val progress = index(100L)
        assertEquals(0.0, progress.progress(0, -5.0), 0.0001)
        assertEquals(1.0, progress.progress(0, 5.0), 0.0001)
        assertEquals(1.0, progress.progress(99, 1.0), 0.0001)
        assertEquals(0.0, progress.progress(0, Double.NaN), 0.0001)
    }

    // ---------------------------------------------------------------- TextUnits

    @Test
    fun anEmojiCountsAsOneUnitButTwoUtf16Units() {
        val emoji = "\uD83D\uDE00"
        assertEquals(2, emoji.length)
        assertEquals(1L, TextUnits.count(emoji))
    }

    @Test
    fun whitespaceAndObjectReplacementDoNotCount() {
        assertEquals(2L, TextUnits.count("甲 乙"))
        assertEquals(2L, TextUnits.count("甲\uFFFC乙"))
        assertEquals(2L, TextUnits.count("甲\n\t乙"))
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        val text = "甲\uD83D\uDE00乙"
        assertTrue(TextUnits.splitsSurrogatePair(text, 2))
        assertFalse(TextUnits.splitsSurrogatePair(text, 1))
        // 夹到 1，不切开 emoji。
        assertEquals(1, TextUnits.sanitizeOffset(text, 2))
        assertEquals(3, TextUnits.sanitizeOffset(text, 3))
    }

    @Test
    fun codePointIndexIgnoresSurrogateHalves() {
        val text = "\uD83D\uDE00乙"
        assertEquals(0, TextUnits.codePointIndex(text, 0))
        assertEquals(1, TextUnits.codePointIndex(text, 2))
        assertEquals(2, TextUnits.codePointIndex(text, 3))
    }

    // ---------------------------------------------------------------- 10.4 速度与 ETA

    @Test
    fun aOneSecondSampleNeverBecomesAReadingSpeed() {
        val estimator = ReadingSpeedEstimator()
        estimator.addWindow(units = 10L, millis = 1_000L)
        assertNull("窗口不足 60 秒不得产生速度", estimator.unitsPerMinute())
        assertFalse(estimator.hasEstimate)
    }

    @Test
    fun speedIsTotalUnitsOverTotalMinutes() {
        val estimator = ReadingSpeedEstimator()
        estimator.addWindow(units = 600L, millis = 60_000L)
        estimator.addWindow(units = 1_200L, millis = 120_000L)
        // (600+1200) / 3 分钟 = 600 字/分钟
        assertEquals(600.0, estimator.unitsPerMinute()!!, 0.5)
        assertTrue(estimator.isPlausible())
    }

    @Test
    fun implausibleWindowsAreExcludedFromEtaButNotErased() {
        val estimator = ReadingSpeedEstimator()
        // 60 秒读 5000 字 = 5000 字/分钟，是合格窗口但超出合理上限。
        estimator.addWindow(units = 5_000L, millis = 60_000L)
        assertFalse(estimator.isPlausible())
        // 速度本身仍可算出（用户真实时长不因此被删），只是不用于 ETA。
        assertEquals(5_000.0, estimator.unitsPerMinute()!!, 5.0)
        // ETA 在异常速度下仍按实际速度给出，由调用方决定是否展示（策略层 isPlausible 判定）。
        assertEquals(1, ReadingSpeedEstimator.remainingMinutes(5_000L, estimator.unitsPerMinute()))
    }

    @Test
    fun subMinuteWindowsAreNotQualifiedWindowsAtAll() {
        val estimator = ReadingSpeedEstimator()
        // 主计划 10.4 第 6 条：窗口至少 60 秒有效时间，否则不构成速度样本。
        estimator.addWindow(units = 3_000L, millis = 30_000L)
        assertFalse(estimator.hasEstimate)
        assertNull(estimator.unitsPerMinute())
    }

    @Test
    fun etaIsUnknownWithoutSamplesInsteadOfFabricated() {
        // 旧实现 `speed.coerceAtLeast(100f)` 会在这里报出 10 分钟。
        assertNull(ReadingSpeedEstimator.remainingMinutes(remainingUnits = 1_000L, unitsPerMinute = null))
        // 零速度同样未知。
        assertNull(ReadingSpeedEstimator.remainingMinutes(1_000L, 0.0))
        assertNull(ReadingSpeedEstimator.remainingMinutes(1_000L, Double.NaN))
        // 到末尾 = 0 分钟。
        assertEquals(0, ReadingSpeedEstimator.remainingMinutes(0L, 600.0))
        // 正常估算向上取整。
        assertEquals(2, ReadingSpeedEstimator.remainingMinutes(1_000L, 600.0))
    }

    // ---------------------------------------------------------------- 10.3 有效时间

    @Test
    fun timeIsCountedWithoutAnyPageTurn() {
        var now = 0L
        val machine = ReadingTimeStateMachine(nowProvider = { now })
        machine.enterContent()
        now += 90_000
        val delta = machine.takeDelta()
        // 主计划 T073：同页 90 秒 → 保存 90 秒，turn = 0。
        assertEquals(90_000L, delta.visualReadMillis)
        assertEquals(0, delta.settledTurns)
        assertTrue("只要任一分量 > 0 就可落库", delta.isPersistable)
    }

    @Test
    fun idleTimeIsCappedAtTheIdleBoundary() {
        var now = 0L
        val machine = ReadingTimeStateMachine(nowProvider = { now })
        machine.enterContent()
        now += 60_000
        machine.onUserActivity()
        // 之后 20 分钟没有任何交互：只计到 lastActivity + 5 分钟。
        now += 20 * 60_000
        val delta = machine.takeDelta()
        assertEquals(60_000L + ReaderSessionPolicy.IDLE_TIMEOUT_MS, delta.visualReadMillis)
    }

    @Test
    fun leavingContentFreezesTimeImmediately() {
        var now = 0L
        val machine = ReadingTimeStateMachine(nowProvider = { now })
        machine.enterContent()
        now += 30_000
        machine.exitContent()
        // 后台再放 10 分钟，不计入。
        now += 600_000
        machine.takeDelta()
        now += 5_000
        assertEquals(0L, machine.takeDelta().visualReadMillis)
    }

    @Test
    fun ttsAndVisualTimeNeverDoubleCount() {
        var now = 0L
        val machine = ReadingTimeStateMachine(nowProvider = { now })
        machine.enterContent()
        now += 10_000
        machine.setTtsPlaying(true)
        now += 20_000
        machine.setTtsPlaying(false)
        now += 10_000
        val delta = machine.takeDelta()
        assertEquals("同一时间段只计一种", 20_000L, delta.ttsReadMillis)
        assertEquals(20_000L, delta.visualReadMillis)
        assertEquals(40_000L, delta.totalMillis)
    }

    @Test
    fun deltasAccumulateButAreNotShared() {
        val a = SessionDelta(visualReadMillis = 1_000L, qualifiedTextUnits = 300L, settledTurns = 2)
        val b = SessionDelta(ttsReadMillis = 500L)
        val sum = a + b
        assertEquals(1_500L, sum.totalMillis)
        assertEquals(300L, sum.qualifiedTextUnits)
        assertEquals(2, sum.settledTurns)
        assertFalse(SessionDelta.NONE.isPersistable)
    }
}
