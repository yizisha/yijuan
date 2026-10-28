package com.flowreader.feature.reader

import com.flowreader.app.domain.model.TextLocator

/**
 * 阅读导航状态机（主计划 6.1、6.2，INV07/INV08）。
 *
 * **纯函数 reducer**：不碰文件、不碰 Compose、不做 I/O。跨章加载、布局测量、数据库写入
 * 都由宿主执行 [Effect] 完成，再通过 [onViewport] 把结果喂回来。
 *
 * ## 为什么要重写这一层
 *
 * 旧实现的失败模式写在 G0 复现测试里：
 * - 一次触摸序列可以被多个回调共同触发（同一滑动翻了两页）；
 * - 旧章节的异步加载回调回来时把新章节的状态覆盖掉（"回退又被弹回"）；
 * - 用动画/加载期间的重复点击排队补翻，稳定后继续翻好几页。
 *
 * 对应到本文件的三个不变量：
 * - **INV07**：一次触摸序列至多提交一个 [Intent.Turn] —— 派发前 `gate.accept(gestureId)`，
 *   同一 `gestureId` 第二次到达直接拒绝；动画/加载期间开始的整段手势被标为 `ignored`，
 *   即使抬起时已经空闲也**不补触发**。
 * - **INV08**：只有当前 `navigationGeneration` / `layoutGeneration` 的反馈可以提交位置。
 *   过期快照一律 [Effect.IgnoredStale]，不写任何东西。
 * - **6.2 表格**：非空闲状态收到额外边缘 Turn → 丢弃整次触摸序列，**不排队**。
 *
 * ## 状态与转移（严格按 6.2 表格）
 *
 * | 当前状态 | 输入 | 动作/新状态 |
 * |---|---|---|
 * | Idle | 有效 Turn | 原子取得导航所有权 → [State.Resolving] |
 * | Resolving | 目标解析成功 | → [State.Loading]（**先不改已提交位置**） |
 * | Loading | 内容已加载且 token 有效 | → [State.LayingOut]；失败 → [State.ErrorWithPreviousContent] |
 * | LayingOut | 最新布局可用 | → [State.Restoring] |
 * | Restoring | 相同 token 的稳定快照 | → [State.Committing] → 一次提交 → Idle |
 * | 非 Idle | 额外边缘 Turn | 丢弃整次序列，不排队、不补翻 |
 * | 非 Idle | 用户显式 TOC/LINK/SEARCH 目标 | 取消旧请求、generation++、只保留最新 |
 * | 任意 | 页面退出/换书 | generation++、取消、保存最后已确认快照 |
 * | Idle/布局中 | 设置或视口改变 | 合并同帧变化、保留源锚点、layoutGeneration++ → REFLOW |
 */
