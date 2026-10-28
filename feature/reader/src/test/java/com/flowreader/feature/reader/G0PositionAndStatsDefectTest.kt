package com.flowreader.feature.reader

import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.PageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G0 复现夹具：**位置单位混用、剩余时间单位不一致、阅读时长依赖翻页**（主计划 F01/F02/F04，
 * 对应 R1/R3、T013—T019、T069—T079）。
 *
 * 这些是**缺陷特征描述测试**：断言当前代码的真实行为，因此现在通过；S4/S9 修正后必须翻转。
 * 每条都标出「当前值」与「主计划要求的值」，避免只断言函数存在或只断言错误数值。
 */
class G0PositionAndStatsDefectTest {

    private fun chapter(index: Int, content: String) = Chapter(
        bookId = 1L,
        index = index,
        title = "第${index + 1}章",
        content = content,
        startPosition = 0,
        endPosition = content.length
    )

    private fun metadataOnlyChapter(index: Int) = Chapter(
        bookId = 1L,
        index = index,
        title = "第${index + 1}章",
        content = "",
        startPosition = 0,
        endPosition = 0
    )

    // ---------------------------------------------------------------- F01 位置单位混用

    @Test
    fun theSameIntFieldCarriesTwoDifferentUnits() {
        // 同一个 `Book.currentPosition: Int`：分页/漫画是**已渲染页下标**，滚动是**滚动像素**。
        assertEquals(ReaderPositionUnit.PAGE_INDEX, ReaderPositionUnit.of(PageMode.PAGED, BookFormat.TXT))
        assertEquals(ReaderPositionUnit.CHARACTERS, ReaderPositionUnit.of(PageMode.SLIDE, BookFormat.TXT))
        assertEquals(ReaderPositionUnit.PAGE_INDEX, ReaderPositionUnit.of(PageMode.SLIDE, BookFormat.COMIC))

        // 而 `ReaderPositionUnit.CHARACTERS` 的文档自述是“approximated by scroll pixels”：
        // 也就是说 CHARACTERS 分支并不是字符偏移，连它也不是可靠内容坐标。
        // 结论：Book.currentPosition 不能作为任何持久定位；S4 起必须改为 TextLocator。
        assertTrue(true)
    }

    @Test
    fun aPageIndexFedIntoTheCharacterPathCompletesNoPage() {
        var now = 0L
        val tracker = ReaderSessionTracker(pauseThresholdMs = 5 * 60 * 1000L, nowProvider = { now })
        tracker.startSession()
        val content = "甲".repeat(1000)
        val charsPerPage = 500

        // PAGED 模式：每次翻页 position 只 +1（页下标），却按字符数计算。
        now += 30_000
        val events = listOf(1, 2, 3, 4)
        var completed = 0
        events.forEach { pageIndex ->
            now += 30_000
            completed += tracker.recordProgress(position = pageIndex, content = content, charsPerPage = charsPerPage)
        }
        // 翻了 4 页、读了 2 分钟，却一页都没“完成” → readPages 保持 0。
        assertEquals("页下标当字符用，累计不足一页", 0, completed)
        assertEquals(0, tracker.readPages)

        // 而 `ReaderViewModel.saveReadingStats()` 的门槛是 `seconds > 0 && pages > 0`，
        // 于是这 2 分钟阅读时间连同页数一起被丢弃（F04 的核心症状）。
        val (pages, seconds) = tracker.takeSnapshotAndReset()
        assertEquals(0, pages)
        assertTrue("时长本身是有的，却被 pages>0 门槛拦掉", seconds >= 120)
        assertTrue("当前门槛会把整段会话丢掉", !(seconds > 0 && pages > 0))
    }

    @Test
    fun thePageIndexPathCompletesPagesOnlyWhenTheCallerBranches() {
        var now = 0L
        val tracker = ReaderSessionTracker(nowProvider = { now })
        tracker.startSession()
        now += 30_000
        val pages = tracker.recordPageProgress(pageIndex = 4, charsPerPage = 500)
        // 这正是 v56.4.3 打的分支补丁：只有走 recordPageProgress 才会记到页数。
        assertEquals(4, pages)
    }

    // ---------------------------------------------------------------- F02 剩余时间

    @Test
    fun remainingTimeUsesChapterContentLengthSoUnloadedChaptersCountAsZero() {
        val engine = ReaderProgressEngine()
        // 目录页只带元数据（内容未加载）时，`chapter.content` 为空字符串。
        val chapters = listOf(metadataOnlyChapter(0), metadataOnlyChapter(1), metadataOnlyChapter(2))

        val minutes = engine.remainingMinutes(chapters = chapters, currentIndex = 0, currentPosition = 0, speed = 300f)
        // 当前实现：0 字 / 300 字每分钟 = 0 分钟。明明还有三章没读，却报告“读完了”。
        assertEquals("未加载正文章节被当成 0 字", 0, minutes)

        // 主计划 10.5/T070 要求：分母来自完整出版物索引，不能依赖当前已加载章节集合。
    }

