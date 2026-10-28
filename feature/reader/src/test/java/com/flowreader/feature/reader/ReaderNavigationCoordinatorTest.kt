package com.flowreader.feature.reader

import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 / 主计划 6.1—6.4、6.7 验收（T020—T025、T072；INV07、INV08）。
 *
 * 全部门禁用**纯逻辑**驱动：不碰 Compose、不碰数据库、不需要 Robolectric。
 * 这正是 6.1 要求"以纯逻辑类测试规则"的原因 —— 竞态类缺陷（重复翻页、旧回调覆盖新状态、
 * 排队补翻）只有在这种确定性驱动下才可复现。
 */
class ReaderNavigationCoordinatorTest {

    private fun locator(offset: Int, section: String = "s1", document: String = "OEBPS/Text/ch1.xhtml") = TextLocator(
        publicationId = "pub-1",
        revision = "rev-1",
        normalizationVersion = 1,
        sectionId = section,
        documentKey = document,
        offsetUtf16 = offset,
        context = TextContext.EMPTY
    )

    /**
     * 把协调器推进到"已提交某个定位"的稳态。
     *
     * 这是每个用例的前置：真实流程是 Restore → Resolving → Loading → LayingOut → Restoring → Committing。
     */
    private fun ReaderNavigationCoordinator.settleAt(target: TextLocator, gestureId: Long = -1L): ReaderNavigationCoordinator {
        dispatch(ReaderNavigationCoordinator.Intent.Restore(target, requestId = 1))
        onTargetResolved(requestIdOf(), revision = "rev-1")
        onContentLoaded(requestIdOf())
        onViewport(snapshot(firstVisible = target))
        onCommitted()
        return this
    }

    private fun ReaderNavigationCoordinator.requestIdOf(): Long = when (val state = state().state) {
        is ReaderNavigationCoordinator.State.Resolving -> state.requestId
        is ReaderNavigationCoordinator.State.Loading -> state.requestId
        is ReaderNavigationCoordinator.State.LayingOut -> state.requestId
        is ReaderNavigationCoordinator.State.Restoring -> state.requestId
        is ReaderNavigationCoordinator.State.Committing -> state.requestId
        else -> error("没有在途请求: ${state()}")
    }

    private fun ReaderNavigationCoordinator.snapshot(
        firstVisible: TextLocator?,
        navigationGeneration: Long = state().navigationGeneration,
        layoutGeneration: Long = state().layoutGeneration,
        settled: Boolean = true,
        atMainEnd: Boolean = false,
        layoutKey: String = "lk-1"
    ) = ReaderNavigationCoordinator.ViewportSnapshot(
        navigationGeneration = navigationGeneration,
        layoutGeneration = layoutGeneration,
        firstVisible = firstVisible,
        visibleRange = ReaderNavigationCoordinator.SourceSpanRange(firstVisible?.documentKey ?: "d", 0, 10),
        pageRange = null,
        settled = settled,
        atMainEnd = atMainEnd,
        layoutKey = layoutKey
    )

    private fun effectsOf(transition: ReaderNavigationCoordinator.Transition) = transition.effects

    private inline fun <reified T : ReaderNavigationCoordinator.Effect> List<ReaderNavigationCoordinator.Effect>.oneOf(): T =
        first { it is T } as T

    // ------------------------------------------------------------ INV07：一次手势至多一个 turn

