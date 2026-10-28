package com.flowreader.feature.reader

/**
 * 有效阅读规则与时长的集中配置（主计划 10.3 / 10.4 / R3）。
 *
 * 所有阈值**只在这里定义**，实现不得各写一份魔数，也不得为了让门禁变绿而私自放宽
 * （主计划 0.3 与第 16 节第 11 条）。
 */
object ReaderSessionPolicy {

    /** v1 空闲上限：超过它就不再累计时间（主计划 10.3）。 */
    const val IDLE_TIMEOUT_MS = 300_000L

    /** 普通滚动落库的合并窗口（沿用既有 3 秒口径）。 */
    const val SAVE_DEBOUNCE_MS = 3_000L

    /** 定期生成 delta 的有效时长间隔（主计划 10.5）。 */
    const val DELTA_INTERVAL_MS = 30_000L

    /** 同一源范围连续可见多久才算一次合格曝光（主计划 10.4 第 2 条）。 */
    const val EXPOSURE_QUALIFY_MS = 2_000L

    /** 速度采样：窗口下限。 */
    const val SPEED_MIN_WINDOW_MS = 60_000L

    /** 速度采样：窗口内至少新增多少有效字。 */
    const val SPEED_MIN_UNITS = 300L

    /** 速度采样：保留的最近窗口数。 */
    const val SPEED_MAX_WINDOWS = 20

    /** 异常窗口下限（字/分钟）：只排除 ETA 估计，**不删**用户真实时间。 */
    const val SPEED_FLOOR_UNITS_PER_MINUTE = 50.0

    /** 异常窗口上限（字/分钟）：同上。 */
    const val SPEED_CEILING_UNITS_PER_MINUTE = 2_000.0
}

/**
 * 阅读速度估计（主计划 10.4 第 6—9 条）。
 *
 * 与旧实现的三点差异：
 * 1. 旧 `ReaderSessionTracker` 用 EMA 累积**瞬时**速度，一次 1 秒 10 字的采样就能成为“阅读速度”；
 *    这里只在窗口满足 ≥60 秒且 ≥300 有效字时才纳入，并用**总字/总分钟**而不是平均瞬时速度。
 * 2. 旧 `ReaderProgressEngine` 用 `speed.coerceAtLeast(100f)` 在无样本时伪造时长；这里返回 null，
 *    UI 显示「剩余时间：估算中」。
 * 3. 异常窗口（<50 或 >2000 字/分钟）只被排除出 ETA，**用户真实阅读时间照常保存**。
 */
class ReadingSpeedEstimator {

    private data class Window(val units: Long, val millis: Long)

    private val windows = ArrayDeque<Window>()

    val hasEstimate: Boolean get() = windows.isNotEmpty()

    /** 记入一个已完成的有效窗口；不合格的窗口直接丢弃（不影响时长统计）。 */
    fun addWindow(units: Long, millis: Long) {
        if (units < ReaderSessionPolicy.SPEED_MIN_UNITS) return
        if (millis < ReaderSessionPolicy.SPEED_MIN_WINDOW_MS) return
        windows.addLast(Window(units, millis))
        while (windows.size > ReaderSessionPolicy.SPEED_MAX_WINDOWS) windows.removeFirst()
    }

    /** 有效字/分钟；样本不足或总时长非正时返回 null（调用方显示“估算中”）。 */
    fun unitsPerMinute(): Double? {
        if (windows.isEmpty()) return null
        var units = 0L
        var millis = 0L
        windows.forEach {
            units += it.units
            millis += it.millis
        }
        if (millis <= 0L || units <= 0L) return null
        val perMinute = units.toDouble() / (millis.toDouble() / 60_000.0)
        if (!perMinute.isFinite() || perMinute <= 0.0) return null
        return perMinute
    }

    /** 是否落在可用于 ETA 的合理区间；异常窗口只影响 ETA。 */
    fun isPlausible(): Boolean {
        val speed = unitsPerMinute() ?: return false
        return speed >= ReaderSessionPolicy.SPEED_FLOOR_UNITS_PER_MINUTE &&
            speed <= ReaderSessionPolicy.SPEED_CEILING_UNITS_PER_MINUTE
    }

    fun reset() = windows.clear()

    companion object {
        /**
         * 剩余分钟（主计划 10.4 第 9 条）：`ceil(remainingUnits / speed)`；
         * remaining=0 → 0（已到末尾）；速度缺失或非有限 → null（未知），**不伪造**。
         */
        fun remainingMinutes(remainingUnits: Long, unitsPerMinute: Double?): Int? {
            if (remainingUnits <= 0L) return 0
            val speed = unitsPerMinute ?: return null
            if (!speed.isFinite() || speed <= 0.0) return null
            val minutes = remainingUnits.toDouble() / speed
            if (!minutes.isFinite() || minutes < 0.0) return null
            return kotlin.math.ceil(minutes).toInt()
        }
    }
}

