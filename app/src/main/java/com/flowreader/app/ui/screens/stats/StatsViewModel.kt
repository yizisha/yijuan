package com.flowreader.app.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.domain.model.DailyStats
import com.flowreader.app.domain.model.ReadingReport
import com.flowreader.app.domain.model.ReadingSummary
import com.flowreader.app.domain.repository.ReadingStatsRepository
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val todayReadTime: Long = 0,
    val todayReadPages: Int = 0,
    val totalReadTime: Long = 0,
    val totalReadPages: Int = 0,
    val totalBooks: Int = 0,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val recentDailyStats: List<DailyStats> = emptyList(),
    /** Half a year of per-day totals for the heatmap. */
    val heatmapStats: List<DailyStats> = emptyList(),
    val weeklyGoalMinutes: Int = 210,
    val monthlyGoalMinutes: Int = 900,
    val weeklyReport: ReadingReport? = null,
    val monthlyReport: ReadingReport? = null,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val periodSummary: StatsPeriodSummary? = null,
    val rangeLabel: String = "",
    /** Last day of the selected window; today by default, and moves when a past range is picked. */
    val anchor: java.time.LocalDate = java.time.LocalDate.now()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val readingStatsRepository: ReadingStatsRepository,
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()
    private var statsLoadJob: kotlinx.coroutines.Job? = null
    private var heatmapJob: kotlinx.coroutines.Job? = null

    init {
        // Primary tabs now retain their ViewModels. Keep cached results, but do not keep
        // querying/aggregating statistics every five seconds while the user reads elsewhere.
        viewModelScope.launch {
            _uiState.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { visible ->
                if (visible) {
                    loadStats()
                    loadHeatmap()
                } else {
                    statsLoadJob?.cancel()
                    heatmapJob?.cancel()
                }
            }
        }
        viewModelScope.launch {
            combine(settingsRepository.getWeeklyReadingGoal(), settingsRepository.getMonthlyReadingGoal()) { week, month -> week to month }
                .collect { (week, month) -> _uiState.update { it.copy(weeklyGoalMinutes = week, monthlyGoalMinutes = month) } }
        }
    }

    /**
     * 周 / 月 / 年. The summary is recomputed from the same per-day rows the heatmap draws, so
     * switching the period costs no query — only arithmetic.
     *
     * Switching the period also snaps the window back to the current one: picking 月 after browsing
     * to 去年 should not leave the page showing a year-old month.
     */
    fun selectPeriod(period: StatsPeriod) {
        val today = java.time.LocalDate.now()
        _uiState.update { state ->
            state.copy(
                period = period,
                anchor = today,
                periodSummary = StatsPeriodSummary.of(state.heatmapStats, period, today),
                rangeLabel = StatsPeriodSummary.rangeLabel(period, today)
            )
        }
    }

    /**
     * Jumps to a concrete past window — 上个月, 2025 年, 三周前 — instead of only "the last N days".
     * [anchor] is the window's last day.
     */
    fun selectAnchor(anchor: java.time.LocalDate) {
        _uiState.update { state ->
            state.copy(
                anchor = anchor,
                periodSummary = StatsPeriodSummary.of(state.heatmapStats, state.period, anchor),
                rangeLabel = StatsPeriodSummary.rangeLabel(state.period, anchor)
            )
        }
    }

    /**
     * The heatmap needs a longer window than the trend chart, and `getRecentDailyStats` already
     * aggregates one row per book per day into one row per day — so this is a second collection on
     * the same flow rather than a second query shape.
     */
    private fun loadHeatmap() {
        heatmapJob?.cancel()
        heatmapJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            readingStatsRepository.getRecentDailyStats(HEATMAP_DAYS).collect { stats ->
                _uiState.update { state ->
                    state.copy(
                        heatmapStats = stats,
                        periodSummary = StatsPeriodSummary.of(stats, state.period, state.anchor),
                        rangeLabel = StatsPeriodSummary.rangeLabel(state.period, state.anchor)
                    )
                }
            }
        }
    }

    private fun loadStats() {
        statsLoadJob?.cancel()
        statsLoadJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            _uiState.update { it.copy(error = null) }
            try {
                readingStatsRepository.getRecentDailyStats(7).collect { dailyStats ->
                    val todayReadTime = readingStatsRepository.getTodayReadTime()
                    val todayReadPages = readingStatsRepository.getTodayReadPages()
                    val totalReadTime = readingStatsRepository.getTotalReadTime()
                    val totalReadPages = readingStatsRepository.getTotalReadPages()
                    val summary = readingStatsRepository.getReadingSummary()
                    val weeklyGoal = settingsRepository.getWeeklyReadingGoal().first()
                    val monthlyGoal = settingsRepository.getMonthlyReadingGoal().first()
                    val weeklyReport = readingStatsRepository.getReadingReport(7)
                    val monthlyReport = readingStatsRepository.getReadingReport(30)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            todayReadTime = todayReadTime,
                            todayReadPages = todayReadPages,
                            totalReadTime = totalReadTime,
                            totalReadPages = totalReadPages,
                            totalBooks = summary.totalBooks,
                            currentStreak = summary.currentStreak,
                            longestStreak = summary.longestStreak,
                            recentDailyStats = dailyStats,
                            weeklyGoalMinutes = weeklyGoal,
                            monthlyGoalMinutes = monthlyGoal,
                            weeklyReport = weeklyReport,
                            monthlyReport = monthlyReport
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "加载统计失败: ${e.localizedMessage ?: "未知错误"}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun refresh() {
        loadStats()
    }

    fun updateWeeklyGoal(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateWeeklyReadingGoal(minutes)
            refresh()
        }
    }

    fun updateMonthlyGoal(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.updateMonthlyReadingGoal(minutes)
            refresh()
        }
    }

    private companion object {
        /** Two years: enough for the 年 summary and its previous-period comparison. */
        const val HEATMAP_DAYS = 366 * 2
    }
}