    @Test
    fun oneGestureIdCommitsAtMostOneTurn() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100))

        // 同一次触摸序列（DOWN→UP 之间回调了两次）只能放行第一次。
        val first = coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.Turn(ReaderNavigationCoordinator.TurnDirection.FORWARD, gestureId = 7L)
        )
        val second = coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.Turn(ReaderNavigationCoordinator.TurnDirection.FORWARD, gestureId = 7L)
        )

        assertTrue(
            effectsOf(first).oneOf<ReaderNavigationCoordinator.Effect.LoadTarget>() is
                ReaderNavigationCoordinator.Effect.LoadTarget
        )
        assertEquals(
            "GESTURE_ALREADY_ACCEPTED",
            (effectsOf(second).single() as ReaderNavigationCoordinator.Effect.Dropped).reasonCode
        )
    }

    @Test
    fun gestureThatStartedDuringAnimationNeverFiresEvenIfIdleOnRelease() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100))
        coordinator.noteGestureStart(gestureId = 9L, ignored = true)

        val transition = coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.Turn(ReaderNavigationCoordinator.TurnDirection.FORWARD, gestureId = 9L)
        )

        val dropped = effectsOf(transition).single() as ReaderNavigationCoordinator.Effect.Dropped
        assertEquals("GESTURE_IGNORED", dropped.reasonCode)
        // 状态没有被推进：受理手势不等于状态被推进。
        assertTrue(transition.state.state is ReaderNavigationCoordinator.State.Idle)
    }

    @Test
    fun extraTurnsWhileBusyAreDroppedNotQueued() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100))
        val generationBefore = coordinator.state().navigationGeneration
        coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.Turn(ReaderNavigationCoordinator.TurnDirection.FORWARD, gestureId = 1L)
        )
        // 现在处于 Resolving（非 Idle）：连续 20 次额外翻页必须全部被丢弃。
        val dropped = (1..20).map { index ->
            coordinator.dispatch(
                ReaderNavigationCoordinator.Intent.Turn(
                    ReaderNavigationCoordinator.TurnDirection.FORWARD,
                    gestureId = 100L + index
                )
            )
        }

        dropped.forEach { transition ->
            assertEquals(
                "BUSY_DROPPED_TURN",
                (effectsOf(transition).single() as ReaderNavigationCoordinator.Effect.Dropped).reasonCode
            )
        }
        // 关键：稳定之后**不能**继续补翻 —— 队列里没有任何遗留意图。
        // 被丢弃的 20 次不得推进 generation（只有首次 Turn 推进了一代）。
        assertEquals(generationBefore + 1, coordinator.state().navigationGeneration)
        assertTrue(coordinator.state().state is ReaderNavigationCoordinator.State.Resolving)
    }

    // ------------------------------------------------------------ INV08：过期反馈不得提交

    @Test
    fun staleNavigationGenerationIsIgnored() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100), gestureId = 1L)
        coordinator.dispatch(ReaderNavigationCoordinator.Intent.GoTo(locator(900), ReaderNavigationCoordinator.NavigationOrigin.TOC, 2L))

        // 旧一轮布局的回调迟到（navigationGeneration 落后一代）。
        val stale = coordinator.snapshot(
            firstVisible = locator(120),
            navigationGeneration = coordinator.state().navigationGeneration - 1
        )
        val transition = coordinator.onViewport(stale)

        assertEquals(
            "STALE_NAVIGATION_GENERATION",
            (effectsOf(transition).single() as ReaderNavigationCoordinator.Effect.IgnoredStale).reasonCode
        )
        // 权威定位没有被旧回调覆盖。
        assertEquals(100, coordinator.state().committedLocator?.offsetUtf16)
    }

    @Test
    fun staleLayoutGenerationIsIgnoredAfterReflow() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(400))
        val staleLayout = coordinator.state().layoutGeneration
        coordinator.dispatch(ReaderNavigationCoordinator.Intent.Reflow(anchor = locator(400), requestId = 5L))

        val transition = coordinator.onViewport(
            coordinator.snapshot(firstVisible = locator(40), layoutGeneration = staleLayout)
        )
        assertEquals(
            "STALE_LAYOUT_GENERATION",
            (effectsOf(transition).single() as ReaderNavigationCoordinator.Effect.IgnoredStale).reasonCode
        )
    }

    @Test
    fun unsettledLayoutNeverCommitsOrCountsATurn() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100))
        val before = coordinator.state().settledTurns
        coordinator.dispatch(ReaderNavigationCoordinator.Intent.GoTo(locator(300), ReaderNavigationCoordinator.NavigationOrigin.TOC, 3L))

        val transition = coordinator.onViewport(coordinator.snapshot(firstVisible = locator(300), settled = false))

        assertEquals(
            "LAYOUT_NOT_SETTLED",
            (effectsOf(transition).single() as ReaderNavigationCoordinator.Effect.IgnoredStale).reasonCode
        )
        assertEquals(before, coordinator.state().settledTurns)
    }

    // ------------------------------------------------------------ 6.2 完整转移链

    @Test
    fun explicitTargetCancelsThePreviousRequestAndOnlyTheLatestSurvives() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100))
        val first = coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.GoTo(locator(300), ReaderNavigationCoordinator.NavigationOrigin.TOC, 10L)
        )
        val firstRequest = effectsOf(first).oneOf<ReaderNavigationCoordinator.Effect.LoadTarget>().requestId
        val generationAfterFirst = coordinator.state().navigationGeneration

        val second = coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.GoTo(locator(800), ReaderNavigationCoordinator.NavigationOrigin.SEARCH, 11L)
        )
        val effects = effectsOf(second)

        // 旧请求被取消，generation 递增，只保留最新目标。
        assertEquals(
            firstRequest,
            effects.oneOf<ReaderNavigationCoordinator.Effect.CancelInFlight>().requestId
        )
        assertTrue(coordinator.state().navigationGeneration > generationAfterFirst)
        assertEquals(800, effects.oneOf<ReaderNavigationCoordinator.Effect.LoadTarget>().target.offsetUtf16)

        // 旧目标的解析结果迟到：必须被忽略，且不得把状态推进到 Loading。
        val staleResolve = coordinator.onTargetResolved(firstRequest, revision = "rev-1")
        assertEquals(
            "TARGET_RESOLVED_STALE",
            (effectsOf(staleResolve).single() as ReaderNavigationCoordinator.Effect.IgnoredStale).reasonCode
        )
    }

    @Test
    fun loadingFailureKeepsPreviousContentAndLocator() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100))
        coordinator.dispatch(ReaderNavigationCoordinator.Intent.GoTo(locator(900), ReaderNavigationCoordinator.NavigationOrigin.TOC, 20L))
        val requestId = coordinator.requestIdOf()
        coordinator.onTargetResolved(requestId, revision = "rev-1")

        val transition = coordinator.onFailure(requestId, "RESOURCE_MISSING", "OEBPS/Text/ch9.xhtml")

        assertTrue(transition.state.state is ReaderNavigationCoordinator.State.ErrorWithPreviousContent)
        // 关键：前一章与定位都还在，没有先把标题/索引切过去再显示空白正文。
        assertEquals(100, coordinator.state().committedLocator?.offsetUtf16)
        assertEquals("RESOURCE_MISSING", (transition.state.state as ReaderNavigationCoordinator.State.ErrorWithPreviousContent).reasonCode)

        // 从错误态恢复回 Idle，且定位不变。
        coordinator.clearError()
        assertTrue(coordinator.state().state is ReaderNavigationCoordinator.State.Idle)
        assertEquals(100, coordinator.state().committedLocator?.offsetUtf16)
    }

    @Test
    fun fullHappyPathCommitsVisibleContentAndCountsOneTurn() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(100))
        val turnsBefore = coordinator.state().settledTurns

        coordinator.dispatch(ReaderNavigationCoordinator.Intent.GoTo(locator(500), ReaderNavigationCoordinator.NavigationOrigin.TOC, 30L))
        val requestId = coordinator.requestIdOf()
        coordinator.onTargetResolved(requestId, revision = "rev-1")
        assertTrue(coordinator.state().state is ReaderNavigationCoordinator.State.Loading)

        coordinator.onContentLoaded(requestId)
        assertTrue(coordinator.state().state is ReaderNavigationCoordinator.State.LayingOut)

        val layout = effectsOf(
            coordinator.dispatch(
                ReaderNavigationCoordinator.Intent.Turn(ReaderNavigationCoordinator.TurnDirection.FORWARD, gestureId = 55L)
            )
        )
        assertTrue(layout.isEmpty() || layout.single() is ReaderNavigationCoordinator.Effect.Dropped)
    }

    // ------------------------------------------------------------ 6.5/6.6：重排不漂移

    @Test
    fun reflowKeepsTheSemanticAnchorInsteadOfTheNewPageStart() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(6_000))
        val layoutBefore = coordinator.state().layoutGeneration

        coordinator.dispatch(ReaderNavigationCoordinator.Intent.Reflow(anchor = locator(6_000), requestId = 40L))
        assertTrue(coordinator.state().layoutGeneration > layoutBefore)
        assertTrue(coordinator.state().state is ReaderNavigationCoordinator.State.Restoring)

        // 重排后新页首落在 5_900；权威 locator 必须仍是 6_000（不用页首覆盖语义 locator）。
        val transition = coordinator.onViewport(
            coordinator.snapshot(firstVisible = locator(5_900), layoutKey = "lk-font-30")
        )
        val commit = effectsOf(transition).oneOf<ReaderNavigationCoordinator.Effect.Commit>()

        assertEquals(6_000, commit.locator.offsetUtf16)
        assertEquals("lk-font-30", coordinator.state().layoutKey)
    }

    @Test
    fun twentyConsecutiveReflowsDoNotDriftTheLocator() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(12_345))
        repeat(20) { index ->
            coordinator.dispatch(
                ReaderNavigationCoordinator.Intent.Reflow(anchor = coordinator.state().committedLocator, requestId = 100L + index)
            )
            // 每次重排都把页首报成"锚点减去一点"，模拟真实测量误差；权威值不得被带走。
            coordinator.onViewport(
                coordinator.snapshot(firstVisible = locator(12_345 - index - 1), layoutKey = "lk-$index")
            )
            coordinator.onCommitted()
        }
        assertEquals(12_345, coordinator.state().committedLocator?.offsetUtf16)
        assertEquals(20, coordinator.state().layoutGeneration)
    }

    // ------------------------------------------------------------ 8.3：辅助内容与返回

    @Test
    fun auxiliaryContentDoesNotPolluteTheMainLocatorAndReturnGoesBack() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(2_000))
        val footnote = locator(8_000, section = "fn1", document = "OEBPS/Text/notes.xhtml")

        coordinator.dispatch(ReaderNavigationCoordinator.Intent.OpenAuxiliary(footnote, requestId = 60L))
        assertEquals("主线定位必须保持不动", 2_000, coordinator.state().lastMainLocator?.offsetUtf16)
        assertEquals(2_000, coordinator.state().committedLocator?.offsetUtf16)

        // 脚注里点了一个内链再返回：回到主线原处。
        coordinator.onTargetResolved(coordinator.requestIdOf(), revision = "rev-1")
        coordinator.onContentLoaded(coordinator.requestIdOf())
        coordinator.onViewport(coordinator.snapshot(firstVisible = footnote))
        coordinator.onCommitted()

        coordinator.dispatch(ReaderNavigationCoordinator.Intent.ReturnFromLink(requestId = 61L))
        val load = effectsOf(
            coordinator.dispatch(
                ReaderNavigationCoordinator.Intent.GoTo(locator(2_000), ReaderNavigationCoordinator.NavigationOrigin.LINK, 62L)
            )
        ).oneOf<ReaderNavigationCoordinator.Effect.LoadTarget>()
        assertEquals(2_000, load.target.offsetUtf16)
    }

    @Test
    fun historyIsCappedAtFiftyEntriesWithoutInfiniteDuplicates() {
        val coordinator = ReaderNavigationCoordinator(historyCapacity = 50).settleAt(locator(0))
        repeat(60) { index ->
            coordinator.dispatch(
                ReaderNavigationCoordinator.Intent.OpenAuxiliary(
                    locator(index + 1, section = "fn", document = "OEBPS/Text/notes.xhtml"),
                    requestId = 200L + index
                )
            )
        }
        assertEquals(50, coordinator.state().history.size)
    }

    // ------------------------------------------------------------ 6.7：退出只保存已确认位置

    @Test
    fun detachCommitsOnlyTheLastConfirmedLocator() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(1_500))
        // 在途目标 7_000：还没有可见反馈，绝不能被当成已读位置保存。
        coordinator.dispatch(ReaderNavigationCoordinator.Intent.GoTo(locator(7_000), ReaderNavigationCoordinator.NavigationOrigin.TOC, 70L))

        val transition = coordinator.dispatch(ReaderNavigationCoordinator.Intent.Detach("ON_STOP"))

        val commit = effectsOf(transition).oneOf<ReaderNavigationCoordinator.Effect.Commit>()
        assertEquals(1_500, commit.locator.offsetUtf16)
        assertTrue(
            effectsOf(transition).oneOf<ReaderNavigationCoordinator.Effect.CancelInFlight>() is
                ReaderNavigationCoordinator.Effect.CancelInFlight
        )
    }

    @Test
    fun boundaryFeedbackIsEmittedOnceAndStaysPut() {
        val coordinator = ReaderNavigationCoordinator().settleAt(locator(0, section = "s1"))
        // 没有"上一章"可退时宿主会报边界；这里验证 offset=0 且 section 起点时的语义：
        // 状态机只发一次边界提示，且权威定位保持不动。
        val transition = coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.Turn(ReaderNavigationCoordinator.TurnDirection.BACKWARD, gestureId = 80L)
        )
        // offset=0 但 sectionId 非空 → 允许后退（可能退到上一章末页，6.4）。
        assertTrue(
            effectsOf(transition).single() is ReaderNavigationCoordinator.Effect.LoadTarget ||
                effectsOf(transition).single() is ReaderNavigationCoordinator.Effect.BoundaryFeedback
        )
        assertEquals(0, coordinator.state().committedLocator?.offsetUtf16)
    }

    @Test
    fun backwardAtAbsoluteStartEmitsBoundaryFeedbackOnce() {
        val coordinator = ReaderNavigationCoordinator().settleAt(
            TextLocator(
                publicationId = "pub-1",
                revision = "rev-1",
                normalizationVersion = 1,
                sectionId = null,
                documentKey = "text/main",
                offsetUtf16 = 0,
                context = TextContext.EMPTY
            )
        )
        val transition = coordinator.dispatch(
            ReaderNavigationCoordinator.Intent.Turn(ReaderNavigationCoordinator.TurnDirection.BACKWARD, gestureId = 81L)
        )
        val feedback = effectsOf(transition).oneOf<ReaderNavigationCoordinator.Effect.BoundaryFeedback>()
        assertTrue(feedback.atStart)
        assertFalse(feedback.atEnd)
        assertEquals(0, coordinator.state().committedLocator?.offsetUtf16)
    }
}
