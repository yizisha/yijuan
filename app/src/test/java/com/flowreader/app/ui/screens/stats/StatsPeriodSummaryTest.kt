package com.flowreader.app.ui.screens.stats

import com.flowreader.app.domain.model.DailyStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsPeriodSummaryTest {
    @Test fun aSubMinuteSessionHasHeatmapColourWithoutInflatingItsRealDuration() {
        assertEquals(0, heatmapIntensityMinutes(0))
        assertEquals(1, heatmapIntensityMinutes(9))
        assertEquals(1, heatmapIntensityMinutes(59))
        assertEquals(15, heatmapIntensityMinutes(900))
        val summary = StatsPeriodSummary.of(listOf(DailyStats(today.toString(), 9L, 0, 1)), StatsPeriod.WEEK, today)
        assertEquals(9L, summary.readTime)
    }
    @Test fun chartFillsTheSelectedWeekAndExcludesOtherWindows() {
        val anchor = LocalDate.of(2026, 9, 10)
        val state = StatsUiState(period = StatsPeriod.WEEK, anchor = anchor, heatmapStats = listOf(
            DailyStats("2026-09-10", 60L, 2, 1), DailyStats("2026-09-03", 99_000L, 9, 1)))
        val chart = periodChartStats(state)
        assertEquals(7, chart.size)
        assertEquals("2026-09-07", chart.first().date)
        assertEquals("2026-09-13", chart.last().date)
        assertEquals(60L, chart.sumOf { it.totalReadTime })
        assertEquals(6, chart.count { it.totalReadTime == 0L })
    }

    @Test fun yearChartHasTwelveMonthsAndPreservesTotals() {
        val rows = listOf(DailyStats("2026-01-01", 30_000L, 3, 1), DailyStats("2026-01-31", 70_000L, 7, 1), DailyStats("2025-12-31", 90_000L, 9, 1))
        val chart = periodChartStats(StatsUiState(period = StatsPeriod.YEAR, anchor = LocalDate.of(2026, 10, 11), heatmapStats = rows))
        assertEquals(12, chart.size)
        assertEquals(100_000L, chart.first().totalReadTime)
        assertEquals(100_000L, chart.sumOf { it.totalReadTime })
        assertEquals(10, chart.sumOf { it.totalReadPages })
    }

    private val today = LocalDate.of(2026, 9, 10)

    private fun day(offsetDays: Long, minutes: Int, books: Int = 0) = DailyStats(
        date = today.minusDays(offsetDays).toString(),
        totalReadTime = minutes * 60L,
        totalReadPages = minutes,
        booksRead = books
    )

    @Test
    fun weekWindowCountsOnlyItsOwnDays() {
        // 2026-09-10 is a Thursday, so the window is Mon 09-07 … Sun 09-13.
        val summary = StatsPeriodSummary.of(
            daily = listOf(day(0, 30), day(6, 30), day(7, 90), day(30, 60)),
            period = StatsPeriod.WEEK,
            anchor = today
        )
        // Only 09-10 falls inside this week; 09-04 and 09-03 belong to the previous one.
        assertEquals(30 * 60L, summary.readTime)
        assertEquals(120 * 60L, summary.previousReadTime)
        assertEquals(1, summary.daysRead)
    }

    @Test
    fun averagesOverDaysReadNotCalendarDays() {
        val summary = StatsPeriodSummary.of(
            daily = listOf(day(0, 60), day(1, 60)),
            period = StatsPeriod.WEEK,
            anchor = today
        )
        // 120 minutes over 2 active days, not over 7 calendar days.
        assertEquals(60 * 60L, summary.averagePerActiveDay)
    }

    @Test
    fun changeIsRelativeToThePreviousWindow() {
        val up = StatsPeriodSummary.of(
            daily = listOf(day(0, 120), day(7, 60)),
            period = StatsPeriod.WEEK,
            anchor = today
        )
        assertEquals(100, up.changePercent)

        val down = StatsPeriodSummary.of(
            daily = listOf(day(0, 30), day(7, 60)),
            period = StatsPeriod.WEEK,
            anchor = today
        )
        assertEquals(-50, down.changePercent)
    }

    @Test
    fun noPreviousWindowMeansNoComparison() {
        val summary = StatsPeriodSummary.of(listOf(day(0, 10)), StatsPeriod.WEEK, today)
        assertNull(summary.changePercent)
        assertEquals(10 * 60L, summary.averagePerActiveDay)
        assertEquals(1, summary.daysRead)
    }

    @Test
    fun monthWindowIsTheWholeCalendarMonth() {
        // The bug: any past month reported "the last 30 days" and labelled itself 「近 2 个月」.
        val (start, end) = StatsPeriodSummary.window(StatsPeriod.MONTH, today)
        assertEquals(LocalDate.of(2026, 9, 1), start)
        assertEquals(LocalDate.of(2026, 9, 30), end)
        assertEquals("2026年9月", StatsPeriodSummary.rangeLabel(StatsPeriod.MONTH, today))
    }

    @Test
    fun weekWindowRunsMondayToSunday() {
        // 2026-09-10 is a Thursday.
        val (start, end) = StatsPeriodSummary.window(StatsPeriod.WEEK, today)
        assertEquals(LocalDate.of(2026, 9, 7), start)
        assertEquals(LocalDate.of(2026, 9, 13), end)
    }

    @Test
    fun yearWindowIsTheWholeCalendarYear() {
        val (start, end) = StatsPeriodSummary.window(StatsPeriod.YEAR, today)
        assertEquals(LocalDate.of(2026, 1, 1), start)
        assertEquals(LocalDate.of(2026, 12, 31), end)
        assertEquals("2026年", StatsPeriodSummary.rangeLabel(StatsPeriod.YEAR, today))
    }

    @Test
    fun aDayIsNeverCountedInBothWindows() {
        // 09-10 is inside the current month, 08-31 inside the previous one: no double counting.
        val summary = StatsPeriodSummary.of(
            daily = listOf(day(0, 10), day(10, 20)),
            period = StatsPeriod.MONTH,
            anchor = today
        )
        assertEquals(10 * 60L, summary.readTime)
        assertEquals(20 * 60L, summary.previousReadTime)
    }

    @Test
    fun pickingAPastMonthReportsThatMonth() {
        val august = LocalDate.of(2026, 8, 10)
        assertEquals("2026年8月", StatsPeriodSummary.rangeLabel(StatsPeriod.MONTH, august))
        val summary = StatsPeriodSummary.of(listOf(day(31, 45)), StatsPeriod.MONTH, august)
        assertEquals(45 * 60L, summary.readTime)
    }

    @Test
    fun rangeLabelsDescribeTheWindow() {
        assertEquals("2026年", StatsPeriodSummary.rangeLabel(StatsPeriod.YEAR, today))
        assertTrue(StatsPeriodSummary.rangeLabel(StatsPeriod.WEEK, today).contains("至"))
    }
}