class ReaderNavigationCoordinator(
    /** 历史栈容量（8.3 第 2 条：50，超出移除最旧项）。 */
    private val historyCapacity: Int = 50
) {

    // ------------------------------------------------------------------ 输入

    /**
     * 导航意图。[Intent.Turn] 的 [Intent.Turn.gestureId] 是**一次 pointer DOWN→UP/CANCEL 序列**的标识，
     * 由手势层分配；同一 `gestureId` 只允许提交一次。
     */
    sealed interface Intent {
        /** 前后翻页/滚动一屏。[ignored] 为 true 表示该触摸序列开始时处于动画/加载中。 */
        data class Turn(val direction: TurnDirection, val gestureId: Long, val ignored: Boolean = false) : Intent

        /** 显式跳转（目录、搜索、书签、内链、听书 seek）。 */
        data class GoTo(val target: TextLocator, val origin: NavigationOrigin, val requestId: Long) : Intent

        /** 打开书后的持久位置恢复。 */
        data class Restore(val target: TextLocator, val requestId: Long) : Intent

        /** 设置/视口变化导致的重排；[anchor] 是重排前固定的权威定位。 */
        data class Reflow(val anchor: TextLocator?, val requestId: Long) : Intent

        /**
         * 打开辅助内容（脚注/尾注/非主线文档）。
         *
         * 6.1 要求主线进度不被污染：本意图**不修改**权威定位，只推进历史栈。
         */
        data class OpenAuxiliary(val target: TextLocator, val requestId: Long) : Intent

        /** 返回内链原处（8.3）：从历史栈弹出。 */
        data class ReturnFromLink(val requestId: Long) : Intent

        /** 页面退出 / 换书 / 保存最后已确认快照。 */
        data class Detach(val reason: String) : Intent
    }

    enum class TurnDirection { FORWARD, BACKWARD }

    /** 意图来源，必须保留到统计端（6.1）。 */
    enum class NavigationOrigin { GESTURE, TOC, LINK, BOOKMARK, SEARCH, TTS, RESTORE, REFLOW }

    /**
     * 布局反馈。**它不是导航意图**（6.1 末段）。
     *
     * [visibleStart]/[visibleEnd] 是实际可见的源范围；[settled] 表示布局已稳定
     * （自动分页过渡结束前不登记新页面曝光，10.3）。
     */
    data class ViewportSnapshot(
        val navigationGeneration: Long,
        val layoutGeneration: Long,
        val firstVisible: TextLocator?,
        val visibleRange: SourceSpanRange,
        val pageRange: SourceSpanRange?,
        val settled: Boolean,
        val atMainEnd: Boolean,
        val layoutKey: String
    )

    /** 一个源范围（避免让导航层依赖内容包内部类型）。 */
    data class SourceSpanRange(val documentKey: String, val startUtf16: Int, val endUtf16Exclusive: Int)

    // ------------------------------------------------------------------ 状态

    sealed interface State {
        /** 没有任何进行中的导航。 */
        data object Idle : State

        /**
         * 正在解析目标（确定 revision/section），**尚未修改已提交位置**。
         *
         * [requestedTarget] 是该请求的目标：Turn 沿用的是已提交定位（方向由宿主按 section/页边界解释），
         * 其余意图是用户显式给出的目标。请求目标必须随状态携带，否则 `onTargetResolved` 只能去读
         * `committedLocator`，会把"用户要求去哪"和"现在在哪"混为一谈。
         */
        data class Resolving(
            val requestId: Long,
            val origin: NavigationOrigin,
            val requestedTarget: TextLocator
        ) : State

        /** 内容加载中。 */
        data class Loading(val requestId: Long, val origin: NavigationOrigin, val target: TextLocator) : State

        /** 布局计算中。 */
        data class LayingOut(val requestId: Long, val origin: NavigationOrigin, val target: TextLocator) : State

        /** 等待包含目标的那一页/行稳定。 */
        data class Restoring(val requestId: Long, val origin: NavigationOrigin, val target: TextLocator) : State

        /** 已交给有序写入器，等待提交完成。 */
        data class Committing(val requestId: Long, val origin: NavigationOrigin, val target: TextLocator) : State

        /**
         * 加载失败：**保留前一章与定位**，不先把标题/索引切过去再显示空白正文（6.2）。
         */
        data class ErrorWithPreviousContent(val reasonCode: String, val detail: String?) : State
    }

    /** 状态与效果。 */
    data class Transition(val state: ReaderNavigationState, val effects: List<Effect>)

    /** 宿主可见的完整状态（不可变快照，6.7：保存逻辑不得读到中途变化的 uiState）。 */
    data class ReaderNavigationState(
        val state: State = State.Idle,
        val navigationGeneration: Long = 0L,
        val layoutGeneration: Long = 0L,
        /** 权威内容定位（INV01）：唯一事实来源；浏览辅助内容时保持主线定位不动。 */
        val committedLocator: TextLocator? = null,
        /**
         * **当前屏幕上正在显示**的位置（可能是辅助内容）。
         *
         * 与 [committedLocator] 分开的必要性：8.3 的返回栈要记录"从哪儿跳走的"，
         * 而主线进度要用 [lastMainLocator]。三者混成一个字段时，脚注跳转会把主线定位带走。
         */
        val contentLocator: TextLocator? = null,
        /** 浏览辅助内容时保持主线进度用（10.2）。 */
        val lastMainLocator: TextLocator? = null,
        val layoutKey: String = "",
        val atMainEnd: Boolean = false,
        /** 返回栈（8.3）：只记录内容位置，不保存像素。 */
        val history: List<TextLocator> = emptyList(),
        /** 已接受并完成的导航次数（settledTurns 的来源）。 */
        val settledTurns: Int = 0
    )

    // ------------------------------------------------------------------ 效果

    sealed interface Effect {
        /** 要求宿主加载/解析目标。[requestId] 用于识别过期结果。 */
        data class LoadTarget(
            val requestId: Long,
            val origin: NavigationOrigin,
            val target: TextLocator,
            val navigationGeneration: Long
        ) : Effect

        /** 内容已就绪：要求布局定位到目标。 */
        data class LayOut(
            val requestId: Long,
            val target: TextLocator,
            val navigationGeneration: Long,
            val layoutGeneration: Long
        ) : Effect

        /** 提交新的权威定位（交给有序写入器，6.7）。 */
        data class Commit(
            val locator: TextLocator,
            val origin: NavigationOrigin,
            val atMainEnd: Boolean
        ) : Effect

        /** 被丢弃的输入，带原因码（诊断与统计都要能区分"丢弃"与"完成"）。 */
        data class Dropped(val reasonCode: String, val detail: String? = null) : Effect

        /** 过期反馈：不提交任何东西（INV08）。 */
        data class IgnoredStale(val reasonCode: String) : Effect

        /** 取消在途任务（换书/退出/新目标）。 */
        data class CancelInFlight(val requestId: Long, val reasonCode: String) : Effect

        /** 一次边界提示（6.4：第一章开头后退保持原位并一次提示；末尾前进同理）。 */
        data class BoundaryFeedback(val atStart: Boolean, val atEnd: Boolean) : Effect
    }

    /** 手势闸门：同一 gestureId 只放行一次，并记住"被忽略的序列"。 */
    private val acceptedGestures = HashSet<Long>()
    private val ignoredGestures = HashSet<Long>()

    private var current = ReaderNavigationState()

    fun state(): ReaderNavigationState = current

    /** 手势层在 DOWN 时登记：[ignored] 为 true 表示这个序列开始于动画/加载/恢复期间。 */
    fun noteGestureStart(gestureId: Long, ignored: Boolean) {
        if (ignored) ignoredGestures += gestureId else ignoredGestures -= gestureId
    }

    // ------------------------------------------------------------------ 派发

    fun dispatch(intent: Intent): Transition = when (intent) {
        is Intent.Turn -> dispatchTurn(intent)
        is Intent.GoTo -> dispatchGoTo(intent)
        is Intent.Restore -> dispatchRestore(intent)
        is Intent.Reflow -> dispatchReflow(intent)
        is Intent.OpenAuxiliary -> dispatchOpenAuxiliary(intent)
        is Intent.ReturnFromLink -> dispatchReturnFromLink(intent)
        is Intent.Detach -> dispatchDetach(intent)
    }

    private fun dispatchTurn(intent: Intent.Turn): Transition {
        // 整段序列开始时就不该生效：即使抬起时已经空闲也不补翻（6.3 第 4 条、INV07）。
        if (intent.ignored || ignoredGestures.contains(intent.gestureId)) {
            ignoredGestures -= intent.gestureId
            return Transition(current, listOf(Effect.Dropped("GESTURE_IGNORED", "gesture=${intent.gestureId}")))
        }
        if (!acceptedGestures.add(intent.gestureId)) {
            return Transition(current, listOf(Effect.Dropped("GESTURE_ALREADY_ACCEPTED", "gesture=${intent.gestureId}")))
        }

        // 6.2：非空闲状态的额外边缘 Turn → 丢弃整次触摸序列，不排队。
        if (current.state !is State.Idle) {
            return Transition(
                current,
                listOf(Effect.Dropped("BUSY_DROPPED_TURN", stateName(current.state)))
            )
        }

        val anchor = current.committedLocator
            ?: return Transition(current, listOf(Effect.Dropped("NO_COMMITTED_LOCATOR", "尚未完成首次定位")))

        // 6.4：第一章开头后退保持原位并一次边界提示；主线末尾前进同理。
        if (intent.direction == TurnDirection.BACKWARD && !canGoBackward(anchor)) {
            return Transition(current, listOf(Effect.BoundaryFeedback(atStart = true, atEnd = false)))
        }

        val requestId = nextRequestId()
        val generation = current.navigationGeneration + 1
        val state = ReaderNavigationState(
            state = State.Resolving(requestId, NavigationOrigin.GESTURE, anchor),
            navigationGeneration = generation,
            layoutGeneration = current.layoutGeneration,
            committedLocator = current.committedLocator,
            lastMainLocator = current.lastMainLocator ?: current.committedLocator,
            layoutKey = current.layoutKey,
            atMainEnd = current.atMainEnd,
            history = current.history,
            settledTurns = current.settledTurns
        )
        current = state
        return Transition(
            state,
            listOf(
                // 一次手势只产生一个加载请求：方向由宿主按 section/pages 解释（6.4）。
                Effect.LoadTarget(requestId, NavigationOrigin.GESTURE, anchor, generation)
            )
        )
    }

    private fun dispatchGoTo(intent: Intent.GoTo): Transition {
        // 显式新目标：取消旧请求、generation++、只保留最新（6.2）。
        val effects = mutableListOf<Effect>()
        inFlightRequestId()?.let { effects += Effect.CancelInFlight(it, "SUPERSEDED_BY_NEW_TARGET") }
        val generation = current.navigationGeneration + 1
        val requestId = nextRequestId()
        val state = current.copy(
            state = State.Resolving(requestId, intent.origin, intent.target),
            navigationGeneration = generation,
            // 6.5 第 6 条：REFLOW/RESTORE 结束后**不能马上**用新页首覆盖语义 locator；
            // 跳转目标本身是用户意图，所以这里也不改 committedLocator，等提交阶段统一写。
            lastMainLocator = current.lastMainLocator ?: current.committedLocator
        )
        current = state
        effects += Effect.LoadTarget(requestId, intent.origin, intent.target, generation)
        return Transition(state, effects)
    }

    private fun dispatchRestore(intent: Intent.Restore): Transition {
        inFlightRequestId()?.let { /* 恢复期间的新目标直接覆盖 */ }
        val generation = current.navigationGeneration + 1
        val requestId = nextRequestId()
        val state = current.copy(
            state = State.Resolving(requestId, NavigationOrigin.RESTORE, intent.target),
            navigationGeneration = generation,
            atMainEnd = false
        )
        current = state
        return Transition(
            state,
            listOf(
                Effect.LoadTarget(requestId, NavigationOrigin.RESTORE, intent.target, generation)
            )
        )
    }

    private fun dispatchReflow(intent: Intent.Reflow): Transition {
        // 6.2：设置/视口改变可以发生在 Idle 或布局中；合并同一帧变化，保留源锚点。
        val anchor = intent.anchor ?: current.committedLocator
        val layoutGeneration = current.layoutGeneration + 1
        val requestId = nextRequestId()
        val state = current.copy(
            state = if (anchor == null) State.Idle else State.Restoring(requestId, NavigationOrigin.REFLOW, anchor),
            layoutGeneration = layoutGeneration
        )
        current = state
        if (anchor == null) {
            return Transition(state, listOf(Effect.Dropped("REFLOW_WITHOUT_ANCHOR", "没有可保留的源锚点")))
        }
        return Transition(
            state,
            listOf(Effect.LayOut(requestId, anchor, state.navigationGeneration, layoutGeneration))
        )
    }

    private fun dispatchOpenAuxiliary(intent: Intent.OpenAuxiliary): Transition {
        val main = current.lastMainLocator ?: current.committedLocator
        // 8.3 第 2 条：连续跳同一个目标不生成无限重复条目。
        //
        // 判据必须是"**离开的原处**是不是已经在栈顶"，不能用"当前主线定位是否等于原处" ——
        // 浏览辅助内容时主线定位本来就不动，用后者会把每一次脚注跳转都误判成重复，
        // 栈里永远只有一条，返回链直接断掉（实测：60 次跳转后 history.size == 1）。
        val leaveFrom = current.contentLocator ?: main
        val history = if (leaveFrom == null) {
            current.history
        } else {
            pushHistory(current.history, leaveFrom)
        }
        val generation = current.navigationGeneration + 1
        val requestId = nextRequestId()
        val state = current.copy(
            state = State.Resolving(requestId, NavigationOrigin.LINK, intent.target),
            navigationGeneration = generation,
            // 主线定位保持不动（10.2：查看脚注时用 lastMainLocator 计算主进度）。
            lastMainLocator = main,
            contentLocator = intent.target,
            history = history
        )
        current = state
        return Transition(
            state,
            listOf(Effect.LoadTarget(requestId, NavigationOrigin.LINK, intent.target, generation))
        )
    }

    private fun dispatchReturnFromLink(intent: Intent.ReturnFromLink): Transition {
        val previous = current.history.lastOrNull()
            ?: return Transition(current, listOf(Effect.Dropped("HISTORY_EMPTY", "没有可返回的内链位置")))
        val history = current.history.dropLast(1)
        val generation = current.navigationGeneration + 1
        val requestId = nextRequestId()
        val state = current.copy(
            state = State.Resolving(requestId, NavigationOrigin.LINK, previous),
            navigationGeneration = generation,
            history = history
        )
        current = state
        return Transition(
            state,
            listOf(Effect.LoadTarget(requestId, NavigationOrigin.LINK, previous, generation))
        )
    }

    private fun dispatchDetach(intent: Intent.Detach): Transition {
        val effects = mutableListOf<Effect>()
        inFlightRequestId()?.let { effects += Effect.CancelInFlight(it, "DETACHED:${intent.reason}") }
        // 只保证最后**已提交**的位置：不把在途目标当成已读（6.7）。
        current.committedLocator?.let { locator ->
            effects += Effect.Commit(locator, NavigationOrigin.RESTORE, current.atMainEnd)
        }
        val state = current.copy(
            state = State.Idle,
            navigationGeneration = current.navigationGeneration + 1
        )
        current = state
        return Transition(state, effects)
    }

    // ------------------------------------------------------------------ 反馈

    /**
     * 布局反馈（6.1：反馈不是意图）。
     *
     * 只有 token 与当前代次完全一致的快照才能推进状态或提交位置；其余一律
     * [Effect.IgnoredStale]（INV08）。
     */
    fun onViewport(snapshot: ViewportSnapshot): Transition {
        if (snapshot.navigationGeneration != current.navigationGeneration) {
            return Transition(current, listOf(Effect.IgnoredStale("STALE_NAVIGATION_GENERATION")))
        }
        if (snapshot.layoutGeneration != current.layoutGeneration) {
            return Transition(current, listOf(Effect.IgnoredStale("STALE_LAYOUT_GENERATION")))
        }
        if (current.state is State.Committing) {
            // 已经交出去了，等写入器确认；此时再来的快照不重复提交。
            return Transition(current, listOf(Effect.IgnoredStale("COMMIT_ALREADY_ISSUED")))
        }

        // 未稳定（自动分页过渡中）：不登记曝光、不提交位置（10.3、6.5 第 5 条）。
        if (!snapshot.settled) {
            return Transition(current, listOf(Effect.IgnoredStale("LAYOUT_NOT_SETTLED")))
        }

        val origin = when (val state = current.state) {
            is State.Resolving -> state.origin
            is State.Loading -> state.origin
            is State.LayingOut -> state.origin
            is State.Restoring -> state.origin
            else -> return Transition(current, listOf(Effect.IgnoredStale(stateName(current.state))))
        }

        // 首个真实可见 locator 才是提交对象；没有它说明这一屏没有正文。
        val firstVisible = snapshot.firstVisible
            ?: return Transition(current, listOf(Effect.IgnoredStale("NO_VISIBLE_CONTENT")))

        // 6.5 第 6 条：REFLOW/RESTORE 保留原语义 locator，不用新页首覆盖它。
        val commitTarget = if (origin == NavigationOrigin.REFLOW || origin == NavigationOrigin.RESTORE) {
            requestedTargetOf(current.state) ?: firstVisible
        } else {
            firstVisible
        }

        val state = current.copy(
            state = State.Committing(nextRequestId(), origin, commitTarget),
            layoutKey = snapshot.layoutKey,
            atMainEnd = snapshot.atMainEnd,
            // 屏幕上真正显示的是 commitTarget（可能是辅助内容）；主线定位另行维护。
            contentLocator = commitTarget,
            lastMainLocator = current.lastMainLocator ?: commitTarget,
            settledTurns = current.settledTurns + 1
        )
        current = state
        return Transition(
            state,
            listOf(Effect.Commit(commitTarget, origin, snapshot.atMainEnd))
        )
    }

    /** 有序写入器确认落库完成 → 回到 Idle（6.7）。 */
    fun onCommitted(): Transition {
        val state = current.state
        if (state !is State.Committing) {
            return Transition(current, listOf(Effect.IgnoredStale("NOT_COMMITTING")))
        }
        val next = current.copy(
            state = State.Idle,
            committedLocator = state.target,
            contentLocator = state.target
        )
        current = next
        return Transition(next, emptyList())
    }

    /** 内容加载完成，进入布局。 */
    fun onContentLoaded(requestId: Long): Transition {
        val state = current.state
        if (state !is State.Loading || state.requestId != requestId) {
            return Transition(current, listOf(Effect.IgnoredStale("CONTENT_LOADED_STALE")))
        }
        val next = current.copy(state = State.LayingOut(state.requestId, state.origin, state.target))
        current = next
        return Transition(
            next,
            listOf(Effect.LayOut(state.requestId, state.target, current.navigationGeneration, current.layoutGeneration))
        )
    }

    /** 目标解析完成，开始加载。 */
    fun onTargetResolved(requestId: Long, revision: String): Transition {
        val state = current.state
        if (state !is State.Resolving || state.requestId != requestId) {
            return Transition(current, listOf(Effect.IgnoredStale("TARGET_RESOLVED_STALE")))
        }
        val next = current.copy(
            state = State.Loading(state.requestId, state.origin, state.requestedTarget.copy(revision = revision))
        )
        current = next
        return Transition(next, emptyList())
    }

    /** 加载/布局失败：保留前一章与定位（6.2）。 */
    fun onFailure(requestId: Long, reasonCode: String, detail: String? = null): Transition {
        if (inFlightRequestId() != requestId) {
            return Transition(current, listOf(Effect.IgnoredStale("FAILURE_STALE")))
        }
        val next = current.copy(state = State.ErrorWithPreviousContent(reasonCode, detail))
        current = next
        return Transition(next, emptyList())
    }

    /** 从错误态恢复（用户在错误提示上确认）。 */
    fun clearError(): Transition {
        if (current.state !is State.ErrorWithPreviousContent) {
            return Transition(current, listOf(Effect.IgnoredStale("NOT_IN_ERROR")))
        }
        val next = current.copy(state = State.Idle)
        current = next
        return Transition(next, emptyList())
    }

    // ------------------------------------------------------------------ 内部

    private var requestCounter = 0L

    private fun nextRequestId(): Long = ++requestCounter

    private fun inFlightRequestId(): Long? = when (val state = current.state) {
        is State.Resolving -> state.requestId
        is State.Loading -> state.requestId
        is State.LayingOut -> state.requestId
        is State.Restoring -> state.requestId
        is State.Committing -> state.requestId
        else -> null
    }

    private fun requestedTargetOf(state: State): TextLocator? = when (state) {
        is State.Resolving -> state.requestedTarget
        is State.Loading -> state.target
        is State.LayingOut -> state.target
        is State.Restoring -> state.target
        is State.Committing -> state.target
        else -> null
    }

    private fun pushHistory(history: List<TextLocator>, locator: TextLocator): List<TextLocator> {
        // 8.3 第 2 条：连续跳同一个目标不生成无限重复条目；容量 50，超出移除最旧项。
        if (history.lastOrNull() == locator) return history
        val appended = history + locator
        return if (appended.size <= historyCapacity) appended else appended.takeLast(historyCapacity)
    }

    /**
     * 6.4：是否还能后退。
     *
     * 这里只做**状态机层面**的判断：没有已提交定位时不能后退；真正的"第一章开头"由宿主
     * 在 section/页边界上判定并通过 [Intent.Turn] 的边界反馈返回。
     */
    private fun canGoBackward(anchor: TextLocator): Boolean = anchor.offsetUtf16 > 0 || anchor.sectionId != null

    private fun stateName(state: State): String = state::class.simpleName ?: "State"
}