/**
 * 一次会话的原始增量（主计划 10.1 / 10.5）。四类数据**互斥计量**：
 * 视觉阅读时长与 TTS 播放时长不能同时增长（10.3 末段），翻页次数与阅读时长脱钩。
 */
data class SessionDelta(
    val visualReadMillis: Long = 0L,
    val ttsReadMillis: Long = 0L,
    val qualifiedTextUnits: Long = 0L,
    val settledTurns: Int = 0
) {
    /** 主计划 10.5：只要任一分量 > 0 就可保存，不再要求同时有页数。 */
    val isPersistable: Boolean
        get() = visualReadMillis > 0L || ttsReadMillis > 0L || qualifiedTextUnits > 0L || settledTurns > 0

    val totalMillis: Long get() = visualReadMillis + ttsReadMillis

    operator fun plus(other: SessionDelta) = SessionDelta(
        visualReadMillis = visualReadMillis + other.visualReadMillis,
        ttsReadMillis = ttsReadMillis + other.ttsReadMillis,
        qualifiedTextUnits = qualifiedTextUnits + other.qualifiedTextUnits,
        settledTurns = settledTurns + other.settledTurns
    )

    companion object {
        val NONE = SessionDelta()
    }
}

/**
 * 有效阅读时间状态机（主计划 10.3）。
 *
 * 与旧 `ReaderSessionTracker` 的关键差异：
 * - 用**可注入单调时钟**（Android 侧 `SystemClock.elapsedRealtime()`），墙上时间只用于划分自然日。
 *   旧实现默认 `System::currentTimeMillis`，改钟或时区切换会让时长暴增或为负（F04）。
 * - 进入空闲时**先结算到 `lastActivity + idleTimeout`**，再把状态切到 IDLE；睡眠/后台整段不计。
 * - 事件顺序固定：先按事件时间把上一状态的时间累积到边界，再切新状态。
 */
class ReadingTimeStateMachine(
    private val nowProvider: () -> Long,
    private val idleTimeoutMs: Long = ReaderSessionPolicy.IDLE_TIMEOUT_MS
) {

    enum class State { ACTIVE, IDLE }

    var state: State = State.IDLE
        private set

    private var lastActivityAt = 0L
    private var lastSettledAt = 0L

    /**
     * 不能用 `lastSettledAt <= 0` 当“未初始化”哨兵：注入的单调时钟可以从 0 开始，那样首段
     * 有效时间会被静默丢弃（本项目的测试就抓到了这一点）。
     */
    private var initialized = false

    /** 视觉与 TTS 互斥：TTS 播放期间 [ttsPlaying] 为 true，视觉时长暂停累计。 */
    var ttsPlaying: Boolean = false
        private set

    private var visualMillis = 0L
    private var ttsMillis = 0L

    fun setTtsPlaying(playing: Boolean) {
        settle(nowProvider())
        ttsPlaying = playing
    }

    /** 进入前台稳定正文。 */
    fun enterContent() {
        val now = nowProvider()
        settle(now)
        state = State.ACTIVE
        lastActivityAt = now
        lastSettledAt = now
        initialized = true
    }

    /** 真实交互（触摸、翻页、选择）。自动动画、重组、保存回调**不**刷新活动点。 */
    fun onUserActivity() {
        val now = nowProvider()
        settle(now)
        state = State.ACTIVE
        lastActivityAt = now
        lastSettledAt = now
        initialized = true
    }

    /** 离开正文（后台、弹层遮挡、切章加载、锁屏）。 */
    fun exitContent() {
        val now = nowProvider()
        settle(now)
        state = State.IDLE
        lastSettledAt = now
    }

    /** 生成并清空当前增量。 */
    fun takeDelta(): SessionDelta {
        val now = nowProvider()
        settle(now)
        val delta = SessionDelta(visualReadMillis = visualMillis, ttsReadMillis = ttsMillis)
        visualMillis = 0L
        ttsMillis = 0L
        return delta
    }

    private fun settle(now: Long) {
        if (state != State.ACTIVE || !initialized) {
            lastSettledAt = now
            return
        }
        // 只累计到「最后一次活动 + 空闲上限」，睡眠/后台整段不计（主计划 10.3）。
        val cutoff = minOf(now, lastActivityAt + idleTimeoutMs)
        val elapsed = (cutoff - lastSettledAt).coerceAtLeast(0L)
        if (elapsed > 0L) {
            if (ttsPlaying) ttsMillis += elapsed else visualMillis += elapsed
        }
        lastSettledAt = cutoff
        if (now - lastActivityAt >= idleTimeoutMs) state = State.IDLE
    }
}
