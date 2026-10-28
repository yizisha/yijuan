package com.flowreader.app.ui.screens.stats

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.flowreader.app.R
import com.flowreader.app.core.designsystem.component.FlowChoiceChip
import com.flowreader.app.core.designsystem.component.FlowIcons
import com.flowreader.app.core.designsystem.component.FlowStateHost
import com.flowreader.app.core.designsystem.component.FlowTopBar
import com.flowreader.app.core.designsystem.token.FlowSpacing
import com.flowreader.app.core.util.FlowFormatters
import com.flowreader.app.domain.model.DailyStats
import com.flowreader.app.domain.model.ReadingReport
import com.flowreader.app.ui.components.durationString
import com.flowreader.app.ui.components.durationText
import com.flowreader.app.ui.components.spokenDateString
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel(), onOpenGoals: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showMoreStats by remember { mutableStateOf(false) }
    var totalsRange by remember { mutableStateOf(TotalsRange.WEEK) }
    var showRangePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            FlowTopBar(title = stringResource(R.string.stats_title), primaryPage = true)
        }
    ) { paddingValues ->
        FlowStateHost(
            isLoading = uiState.isLoading,
            isEmpty = false,
            error = uiState.error,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            onRetry = { viewModel.clearError() }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(FlowSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(FlowSpacing.lg)
            ) {
                // 周 / 月 / 年 (reference-reader layout): the whole page below reports on the
                // selected window, including 累计阅读, which is why there is exactly one switcher.
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
                        StatsPeriod.entries.forEach { period ->
                            FlowChoiceChip(
                                selected = uiState.period == period,
                                onClick = { viewModel.selectPeriod(period) },
                                label = { Text(period.label) }
                            )
                        }
                    }
                }

                item {
                    // Tappable: picks a concrete past window (上周 / 2026年8月 / 2025年), which
                    // "the last N days" alone cannot express.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { showRangePicker = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlowSpacing.xs)
                    ) {
                        Text(
                            text = uiState.rangeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = stringResource(R.string.stats_range_pick),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                uiState.periodSummary?.let { summary ->
                    item { PeriodTotalsCard(summary, periodChartStats(uiState)) }
                    item { PeriodMetricsCard(summary, uiState) }
                }

                if (uiState.heatmapStats.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.stats_heatmap_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.stats_heatmap_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    item {
                        ReadingHeatmap(stats = uiState.heatmapStats)
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.stats_report_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                uiState.weeklyReport?.let { report ->
                    item {
                        ReadingReportCard(
                            report = report,
                            goalMinutes = uiState.weeklyGoalMinutes,
                            onGoalClick = onOpenGoals
                        )
                    }
                }

                uiState.monthlyReport?.let { report ->
                    item {
                        ReadingReportCard(
                            report = report,
                            goalMinutes = uiState.monthlyGoalMinutes,
                            onGoalClick = onOpenGoals
                        )
                    }
                }
                item {
                    TextButton(onClick = { showMoreStats = !showMoreStats }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ui_more_stats))
                        Icon(
                            if (showMoreStats) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                }
                if (showMoreStats) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.stats_today_section),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatCard(
                                modifier = Modifier.weight(1f),
                                icon = FlowIcons.Clock,
                                title = stringResource(R.string.stats_read_time),
                                value = durationText(uiState.todayReadTime),
                                subtitle = stringResource(R.string.stats_subtitle_today)
                            )
                            StatCard(
                                modifier = Modifier.weight(1f),
                                icon = FlowIcons.Book,
                                title = stringResource(R.string.stats_read_pages),
                                value = "${uiState.todayReadPages}",
                                subtitle = stringResource(R.string.stats_unit_pages)
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.stats_total_section),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 累计阅读 has its own 本周 / 本月 / 全部 switcher: this card is about "how much have
                    // I read", which is a different question from "how am I doing this week".
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(FlowSpacing.sm)) {
                            TotalsRange.entries.forEach { range ->
                                FlowChoiceChip(selected = totalsRange == range, onClick = { totalsRange = range }, label = {
                                    Text(
                                        stringResource(
                                            when (range) {
                                                TotalsRange.WEEK -> R.string.stats_period_week
                                                TotalsRange.MONTH -> R.string.stats_period_month
                                                TotalsRange.ALL -> R.string.stats_period_all
                                            }
                                        )
                                    )
                                })
                            }
                        }
                    }

                    item {
                        val totals = remember(uiState.heatmapStats, totalsRange, uiState.totalReadTime, uiState.totalReadPages) {
                            resolveTotals(uiState, totalsRange)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.AccessTime,
                                title = stringResource(R.string.stats_total_time),
                                value = durationText(totals.first),
                                subtitle = stringResource(R.string.stats_subtitle_total)
                            )
                            StatCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.AutoStories,
                                title = stringResource(R.string.stats_total_pages),
                                value = "${totals.second}",
                                subtitle = stringResource(R.string.stats_unit_pages)
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.Book,
                                title = stringResource(R.string.stats_books_read),
                                value = "${uiState.totalBooks}",
                                subtitle = stringResource(R.string.stats_unit_books)
                            )
                            StatCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.LocalFireDepartment,
                                title = stringResource(R.string.stats_streak),
                                value = "${uiState.currentStreak}",
                                subtitle = stringResource(R.string.stats_unit_days)
                            )
                        }
                    }

                    if (uiState.recentDailyStats.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.stats_trend_section),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        item {
                            ReadTimeBarChart(
                                dailyStats = uiState.recentDailyStats,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRangePicker) {
        RangePickerSheet(
            period = uiState.period,
            anchor = uiState.anchor,
            onSelect = { viewModel.selectAnchor(it) },
            onDismiss = { showRangePicker = false }
        )
    }
}

