package com.flowreader.app.ui.screens.stats

import com.flowreader.app.domain.model.DailyStats
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Colour intensity only: a recorded sub-minute session must not look like an unread day. */
internal fun heatmapIntensityMinutes(seconds: Long): Int = if (seconds <= 0) 0 else
    (seconds / 60).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

/** The window the 履历 page reports on. Mirrors the three-tab control of the reference reader. */
enum class StatsPeriod(val label: String, val days: Int) {
    WEEK("周", 7),
    MONTH("月", 30),
    YEAR("年", 365)
}

/**
 * One period's numbers, derived from the same per-day rows the heatmap draws.
 *
 * Everything here is pure so the arithmetic (including the comparison with the previous window) is
 * unit-testable without a database.
 */
data class StatsPeriodSummary(
    val period: StatsPeriod,
    val readTime: Long,
    val readPages: Int,
    val daysRead: Int,
    val booksRead: Int,
    val previousReadTime: Long
) {
    /** Average per *day read*, not per calendar day — an untouched day should not dilute it. */
    val averagePerActiveDay: Long get() = if (daysRead > 0) readTime / daysRead else 0

    /** Percentage change against the previous window, or null when there is nothing to compare. */
    val changePercent: Int?
        get() = if (previousReadTime > 0) {
            (((readTime - previousReadTime) * 100.0) / previousReadTime).toInt()
        } else {
            null
        }

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * @param daily every per-day row available, oldest first is not required — [DailyStats.date]
         *   is the key.
         * @param today injected so tests are not clock-dependent.
         */
        fun of(
            daily: List<DailyStats>,
            period: StatsPeriod,
            anchor: LocalDate = LocalDate.now()
        ): StatsPeriodSummary {
            val byDate = daily.associateBy { it.date }
            // Calendar-aligned window: 2026年8月 means 08-01…08-31, not "the 30 days before the
            // 10th". The old sliding window is why every past month still read 「近 2 个月」.
            val (currentStart, currentEnd) = window(period, anchor)
            val previousEnd = currentStart.minusDays(1)
            val (previousStart, _) = window(period, previousEnd)

            var readTime = 0L
            var readPages = 0
            var daysRead = 0
            var booksRead = 0
            var previousReadTime = 0L

            byDate.forEach { (date, stats) ->
                val day = runCatching { LocalDate.parse(date, ISO) }.getOrNull() ?: return@forEach
                when {
                    !day.isBefore(currentStart) && !day.isAfter(currentEnd) -> {
                        readTime += stats.totalReadTime
                        readPages += stats.totalReadPages
                        booksRead += stats.booksRead
                        if (stats.totalReadTime > 0) daysRead++
                    }

                    !day.isBefore(previousStart) && !day.isAfter(previousEnd) -> {
                        previousReadTime += stats.totalReadTime
                    }
                }
            }

            return StatsPeriodSummary(
                period = period,
                readTime = readTime,
                readPages = readPages,
                daysRead = daysRead,
                booksRead = booksRead,
                previousReadTime = previousReadTime
            )
        }

        /**
         * The window's inclusive first and last day, aligned to the calendar.
         *
         * WEEK runs Monday→Sunday, MONTH runs the 1st→the last day, YEAR runs Jan 1→Dec 31, so a
         * picked window always reads as a real calendar span.
         */
        fun window(period: StatsPeriod, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
            StatsPeriod.WEEK -> {
                val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
                monday to monday.plusDays(6)
            }

            StatsPeriod.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
            StatsPeriod.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
        }

        /** Human label for the window: `2026年8月`, `09月04日至09月10日`, `2026年`. */
        fun rangeLabel(period: StatsPeriod, anchor: LocalDate = LocalDate.now()): String {
            val (start, end) = window(period, anchor)
            return when (period) {
                StatsPeriod.YEAR -> "${anchor.year}年"
                StatsPeriod.MONTH -> "${anchor.year}年${anchor.monthValue}月"
                StatsPeriod.WEEK -> "%02d月%02d日至%02d月%02d日".format(
                    start.monthValue,
                    start.dayOfMonth,
                    end.monthValue,
                    end.dayOfMonth
                )
            }
        }
    }
}