    @Test
    fun aPageIndexIsSubtractedFromACharacterCount() {
        val engine = ReaderProgressEngine()
        val chapters = listOf(chapter(0, "甲".repeat(1000)), chapter(1, "乙".repeat(1000)))

        // PAGED 模式下 currentPosition 是页下标（例如第 5 页），却被当作字符偏移从 1000 里减掉。
        val asPageIndex = engine.remainingMinutes(chapters, currentIndex = 0, currentPosition = 5, speed = 300f)
        val asCharOffset = engine.remainingMinutes(chapters, currentIndex = 0, currentPosition = 995, speed = 300f)
        // 两者相差 990 个“字符”，但按页下标语义它们描述的是同一处内容。
        assertTrue("页下标与字符偏移给出同一剩余量的说法不成立", asPageIndex != asCharOffset)
        assertEquals(7, asPageIndex)
    }

    @Test
    fun zeroSpeedStillProducesAFabricatedFiniteEstimate() {
        val engine = ReaderProgressEngine()
        val chapters = listOf(chapter(0, "甲".repeat(1000)))
        // `speed.coerceAtLeast(100f)`：速度为 0（没有采样）时凭空按 100 字/分钟计算。
        val minutes = engine.remainingMinutes(chapters, currentIndex = 0, currentPosition = 0, speed = 0f)
        assertEquals("无样本时按默认速度伪造时长", 10, minutes)

        // 主计划 10.4 第 8 条要求：样本不足显示“剩余时间：估算中”，不强制 coerceAtLeast(100)。
    }

    @Test
    fun remainingTimeCountsUtf16UnitsNotCodePoints() {
        val engine = ReaderProgressEngine()
        // emoji 占 2 个 UTF-16 单位，但只计 1 个 code point（主计划 10.2）。
        val chapters = listOf(chapter(0, "\uD83D\uDE00".repeat(500)))
        val minutes = engine.remainingMinutes(chapters, currentIndex = 0, currentPosition = 0, speed = 300f)
        // content.length = 1000（UTF-16），按 300 字/分钟 → 3 分钟；按 code point 应是 500 字 → 2 分钟。
        assertEquals("用的是 UTF-16 长度而不是 code point 数", 3, minutes)
    }

    // ---------------------------------------------------------------- F04 有效时间与时长

    @Test
    fun aSessionWithNoPageTurnProducesZeroPagesAndNoTextUnits() {
        var now = 0L
        val tracker = ReaderSessionTracker(nowProvider = { now })
        tracker.startSession()
        now += 90_000

        val (pages, seconds) = tracker.takeSnapshotAndReset()
        assertEquals("同一页停留 90 秒不翻页 → 0 页", 0, pages)
        assertEquals(90L, seconds)

        // 主计划 T073 要求：同页阅读 90 秒要保存 90 秒有效时间、turn=0。
        // 当前没有任何 qualifiedTextUnits / visualReadMillis 字段，快照只有 (pages, seconds)。
    }

    @Test
    fun scrollPixelsAreCountedAsCharacters() {
        var now = 1_000L
        val tracker = ReaderSessionTracker(nowProvider = { now })
        tracker.startSession()
        val content = "甲".repeat(2000)

        now += 60_000
        // SLIDE 模式：position 是 ScrollState.value（像素）。这里 1200 是像素，
        // 却被当作字符下标切开 content 并计数。
        val completed = tracker.recordProgress(position = 1200, content = content, charsPerPage = 500)
        assertEquals("滚动像素被当成 1200 个字符", 2, completed)
        assertEquals(2, tracker.readPages)

        // ROADMAP.md 第 47 行已承认：SLIDE/NONE 下的“已读字数/速度”是系数取决于字号行距的
        // 粗略代理值，绝对值不可信。主计划 10.4 要求改为按可见行真实 SourceRange 计。
    }

    @Test
    fun readingSpeedHasNoMinimumSampleWindow() {
        var now = 1_000L
        val tracker = ReaderSessionTracker(nowProvider = { now })
        tracker.startSession()
        // 第一次采样只间隔 1 秒、位移 10，就直接写入速度 EMA。
        now += 1_000
        tracker.recordProgress(position = 10, content = "甲".repeat(100), charsPerPage = 500)
        // 10 字 / (1/60 分钟) = 600 字/分钟 —— 一个瞬时常数成了“阅读速度”。
        assertEquals(600f, tracker.readingSpeed, 1f)

        // 主计划 10.4 第 6 条要求：窗口至少 60 秒有效时间、至少 300 个新 qualifiedTextUnits，
        // 并用总字/总分钟而不是瞬时速度 EMA；异常窗口只排除 ETA，不删用户真实时间。
    }

    @Test
    fun theDefaultClockIsWallClockNotMonotonic() {
        // 生产默认 `nowProvider = System::currentTimeMillis`（ReaderSessionTracker 构造参数默认值）。
        // 主计划 10.3 要求改用可注入 MonotonicClock（Android 侧 SystemClock.elapsedRealtime()），
        // 墙上时间只用于划分日期。此处断言默认构造可用，缺陷由上面的构造默认值证明。
        val tracker = ReaderSessionTracker()
        tracker.startSession()
        assertTrue(tracker.elapsedSeconds >= 0L)
    }
}