@Composable
private fun ReadingReportCard(report: ReadingReport, goalMinutes: Int, onGoalClick: () -> Unit) {
    val goalSeconds = goalMinutes * 60L
    val progress = if (goalSeconds > 0) (report.totalReadTime.toFloat() / goalSeconds).coerceIn(0f, 1f) else 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(report.rangeLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onGoalClick) { Text(stringResource(R.string.stats_goal_button, goalMinutes)) }
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(
                    R.string.stats_report_summary,
                    durationText(report.totalReadTime),
                    report.totalReadPages
                )
            )
            val noneLabel = stringResource(R.string.stats_value_none)
            Text(stringResource(R.string.stats_fastest_day, report.fastestReadingDay?.date ?: noneLabel))
            Text(stringResource(R.string.stats_most_read_book, report.mostReadBookTitle ?: noneLabel))
            if (progress < 1f) {
                Text(
                    text = stringResource(
                        R.string.stats_goal_remaining,
                        ((goalSeconds - report.totalReadTime).coerceAtLeast(0) / 60).toInt()
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** The three windows offered on the 累计阅读 card. */
private enum class TotalsRange { WEEK, MONTH, ALL }

/**
 * Concrete windows to jump to, newest first: 本周/上周/前三周 for 周, the last twelve months for 月,
 * the last five years for 年. Each entry is the window's last day.
 */
private fun rangeOptions(period: StatsPeriod, today: LocalDate): List<Pair<String, LocalDate>> = when (period) {
    StatsPeriod.WEEK -> buildList {
        add("本周" to today)
        repeat(7) { index ->
            val end = today.minusWeeks((index + 1).toLong())
            add(StatsPeriodSummary.rangeLabel(StatsPeriod.WEEK, end) to end)
        }
    }

    StatsPeriod.MONTH -> buildList {
        add("本月" to today)
        repeat(11) { index ->
            val end = today.minusMonths((index + 1).toLong())
            add("${end.year}年${end.monthValue}月" to end)
        }
    }

    StatsPeriod.YEAR -> buildList {
        add("今年" to today)
        repeat(4) { index ->
            val end = today.minusYears((index + 1).toLong())
            add("${end.year}年" to end)
        }
    }
}

/** Half-screen picker for the reporting window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerSheet(period: StatsPeriod, anchor: LocalDate, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = remember { LocalDate.now() }
    val options = remember(period, today) { rangeOptions(period, today) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.stats_range_pick),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = FlowSpacing.lg, vertical = FlowSpacing.sm)
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(options.size) { index ->
                    val (label, end) = options[index]
                    ListItem(
                        headlineContent = { Text(label) },
                        trailingContent = {
                            if (end == anchor) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        modifier = Modifier.clickable {
                            onSelect(end)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Time and pages for the selected 累计 window.
 *
 * 全部 uses the all-time aggregate the ViewModel already loaded; the shorter windows are summed from
 * the same per-day rows the heatmap draws, so the card cannot disagree with the chart above it.
 */
private fun resolveTotals(uiState: StatsUiState, range: TotalsRange): Pair<Long, Int> = when (range) {
    TotalsRange.ALL -> uiState.totalReadTime to uiState.totalReadPages
    TotalsRange.WEEK -> {
        val summary = StatsPeriodSummary.of(uiState.heatmapStats, StatsPeriod.WEEK)
        summary.readTime to summary.readPages
    }

    TotalsRange.MONTH -> {
        val summary = StatsPeriodSummary.of(uiState.heatmapStats, StatsPeriod.MONTH)
        summary.readTime to summary.readPages
    }
}

/**
 * GitHub-style contribution grid: one column per week, one row per weekday, darker = more minutes.
 *
 * Built from the same `DailyStats` the trend chart uses, so there is no second source of truth for
 * reading time. Days with no row in the database render as the empty level rather than a gap.
 */
@Composable
private fun ReadingHeatmap(stats: List<DailyStats>, modifier: Modifier = Modifier) {
    val today = remember { LocalDate.now() }
    // Anchor the first column on a Monday so rows really are weekdays.
    val start = remember(today) {
        today.minusDays((HEATMAP_WEEKS * 7 - 1).toLong()).let { date ->
            date.minusDays((date.dayOfWeek.value - 1).toLong())
        }
    }
    val minutesByDate = remember(stats) {
        stats.associate { it.date to heatmapIntensityMinutes(it.totalReadTime) }
    }
    val totalWeeks = ((ChronoUnit.DAYS.between(start, today).toInt()) / 7) + 1
    val cell = 11.dp
    val gap = 2.dp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        for (weekday in 1..7) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (week in 0 until totalWeeks) {
                    val date = start.plusDays((week * 7 + weekday - 1).toLong())
                    if (date.isAfter(today)) {
                        Spacer(modifier = Modifier.size(cell))
                    } else {
                        val minutes = minutesByDate[date.format(DateTimeFormatter.ISO_LOCAL_DATE)] ?: 0
                        Box(
                            modifier = Modifier
                                .size(cell)
                                .clip(RoundedCornerShape(2.dp))
                                .background(heatmapColor(minutes))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun heatmapColor(minutes: Int): Color {
    val base = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant
    return when {
        minutes <= 0 -> surface
        minutes < 15 -> base.copy(alpha = 0.25f)
        minutes < 30 -> base.copy(alpha = 0.45f)
        minutes < 60 -> base.copy(alpha = 0.7f)
        else -> base
    }
}

private const val HEATMAP_WEEKS = 26

/**
 * The big number at the top of 履历: total time in the selected window, the daily average on days
 * that were actually read, and the change against the previous window.
 */
@Composable
internal fun PeriodTotalsCard(summary: StatsPeriodSummary, dailyStats: List<DailyStats> = emptyList()) {
    val hours = summary.readTime / 3_600L
    val minutes = (summary.readTime % 3_600L) / 60L

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(FlowSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FlowSpacing.sm)
        ) {
            Text(
                stringResource(R.string.stats_read_time),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$hours",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.stats_unit_hours),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Text(
                    text = "$minutes",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.stats_unit_minutes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                text = buildString {
                    append(stringResource(R.string.stats_average_per_day, durationText(summary.averagePerActiveDay)))
                    summary.changePercent?.let { change ->
                        append("，")
                        append(
                            if (change >= 0) {
                                stringResource(R.string.stats_change_up, change)
                            } else {
                                stringResource(R.string.stats_change_down, -change)
                            }
                        )
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ReadTimeBarChart(dailyStats, Modifier.fillMaxWidth().height(176.dp), stringResource(R.string.room_reading_trend))
        }
    }
}

/** The chart follows the same calendar window as the headline, including days with no reading. */
internal fun periodChartStats(state: StatsUiState): List<DailyStats> {
    val (start, end) = StatsPeriodSummary.window(state.period, state.anchor)
    val byDate = state.heatmapStats.associateBy { it.date }
    val days = generateSequence(start) { day -> day.plusDays(1).takeUnless { it.isAfter(end) } }
        .map { date -> byDate[date.toString()] ?: DailyStats(date.toString(), 0L, 0, 0) }.toList()
    return if (state.period == StatsPeriod.YEAR) {
        days.groupBy { it.date.take(7) }.map { (month, rows) ->
            DailyStats("$month-01", rows.sumOf { it.totalReadTime }, rows.sumOf { it.totalReadPages }, rows.sumOf { it.booksRead })
        }
    } else {
        days
    }
}

/** 已读天数 / 本读完 / 本读过 / 笔记, the four counters the reference reader shows. */
@Composable
private fun PeriodMetricsCard(summary: StatsPeriodSummary, uiState: StatsUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(FlowSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FlowSpacing.md)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricValue(
                    modifier = Modifier.weight(1f),
                    value = "${summary.daysRead}",
                    label = stringResource(R.string.stats_unit_days_read)
                )
                MetricValue(
                    modifier = Modifier.weight(1f),
                    value = "${uiState.totalBooks}",
                    label = stringResource(R.string.stats_books_finished)
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricValue(
                    modifier = Modifier.weight(1f),
                    value = "${summary.booksRead}",
                    label = stringResource(R.string.stats_books_touched)
                )
                MetricValue(
                    modifier = Modifier.weight(1f),
                    value = "${uiState.currentStreak}",
                    label = stringResource(R.string.stats_streak)
                )
            }
        }
    }
}

@Composable
private fun MetricValue(modifier: Modifier = Modifier, value: String, label: String) {
    Column(modifier = modifier) {
        Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatCard(modifier: Modifier = Modifier, icon: ImageVector, title: String, value: String, subtitle: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReadTimeBarChart(
    dailyStats: List<DailyStats>,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.stats_trend_card_title)
) {
    val primaryColor = MaterialTheme.colorScheme.primary.copy(alpha = .58f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant

    val maxReadTime = dailyStats.maxOfOrNull { it.totalReadTime } ?: 0L
    val todayDate = dailyStats.lastOrNull()?.date

    // Each column carries semantics so TalkBack reads the date and duration instead of announcing an
    // unlabelled Canvas. Built from resources via Context rather than stringResource because this runs
    // inside remember. The v51 chart also painted every bar twice — an identical surfaceVariant rect
    // completely covered by the primary one — so the track is now the full column height and the value
    // bar is drawn once.
    val context = LocalContext.current
    val chartDescription = remember(dailyStats, context) {
        if (dailyStats.isEmpty()) {
            context.getString(R.string.stats_chart_empty_desc)
        } else {
            dailyStats.joinToString(context.getString(R.string.stats_chart_separator)) { stat ->
                context.getString(
                    R.string.stats_chart_entry,
                    context.spokenDateString(stat.date),
                    context.durationString(stat.totalReadTime)
                )
            }
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(modifier = Modifier.padding(vertical = FlowSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.stats_trend_peak, durationText(maxReadTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(FlowSpacing.sm))

            if (dailyStats.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.stats_trend_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .semantics { contentDescription = chartDescription }
                ) {
                    val slot = size.width / dailyStats.size
                    val barWidth = slot * 0.55f
                    val chartHeight = size.height - 4f

                    drawRect(
                        color = axisColor,
                        topLeft = Offset(0f, chartHeight),
                        size = Size(size.width, 2f)
                    )

                    dailyStats.forEachIndexed { index, stat ->
                        val x = index * slot + (slot - barWidth) / 2f
                        val barHeight = if (maxReadTime > 0) {
                            (stat.totalReadTime.toFloat() / maxReadTime) * chartHeight
                        } else {
                            0f
                        }
                        if (barHeight > 0f) {
                            drawRect(
                                color = primaryColor,
                                topLeft = Offset(x, chartHeight - barHeight),
                                size = Size(barWidth, barHeight)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(FlowSpacing.sm))

                BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
                    val labelWidth = 56.dp
                    val indices = if (dailyStats.size <= 5) {
                        dailyStats.indices.toList()
                    } else {
                        (0..4).map { kotlin.math.round(it * (dailyStats.size - 1) / 4f).toInt() }.distinct()
                    }
                    indices.forEach { index ->
                        val stat = dailyStats[index]
                        val centre = maxWidth * ((index + .5f) / dailyStats.size)
                        Text(
                            FlowFormatters.shortDate(stat.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(labelWidth)
                                .offset(x = (centre - labelWidth / 2).coerceIn(0.dp, (maxWidth - labelWidth).coerceAtLeast(0.dp)))
                        )
                    }
                }
            }
        }
    }
}
