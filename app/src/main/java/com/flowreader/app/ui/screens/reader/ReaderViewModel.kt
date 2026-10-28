package com.flowreader.app.ui.screens.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowreader.app.core.util.ReadingProgress
import com.flowreader.app.data.local.dao.ReaderStateDao
import com.flowreader.app.data.local.entity.ReaderStateEntity
import com.flowreader.app.domain.model.Affinity
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.AnnotationColor
import com.flowreader.app.domain.model.Book
import com.flowreader.app.domain.model.BookFormat
import com.flowreader.app.domain.model.Bookmark
import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.LegacyPositionUnit
import com.flowreader.app.domain.model.RangeLocator
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.SearchHitProjector
import com.flowreader.app.domain.model.SourceRange
import com.flowreader.app.domain.model.TextContext
import com.flowreader.app.domain.model.TextLocator
import com.flowreader.app.domain.model.TextLocatorCodec
import com.flowreader.app.domain.model.TocTree
import com.flowreader.app.domain.repository.AnnotationRepository
import com.flowreader.app.domain.repository.BookRepository
import com.flowreader.app.domain.repository.BookmarkRepository
import com.flowreader.app.domain.repository.ChapterRepository
import com.flowreader.app.domain.repository.ReadingStatsRepository
import com.flowreader.app.domain.repository.SettingsRepository
import com.flowreader.app.domain.repository.TocRepository
import com.flowreader.app.ui.screens.reader.components.MultiBlockSelection
import com.flowreader.app.ui.screens.reader.components.scrollProjectionToLocator
import com.flowreader.app.util.CacheManager
import com.flowreader.app.util.FtsSearchResult
import com.flowreader.app.util.ChapterTextSearch
import com.flowreader.app.domain.repository.SearchProgress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.flowreader.app.util.TtsManager
import com.flowreader.app.util.epub.ChapterImageRef
import com.flowreader.app.util.epub.ChapterImageResolver
import com.flowreader.app.util.epub.ChapterMarks
import com.flowreader.feature.reader.ContentLocatorResolver
import com.flowreader.feature.reader.ContentProgressIndex
import com.flowreader.feature.reader.ReaderPositionUnit
import com.flowreader.feature.reader.ReaderProgressEngine
import com.flowreader.feature.reader.ReaderSessionTracker
import com.flowreader.feature.reader.ReadingSpeedEstimator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    val currentChapter: Chapter? = null,
    val currentChapterIndex: Int = 0,
    val currentPosition: Int = 0,
    val readingSettings: ReadingSettings = ReadingSettings(),
    val bookmarks: List<Bookmark> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    val showControls: Boolean = true,
    val showChapterList: Boolean = false,
    val showSettings: Boolean = false,
    val showBookmarks: Boolean = false,
    val showAnnotations: Boolean = false,
    val showSearch: Boolean = false,
    val nextVolume: Book? = null,
    val showNextVolumePrompt: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<FtsSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val searchProgress: SearchProgress? = null,
    val searchError: String? = null,
    val searchTarget: TextLocator? = null,
    /**
     * True only while the **book itself** is being opened. Chapter-to-chapter navigation
     * deliberately does *not* set this: see [isChapterSwitching].
     */
    val isLoading: Boolean = true,
    /**
     * An in-book chapter transition is in flight.
     *
     * Chapter changes used to flip [isLoading], which made `FlowStateHost` throw the whole reader
     * away and draw a centred spinner; when the new chapter finally appeared the reader was rebuilt
     * from scratch, so the pager had to re-measure the chapter and briefly reported it as empty.
     * Transitions now keep the *previous* chapter on screen until the new content is ready, so
     * turning a chapter reads as a page turn instead of a reload.
     */
    val isChapterSwitching: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val todayReadTime: Long = 0,
    val todayReadPages: Int = 0,
    val shareText: String? = null,
    val estimatedTimeRemaining: Long = 0,
    val readingSpeed: Float = 0f,
    val sessionReadTime: Long = 0,
    val showEyeProtectionReminder: Boolean = false,
    val dailyGoalProgress: Float = 0f,
    val suggestedBreakTime: Long = 0,
    val isTtsPlaying: Boolean = false,
    val speechEnabled: Boolean = false,
    val isImmersiveMode: Boolean = false,
    /**
     * 目录的展开状态（主计划 7.2 第 6 条）：按书隔离，退出阅读器再进入仍保持。
     *
     * 存的是 **nodeId**，不是标题——同名卷/同名章必须能各自独立折叠（F07）。
     */
    val tocExpandedNodeIds: Set<String> = emptySet(),
    /**
     * 真实目录树（来自 toc_nodes，主计划 7.1）。
     *
     * null 表示这本书还没有内容引擎目录（旧书或尚未重解析），
     * UI 必须回退到 LegacyTocAdapter，不得显示空目录。
     */
    val tocTree: TocTree? = null,
    /**
     * 当前章的**结构图片**（S5 / 主计划 4.6）：章内 U+FFFC 偏移 → 本机绝对路径。
     *
     * 在章节状态发布**之前**解析好，使分页器一次就把图片算进去；否则会先按纯文本排版、
     * 拿到图后再重排一次，换章会明显跳一下。空列表表示这一章没有结构图（旧书亦然）。
     */
    val chapterImages: List<ChapterImageRef> = emptyList(),
    /**
     * 当前章的**呈现标注**（schema v3）：列表项（含推导编号）、表格、注音。
     *
     * 与 [chapterImages] 同一条链路、同一个取数时点（发布章节状态**之前**），
     * 使分页一次就把列表前缀等显示改动算进去。旧书 / schema v2 内容包为 [ChapterMarks.EMPTY]。
     */
    val chapterMarks: ChapterMarks = ChapterMarks.EMPTY,
    val scrollRequestVersion: Long = 0L,
    /**
     * Set when the chapter was entered from the *end* (scrolling up past the start of the next
     * chapter). `ReaderScreen` scrolls to the bottom and then clears it — the target is not known
     * until the new chapter has been laid out.
     */
    val landAtChapterEnd: Boolean = false
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val annotationRepository: AnnotationRepository,
    private val settingsRepository: SettingsRepository,
    private val readingStatsRepository: ReadingStatsRepository,
    private val cacheManager: CacheManager,
    private val bookLoader: com.flowreader.app.util.BookLoader,
    private val chapterTextSearch: ChapterTextSearch,
    private val ttsManager: TtsManager,
    private val backgroundImporter: com.flowreader.app.util.ReaderBackgroundImporter,
    private val fontImporter: com.flowreader.app.util.CustomFontImporter,
    private val readerStateDao: ReaderStateDao? = null,
    /** 真实目录树读取（toc_nodes）。旧书没有节点时返回 null，由兼容适配器兜底。 */
    private val tocRepository: TocRepository? = null,
    /** 结构图片解析（S5）：按当前 section 从内容包取图，只读该 section 涉及的文档。 */
    private val chapterImageResolver: ChapterImageResolver,
    val pageCache: com.flowreader.app.ui.screens.reader.components.ReaderPageCache = com.flowreader.app.ui.screens.reader.components.ReaderPageCache()
) : ViewModel() {

    private val initialSettingsReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val progressEngine = ReaderProgressEngine()
    private val sessionTracker = ReaderSessionTracker(nowProvider = android.os.SystemClock::uptimeMillis)
    private var readerContentVisible = false
    private val ttsCoordinator = ReaderTtsCoordinator(ttsManager)

    private val bookId: Long = savedStateHandle.get<Long>("bookId") ?: 0L
    private val initialChapterIndex: Int = savedStateHandle.get<Int>("chapterIndex") ?: -1

    /**
     * 搜索结果带来的**章内 UTF-16 命中偏移**；-1 表示只跳到章首。
     *
     * 主计划第 9 节：搜索结果必须携带 documentKey + UTF-16 源范围，点击后精确定位，
     * 而不是永远落到章首。该值来自 [SearchHitProjector] 的已验证投影，
     * **不是** FTS 分词位置。
     */
    private val initialLocatorOffset: Int = savedStateHandle.get<Int>("locatorOffset") ?: -1

    private var navigationSequence: Long = 0L

    fun getNavigationSequence(): Long = navigationSequence

    fun buildCurrentLocator(chapterIndex: Int, position: Int): TextLocator {
        val currentBook = _uiState.value.book
        val state = _uiState.value
        val currentChapter = if (chapterIndex == state.currentChapterIndex) state.currentChapter else state.chapters.getOrNull(chapterIndex)
        val pubId = currentBook?.publicationId ?: "legacy-$bookId"
        val rev = currentBook?.activeRevision ?: "legacy"
        val docKey = currentChapter?.let { "chapter:${it.index}" } ?: "chapter:$chapterIndex"
        val content = currentChapter?.content ?: ""
        val offset = position.coerceIn(0, content.length.coerceAtLeast(0))

        val before = if (offset > 0) content.substring((offset - 30).coerceAtLeast(0), offset) else ""
        val after = if (offset < content.length) content.substring(offset, (offset + 30).coerceAtMost(content.length)) else ""

        return TextLocator(
            version = TextLocator.CURRENT_VERSION,
            publicationId = pubId,
            revision = rev,
            normalizationVersion = 1,
            sectionId = "section_$chapterIndex",
            documentKey = docKey,
            offsetUtf16 = offset,
            affinity = Affinity.AFTER,
            context = TextContext(before = before, after = after)
        )
    }

    private val _uiState = MutableStateFlow(ReaderUiState())
    /** An explicit jump must show its destination before continuous-pager feedback may adopt a neighbour. */
    private var pendingPagedEntry: Pair<Long, Int>? = null
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var progressSaveJob: Job? = null
    private var statsUpdateJob: Job? = null
    private var periodicStatsSaveJob: Job? = null
    private var eyeProtectionJob: Job? = null
    private var predictionJob: Job? = null
    private var settingsSaveJob: Job? = null
    private var pendingReadingSettings: ReadingSettings? = null
    private var settingsSaveGeneration = 0L
    private var chapterPreloadJob: Job? = null

    private data class ChapterPresentation(val images: List<ChapterImageRef>, val marks: ChapterMarks)

    // Only presentation metadata lives here. Chapter text continues to use CacheManager.
    private val chapterPresentations = object : LinkedHashMap<Triple<String, String, Int>, ChapterPresentation>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, Int>, ChapterPresentation>?): Boolean =
            size > MAX_PREPARED_CHAPTERS
    }
    private val presentationLoads = mutableMapOf<Triple<String, String, Int>, Deferred<ChapterPresentation>>()
    private val progressDebounceMs = 3000L

    private var lastPositionUpdateTime: Long = 0
    private var lastUiPositionUpdateTime: Long = 0
    private var lastPredictionUpdateTime: Long = 0
    private var lastWidgetProgressPercent: Int = -1
    private var lastControlsToggleUptime: Long = 0
    private var chapterFraction: Float = 0f
    private val chapterPositions = mutableMapOf<Int, Int>()

    /**
     * 全书进度权重索引（主计划 10.2 / INV11）。
     *
     * 分母来自**完整出版物索引**（一次 SQL 聚合，不加载正文），因此进度按真实文本量加权，
     * 而不是旧实现的「章节等权」（F03）。索引未就绪时 [ContentProgressIndex.isReady] 为 false，
     * 调用方回退到等权并在 UI 显示“正在计算”，不会假装精确。
     */
    private var progressIndex: ContentProgressIndex = ContentProgressIndex.EMPTY

    /** Latest fraction through the open chapter; the controls layer reads it for the slider. */
    val currentChapterFraction: Float
        get() = chapterFraction

    /**
     * 全书进度（0..1），按内容权重计算；供控制层的进度条直接读取。
     * 索引未就绪时内部回退到等权，并与 [chapterOrdinalAt] 使用同一套映射，保证条与逆映射一致。
     */
    val bookProgressFraction: Float
        get() = weightedFraction(_uiState.value.currentChapterIndex, chapterFraction)

    /** 进度占比 → 章节序号；供进度条旁的章节名提示使用。 */
    fun chapterOrdinalAt(fraction: Float): Int {
        val state = _uiState.value
        if (state.chapters.isEmpty()) return 0
        val index = progressIndex
        val ordinal = if (index.isReady) {
            index.sectionAt(fraction.toDouble())
        } else {
            progressEngine.chapterAt(fraction, state.chapters.size)
        }
        return ordinal.coerceIn(0, state.chapters.size - 1)
    }

    private val positionUpdateIntervalMs = 250L
    private val predictionUpdateIntervalMs = 1_500L

    init {
        loadBook()
        loadSettings()
        loadTodayStats()
        loadProgressIndex()
        observeTtsState()
        startEyeProtectionTimer()
        startPeriodicStatsSave()
    }

    /**
     * 构建进度权重索引。这是 100/900 字 golden 能成立的前提：分量来自真实文本量，
     * 与当前是否已加载某章正文无关。
     */
    private fun loadProgressIndex() {
        viewModelScope.launch {
            val counts = runCatching { chapterRepository.getTextUnitCounts(bookId) }.getOrDefault(emptyList())
            if (counts.isEmpty()) return@launch
            progressIndex = ContentProgressIndex.of(
                counts.map { (chapterIndex, units) -> "chapter:$chapterIndex" to units }
            )
            // 索引就绪后用真实权重刷新一次剩余时间；进度本身由各处调用方按需读取索引计算。
            calculateReadingPrediction()
        }
    }

    /** 按权重计算全书进度；索引未就绪时回退等权（并可由 UI 标记“正在计算”）。 */
    private fun weightedFraction(ordinal: Int, fraction: Float): Float {
        val index = progressIndex
        if (!index.isReady) {
            return progressEngine.fraction(ordinal, fraction, _uiState.value.chapters.size)
        }
        return index.progress(ordinal, fraction.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 剩余分钟（主计划 10.4 第 9 条）。返回 null 表示**未知**，UI 必须显示“估算中”而不是
     * 一个伪造的数值；到主线末尾返回 0。
     */
    private fun remainingMinutesAt(ordinal: Int, fraction: Float): Int? {
        val index = progressIndex
        if (!index.isReady) {
            // 索引未就绪：不用等权近似冒充剩余量，直接报告未知。
            return null
        }
        val remaining = index.remainingUnits(ordinal, fraction.toDouble())
        // 速度样本：沿用会话速度；无样本（<=0）时为 null → ETA 未知。
        val unitsPerMinute = sessionTracker.readingSpeed.toDouble().takeIf { it > 0.0 }
        return ReadingSpeedEstimator.remainingMinutes(remaining, unitsPerMinute)
    }

    private fun observeTtsState() {
        viewModelScope.launch {
            ttsManager.state.collect { ttsState ->
                _uiState.update { it.copy(isTtsPlaying = ttsState.isSpeaking, message = ttsState.error ?: it.message) }
            }
        }
    }

    private fun startPeriodicStatsSave() {
        periodicStatsSaveJob?.cancel()
        periodicStatsSaveJob = viewModelScope.launch {
            while (true) {
                delay(5_000L)
                saveReadingStats()
            }
        }
    }

    fun onReaderVisibilityChanged(visible: Boolean) {
        readerContentVisible = visible
        sessionTracker.setActive(visible)
        if (!visible) saveReadingStats()
    }

    private fun loadTodayStats() {
        viewModelScope.launch {
            val readTime = readingStatsRepository.getTodayReadTime()
            val readPages = readingStatsRepository.getTodayReadPages()
            val dailyGoal = settingsRepository.getDailyReadingGoal().first()
            val goalProgress = if (dailyGoal > 0) (readTime.toFloat() / (dailyGoal * 60)).coerceIn(0f, 1f) else 0f

            _uiState.update {
                it.copy(
                    todayReadTime = readTime,
                    todayReadPages = readPages,
                    dailyGoalProgress = goalProgress
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressSaveJob?.cancel()
        eyeProtectionJob?.cancel()
        predictionJob?.cancel()
        periodicStatsSaveJob?.cancel()
        ttsCoordinator.shutdown()
        // viewModelScope is already cancelled here, so a plain launch would never run and the
        // final progress/stats would be lost on exit. Use an independent scope for the flush.
        val flushScope = CoroutineScope(Dispatchers.IO)
        val state = _uiState.value
        sessionTracker.setActive(false)
        val (pages, seconds) = sessionTracker.takeSnapshotAndReset()
        if (seconds > 0 || pages > 0) flushScope.launch {
            runCatching { readingStatsRepository.updateTodayStats(bookId, pages, seconds) }
                .onFailure { android.util.Log.e("ReaderViewModel", "Failed to flush reading stats", it) }
        }
        if (state.chapters.isNotEmpty()) {
            val position = chapterPositions[state.currentChapterIndex] ?: state.currentPosition
            val progress = weightedFraction(state.currentChapterIndex, chapterFraction)
            val seq = ++navigationSequence
            val locator = currentContentLocator(state.currentChapterIndex, position)
            val locatorJson = TextLocatorCodec.toJson(locator)
            val stateEntity = ReaderStateEntity(
                bookId = bookId,
                locatorJson = locatorJson,
                quality = "EXACT",
                legacySnapshotJson = """{"chapterIndex":${state.currentChapterIndex},"position":$position,"fraction":$chapterFraction}""",
                lastMainLocatorJson = locatorJson,
                navigationSequence = seq,
                updatedAtMillis = System.currentTimeMillis()
            )
            flushScope.launch {
                persistReadingProgress(stateEntity, state.currentChapterIndex, position, progress)
            }
        }
    }

    private fun saveReadingStats() {
        val (pages, seconds) = sessionTracker.takeSnapshotAndReset()
        // 同上：时长与页数任一 > 0 即保存，不再要求同时具备（F04 / T073）。
        if (seconds > 0 || pages > 0) {
            // A consumed delta must finish writing even when the reader destination is removed.
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    readingStatsRepository.updateTodayStats(
                        bookId = bookId,
                        readPages = pages,
                        readTimeSeconds = seconds
                    )
                    kotlinx.coroutines.withContext(Dispatchers.Main) { loadTodayStats() }
                } catch (e: Exception) {
                    android.util.Log.e("ReaderViewModel", "Failed to save reading stats", e)
                }
            }
        }
    }

    /**
     * 滚动视口投影回调（S5 / 主计划 6.6 / INV08）。
     *
     * `ReaderContent` 在**滚动停止**后把视口顶部投影为规范源 UTF-16 偏移，本方法负责：
     * 1. 丢弃过期反馈：反馈所属章节与当前章节不一致、或携带的 generation 落后时直接忽略；
     * 2. 生成带上下文的权威 [TextLocator]，并走与分页模式同一条 `ReaderStateDao.saveIfNewer`
     *    单调递增序写入（旧 sequence 永远不能覆盖新 sequence）；
     * 3. **不用视口行首覆盖用户锚点**（主计划 6.5 第 6 条的反向约束）：只有当投影确实给出了
     *    可用源坐标时才提交，投影未就绪时保持既有权威定位不变。
     */
    fun onScrollViewportProjected(projection: com.flowreader.app.ui.screens.reader.components.ScrollViewportProjection) {
        if (!projection.isUsable) return
        val state = _uiState.value
        if (state.chapters.isEmpty()) return
        if (projection.chapterIndex != state.currentChapterIndex) return
        if (state.isLoading || state.isChapterSwitching) return

        val chapter = state.currentChapter ?: return
        val offset = projection.offsetUtf16!!.coerceIn(0, chapter.content.length)
        // 与像素位置互校：源偏移应落在本章正文范围内，避免跨章排版数据串台。
        val locator = scrollProjectionToLocator(
            projection = projection,
            publicationId = state.book?.publicationId ?: "legacy-$bookId",
            revision = state.book?.activeRevision ?: "legacy",
            sectionId = "section_${projection.chapterIndex}",
            documentKey = "chapter:${chapter.index}",
            sourceText = chapter.content
        ) ?: return

        // 回写像素位置（供既有滚动恢复逻辑使用），同时以源 locator 作为权威位置。
        chapterPositions[projection.chapterIndex] = offset
        val fraction = ReadingProgress.scrollFraction(offset, chapter.content.length.coerceAtLeast(1))
        chapterFraction = fraction
        _uiState.update { it.copy(currentPosition = offset) }

        val seq = ++navigationSequence
        val locatorJson = TextLocatorCodec.toJson(locator)
        val stateEntity = ReaderStateEntity(
            bookId = bookId,
            locatorJson = locatorJson,
            quality = projection.quality.name,
            legacySnapshotJson = """{"chapterIndex":${projection.chapterIndex},"position":$offset,"fraction":$fraction}""",
            lastMainLocatorJson = locatorJson,
            navigationSequence = seq,
            updatedAtMillis = System.currentTimeMillis()
        )
        lastConfirmedLocator = locator
        debouncedSaveLocator(seq, stateEntity, projection.chapterIndex, offset, fraction)
    }

    /** 最近一次已确认的权威定位；重排/恢复时作为锚点，不被视口行首立即覆盖。 */
    private var lastConfirmedLocator: TextLocator? = null

    private fun currentContentLocator(index: Int, legacyPosition: Int): TextLocator =
        lastConfirmedLocator?.takeIf {
            it.sectionId == "section_$index" && it.documentKey == "chapter:${_uiState.value.chapters.getOrNull(index)?.index}"
        } ?: buildCurrentLocator(index, if (positionIsPageIndex) 0 else legacyPosition)

    /** 有序写入器：只允许更高 sequence 覆盖，杜绝旧异步结果回滚新位置（6.7）。 */
    private fun debouncedSaveLocator(seq: Long, entity: ReaderStateEntity, chapterIndex: Int, position: Int, fraction: Float) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(progressDebounceMs)
            persistReadingProgress(entity, chapterIndex, position, fraction)
        }
    }

    /** 已落库的最大 sequence，用于淘汰迟到的旧写入。 */
    private var lastPersistedSequence: Long = 0L
    private val progressWriteMutex = Mutex()

    private suspend fun persistReadingProgress(entity: ReaderStateEntity, chapterIndex: Int, position: Int, progress: Float) {
        // The legacy Book row has no sequence guard. Serialize it with locator writes, including
        // the exit flush, so a cancelled, slow write cannot finish after a newer chapter's save.
        progressWriteMutex.withLock {
            if (entity.navigationSequence < lastPersistedSequence) return@withLock
            readerStateDao?.saveIfNewer(entity)
            bookRepository.updateReadingProgress(bookId, chapterIndex, position, progress)
            lastPersistedSequence = entity.navigationSequence
        }
    }

    private fun debouncedSaveProgress(chapterIndex: Int, position: Int, progress: Float, debounceMillis: Long = progressDebounceMs) {
        progressSaveJob?.cancel()
        val seq = ++navigationSequence
        val locator = currentContentLocator(chapterIndex, position)
        val locatorJson = TextLocatorCodec.toJson(locator)
        val stateEntity = ReaderStateEntity(
            bookId = bookId,
            locatorJson = locatorJson,
            quality = "EXACT",
            legacySnapshotJson = """{"chapterIndex":$chapterIndex,"position":$position,"fraction":$chapterFraction}""",
            lastMainLocatorJson = locatorJson,
            navigationSequence = seq,
            updatedAtMillis = System.currentTimeMillis()
        )
        progressSaveJob = viewModelScope.launch {
            delay(debounceMillis)
            try {
                persistReadingProgress(stateEntity, chapterIndex, position, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "阅读进度保存失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsRepository.appSettings.map { it.speechEnabled }.distinctUntilChanged()
                .catch { _uiState.update { state -> state.copy(speechEnabled = false) } }
                .collect { enabled ->
                _uiState.update { it.copy(speechEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.appSettings
                .map { it.defaultReadingSettings }
                .distinctUntilChanged()
                .catch { initialSettingsReady.complete(Unit) }
                .onCompletion { initialSettingsReady.complete(Unit) }
                .collect { settings ->
                    // Widget/progress edits emit the same old preferences while a local change
                    // is waiting for its debounced save. They must not undo the live preview.
                    if (pendingReadingSettings != null) {
                        initialSettingsReady.complete(Unit)
                        return@collect
                    }
                    val previousInterval = _uiState.value.readingSettings.eyeProtectionIntervalMinutes
                    _uiState.update {
                        it.copy(readingSettings = settings)
                    }
                    initialSettingsReady.complete(Unit)
                    if (settings.eyeProtectionIntervalMinutes != previousInterval || eyeProtectionJob == null) {
                        startEyeProtectionTimer()
                    }
                }
        }
    }

    /** 最近一次恢复的质量与原因，供 UI 显示「定位待恢复」而不是假装精确。 */
    private var pendingRecoveryQuality: com.flowreader.app.domain.model.ResolutionQuality =
        com.flowreader.app.domain.model.ResolutionQuality.EXACT
    private var pendingRecoveryReason: String? = null

    /** 进入阅读器时待执行的精准滚动目标（S5，滚动模式使用）。 */
    private var pendingScrollTarget: TextLocator? = null

    /** 消费一次待执行的滚动目标；读取后清空，避免重复滚动。 */
    fun consumePendingScrollTarget(): TextLocator? {
        val target = pendingScrollTarget
        pendingScrollTarget = null
        return target
    }

    fun clearSearchTarget() {
        _uiState.update { it.copy(searchTarget = null) }
    }

    /**
     * 用六级容错恢复解析持久 [TextLocator]（主计划 3.4）。
     *
     * 这里把「章节列表」投影成一份最小 [PublicationManifest]：每章是一个 section，
     * 其范围为该章在规范源文本中的全区间。恢复器因此可以按
     * EXACT → CONTEXT_MATCH → SECTION_OFFSET → CHAPTER_PAGE → HEADING → REVISION_ROOT
     * 的固定阶梯求值，而不需要旧式的字符串前缀猜测。
     *
     * **成本契约：与全书章数无关。**
     *
     * 旧实现把**每一章正文**都读出来拼这份清单（真机 1434 章的书 = 1434 次正文读取，且发生在
     * 打开书的同步路径上），而 [ContentLocatorResolver] 实际只在
     * `ContentLocatorResolver.kt` 的 `textProvider`/`documentProvider` 处为**目标文档**取值。
     * 现在：
     *
     * - 各章**长度**来自一次聚合查询（`LENGTH(content)`，不加载正文）；
     * - **正文**只为 locator 真正可能查阅的那一章读（它的 documentKey 与 sectionId 指向的读序，
     *   最多 2 章），并用真实 `.length` 覆盖它的长度，因此 Tier-1 的边界判断仍然精确。
     *
     * 查询失败时退化为长度 0：只影响「与 locator 无关的章节」的夹取上界，
     * locator 自己指向的那一章仍然使用真实长度。
     */
    private suspend fun resolveStoredLocator(
        locator: TextLocator,
        book: Book,
        chapterMetadata: List<Chapter>
    ): com.flowreader.app.domain.model.LocatorResolution {
        val publicationId = book.publicationId ?: "legacy-$bookId"
        val revision = book.activeRevision ?: "legacy"
        // Persisted document keys use the stored chapter index; the compatibility manifest below
        // uses reading ordinals. Normalize at this boundary, including old ordinal-key bookmarks.
        val sectionOrdinal = locator.sectionId?.substringAfter(':')?.substringAfter('_')?.toIntOrNull()
            ?.takeIf { it in chapterMetadata.indices }
        val storedIndex = locator.documentKey.substringAfterLast('/').removePrefix("chapter:").toIntOrNull()
        val targetOrdinal = sectionOrdinal
            ?: chapterMetadata.indexOfFirst { it.index == storedIndex }.takeIf { it >= 0 }
            ?: storedIndex?.takeIf { it in chapterMetadata.indices }
        val normalizedLocator = if (targetOrdinal == null) locator else locator.copy(
            sectionId = "section_$targetOrdinal", documentKey = "chapter:$targetOrdinal"
        )

        // 恢复器只会查阅 locator 自己指向的文档/章节。documentKey 的取值与
        // ContentLocatorResolver.findDocumentKey 一致：先精确匹配，再按最后一段路径匹配，
        // 所以这里也按「最后一段」解析，保证候选集是旧行为的超集。
        val candidateOrdinals = linkedSetOf<Int>()
        normalizedLocator.documentKey.substringAfterLast('/').removePrefix("chapter:").toIntOrNull()
            ?.let { candidateOrdinals += it }
        normalizedLocator.sectionId?.substringAfter(':')?.substringAfter('_')?.toIntOrNull()
            ?.let { candidateOrdinals += it }

        val loadedContent = HashMap<Int, String>()
        for (ordinal in candidateOrdinals) {
            if (ordinal !in chapterMetadata.indices) continue
            val meta = chapterMetadata[ordinal]
            loadedContent[ordinal] = if (meta.content.isNotEmpty()) {
                meta.content
            } else {
                chapterRepository.getChapterContent(bookId, meta.index) ?: ""
            }
        }

        // The normal same-revision bookmark only needs its own chapter, not a full-book LENGTH
        // scan and compatibility manifest. Keep the six-tier resolver for revision recovery.
        val targetText = targetOrdinal?.let { loadedContent[it] }
        if (targetOrdinal != null && targetText != null && locator.publicationId == publicationId && locator.revision == revision &&
            locator.offsetUtf16 in 0..targetText.length) {
            return com.flowreader.app.domain.model.LocatorResolution(
                locator.copy(sectionId = "section_$targetOrdinal", documentKey = "chapter:${chapterMetadata[targetOrdinal].index}"),
                ResolutionQuality.EXACT, ContentLocatorResolver.TIER_EXACT_SOURCE_TEXT
            )
        }

        val storedLengths = runCatching { chapterRepository.getChapterTextLengths(bookId) }
            .getOrDefault(emptyMap())

        // 兼容投影必须与**持久化时的坐标约定**一致，否则恢复器会在错误的模型上求值：
        // 持久层每个逻辑章节是一个独立文档（documentKey = "chapter:<读序>"），
        // 偏移是**章内**偏移；sectionId = "section:<读序>"。
        // 早期实现把它拼成一整篇 text/main，导致 Tier-3 只能返回拼接后的全局偏移，
        // 恢复出来的位置会偏到别的章节去。
        val sections = mutableListOf<com.flowreader.app.domain.model.ReadingSection>()
        val documents = mutableListOf<com.flowreader.app.domain.model.PublicationDocument>()
        chapterMetadata.forEachIndexed { ordinal, chapter ->
            val chapterDocKey = "chapter:$ordinal"
            val content = loadedContent[ordinal]
            // 已加载正文的章节用**真实 UTF-16 长度**；其余用聚合查询的字符数（中文正文两者相同）。
            val length = content?.length ?: storedLengths[chapter.index] ?: 0
            // 非目标章节的非空白字数只是近似（未加载正文），但恢复器不消费这些字段做定位。
            val units = (content?.count { !it.isWhitespace() } ?: length).toLong()
            sections.add(
                com.flowreader.app.domain.model.ReadingSection(
                    sectionId = "section_$ordinal",
                    ordinal = ordinal,
                    title = chapter.title,
                    aliases = emptyList(),
                    kind = com.flowreader.app.domain.model.SectionKind.NORMAL,
                    ranges = listOf(com.flowreader.app.domain.model.SourceRange(chapterDocKey, 0, length)),
                    mainTextUnits = units
                )
            )
            documents.add(
                com.flowreader.app.domain.model.PublicationDocument(
                    documentKey = chapterDocKey,
                    ordinal = ordinal,
                    linear = true,
                    role = com.flowreader.app.domain.model.DocumentRole.MAIN,
                    textLengthUtf16 = length,
                    mainTextUnits = units,
                    manifestRelativePath = "text/chapter-$ordinal",
                    sourceHash = ""
                )
            )
        }
        val manifest = com.flowreader.app.domain.model.PublicationManifest(
            publicationId = publicationId,
            revision = revision,
            normalizationVersion = 1,
            parserSchemaVersion = 1,
            sourceHash = "",
            manifestRelativePath = "manifest.json",
            spineOccurrences = emptyList(),
            documents = documents,
            sections = sections,
            tocTargetSections = emptyMap(),
            totalMainTextUnits = documents.sumOf { it.mainTextUnits },
            resources = emptyList()
        )
        val resolution = ContentLocatorResolver.resolve(
            locator = normalizedLocator,
            activePublicationId = publicationId,
            activeRevision = revision,
            manifest = manifest,
            documentProvider = { key -> buildChapterDocument(key, loadedContent) }
        )
        val resolved = resolution.locator ?: return resolution
        val ordinal = resolved.sectionId?.substringAfter(':')?.substringAfter('_')?.toIntOrNull()
            ?: resolved.documentKey.removePrefix("chapter:").toIntOrNull()
        return resolution.copy(locator = resolved.copy(
            documentKey = ordinal?.let { chapterMetadata.getOrNull(it)?.index }?.let { "chapter:$it" }
                ?: resolved.documentKey
        ))
    }

    /**
     * 把某一章的正文包装成最小 [StructuredDocument]，供恢复器的上下文/锚点匹配使用。
     *
     * [loadedContent] 只包含本次恢复真正可能被查阅的章节（见 [resolveStoredLocator]）；
     * 不在其中的文档返回 null，恢复器会照常落到下一级阶梯，**不会**因此读整本书。
     */
    private fun buildChapterDocument(
        documentKey: String,
        loadedContent: Map<Int, String>
    ): com.flowreader.app.domain.model.StructuredDocument? {
        val ordinal = documentKey.removePrefix("chapter:").toIntOrNull() ?: return null
        val content = loadedContent[ordinal] ?: return null
        return com.flowreader.app.domain.model.StructuredDocument(
            documentKey = documentKey,
            text = content,
            blocks = emptyList(),
            anchorIndex = emptyMap(),
            resourceRefs = emptyList(),
            mainTextUnits = content.count { !it.isWhitespace() }.toLong()
        )
    }

    fun retryLoadBook() {
        loadBook()
    }

    private fun loadBook() {
        pendingPagedEntry = null
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                if (bookId <= 0L) {
                    _uiState.update { it.copy(isLoading = false, error = "无效的书籍 ID") }
                    return@launch
                }

                // Do not typeset with temporary defaults before the saved font/layout arrives.
                initialSettingsReady.await()
                val book = bookRepository.getBookById(bookId)
                val chapterMetadata = chapterRepository.getChapterMetadataList(bookId)
                val bookmarks = bookmarkRepository.getBookmarksListByBookId(bookId)
                val annotations = annotationRepository.getAnnotationsListByBookId(bookId)

                if (book != null && chapterMetadata.isNotEmpty()) {
                    val chapters = if (book.format == BookFormat.COMIC) {
                        chapterMetadata.map { meta ->
                            meta.copy(content = chapterRepository.getChapterContent(bookId, meta.index) ?: "")
                        }
                    } else {
                        chapterMetadata
                    }
                    var currentChapterIndex = book.currentChapter.coerceIn(0, chapterMetadata.size - 1)
                    var currentPosition = book.currentPosition

                    val savedState = readerStateDao?.getState(bookId)
                    if (savedState != null) {
                        navigationSequence = maxOf(navigationSequence, savedState.navigationSequence)
                        val locator = savedState.locatorJson?.let { TextLocatorCodec.fromJson(it) }
                        if (locator != null) {
                            // 六级容错恢复（主计划 3.4）：不再只做 sectionId 前缀的字符串解析。
                            // 恢复结果会带上质量等级，APPROXIMATE/UNRESOLVED 时 UI 必须可见提示，
                            // 且**不得**用近似位置覆盖用户记录。
                            val resolution = resolveStoredLocator(locator, book, chapterMetadata)
                            val resolved = resolution.locator
                            pendingRecoveryQuality = resolution.quality
                            pendingRecoveryReason = resolution.reasonCode
                            if (resolved != null) {
                                // section_${ordinal} 编码的是**阅读序**（section 在主线中的位置），
                                // 不是 chapters.index（物理章节号，可能带跳号或分片偏移）。
                                // 因此先按读序匹配，再回落按物理 index 匹配，最后才用 documentKey。
                                // 兼容两种历史前缀："section_1"（旧写法）与 "section:1"（新约定）。
                                val sectionOrdinal = resolved.sectionId
                                    ?.substringAfter(':')
                                    ?.substringAfter('_')
                                    ?.toIntOrNull()
                                val docIdx = resolved.documentKey.removePrefix("chapter:").toIntOrNull()
                                val candidateIndex = sectionOrdinal?.takeIf { it in chapterMetadata.indices }
                                    ?: docIdx?.takeIf { it in chapterMetadata.indices }
                                    ?: sectionOrdinal?.let { ordinal ->
                                        chapterMetadata.indices.firstOrNull { chapterMetadata[it].index == ordinal }
                                    }
                                if (candidateIndex != null) {
                                    currentChapterIndex = candidateIndex
                                    currentPosition = resolved.offsetUtf16
                                }
                            } else {
                                // Tier 6：连文档都无法确定。保留用户原记录，位置明确回退到内容起点，
                                // 并携带 UNRESOLVED 让 UI 显示「定位待恢复」。
                                currentPosition = 0
                            }
                            lastConfirmedLocator = resolved
                            // 滚动模式进入时按源 locator 精准滚动（S5）；分页模式忽略该信号。
                            pendingScrollTarget = resolved
                        }
                    }

                    val currentChapter = chapters.getOrNull(currentChapterIndex)?.let { meta ->
                        if (meta.content.isNotEmpty()) {
                            meta
                        } else {
                            meta.copy(
                                content = chapterRepository.getChapterContent(bookId, meta.index) ?: ""
                            )
                        }
                    }
                    // S5：当前章的结构图片（只读该 section 涉及的文档，不读整本书）
                    val chapterImages = loadChapterImages(book, currentChapterIndex)
                    // schema v3：当前章的呈现标注（列表/表格/注音），与图片同一时点取好。
                    val chapterMarks = loadChapterMarks(book, currentChapterIndex)

                    // 真实目录树（toc_nodes）。加载失败或无节点都返回 null，
                    // 此时 UI 回退到 LegacyTocAdapter —— 目录**绝不能**因此变空。
                    val tocTree = runCatching {
                        book.activeRevision?.let { revision ->
                            tocRepository?.getTocTree(bookId, revision, book.publicationId ?: "")
                        }
                    }.getOrNull()
                    // 重置目录展开状态：换了书/换 revision 后旧 nodeId 可能已不存在。
                    val tocExpanded = if (tocTree == null) {
                        emptySet()
                    } else {
                        _uiState.value.tocExpandedNodeIds.filter { tocTree.node(it) != null }.toSet()
                    }

                    sessionTracker.startSession(active = readerContentVisible)
                    lastPositionUpdateTime = System.currentTimeMillis()

                    _uiState.update {
                        it.copy(
                            book = book,
                            chapters = chapters,
                            currentChapter = currentChapter,
                            tocTree = tocTree,
                            tocExpandedNodeIds = tocExpanded,
                            currentChapterIndex = currentChapterIndex,
                            currentPosition = currentPosition,
                            chapterImages = chapterImages,
                            chapterMarks = chapterMarks,
                            bookmarks = bookmarks,
                            annotations = annotations,
                            isLoading = false,
                            searchTarget = pendingScrollTarget,
                            scrollRequestVersion = it.scrollRequestVersion + 1
                        )
                    }

                    observeSeries(book)
                    calculateReadingPrediction()
                    preloadAdjacentChapters(currentChapterIndex, chapters.size)

                    val searchOrdinal = chapterMetadata.indexOfFirst { it.index == initialChapterIndex }
                    if (searchOrdinal >= 0) {
                        // 搜索入口携带的是库中章号；定位前映射到读序，并在测量后转换源偏移。
                        val searchOffset = initialLocatorOffset
                        if (searchOffset >= 0) {
                            goToSearchHit(searchOrdinal, searchOffset)
                        } else {
                            goToChapter(searchOrdinal)
                        }
                    }
                } else {
                    val errorMsg = if (book == null) "未找到书籍" else "书籍暂无章节内容"
                    _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "加载书籍失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    private var offeredNextVolume = false

    private fun observeSeries(book: Book) {
        if (book.seriesName == null) return
        viewModelScope.launch {
            bookRepository.getAllBooks().collect { library ->
                val current = library.firstOrNull { it.id == bookId }
                _uiState.update { it.copy(nextVolume = current?.let { value -> com.flowreader.app.domain.model.BookSeries.next(value, library) }) }
            }
        }
    }

    private fun offerNextVolume(force: Boolean = false) {
        if (_uiState.value.nextVolume != null && (force || !offeredNextVolume)) {
            offeredNextVolume = true
            _uiState.update { it.copy(showNextVolumePrompt = true) }
        }
    }

    fun consumeNextVolumePrompt() { _uiState.update { it.copy(showNextVolumePrompt = false) } }

    fun goToNextChapter() {
        val state = _uiState.value
        if (state.isLoading || state.isChapterSwitching) return
        if (state.currentChapterIndex < state.chapters.size - 1) {
            val nextIndex = state.currentChapterIndex + 1
            chapterPositions.remove(nextIndex)
            goToChapter(nextIndex, positionOverride = 0, landAtEnd = false)
        } else {
            offerNextVolume(force = true)
        }
    }

    /** Adopt a settled page from the continuous pager without requesting a new reader instance. */
    fun onPagedPageVisible(
        prepared: com.flowreader.app.ui.screens.reader.components.ReaderChapterPages,
        page: Int,
        fraction: Float,
        requestVersion: Long
    ) {
        val state = _uiState.value
        val index = prepared.chapterIndex
        if (state.isLoading || state.isChapterSwitching || state.scrollRequestVersion != requestVersion) return
        if (state.readingSettings.pageMode != com.flowreader.app.domain.model.PageMode.PAGED) return
        val metadata = state.chapters.getOrNull(index) ?: return
        if (metadata.index != prepared.chapter.index || prepared.chapter.bookId != bookId || page !in prepared.pages.indices) return
        pendingPagedEntry?.takeIf { it.first == requestVersion }?.let { entry ->
            if (index != entry.second) return
            pendingPagedEntry = null
        }
        val changedChapter = index != state.currentChapterIndex
        if (changedChapter) {
            chapterPositions.getOrPut(state.currentChapterIndex) { state.currentPosition }
            ttsCoordinator.stop()
            saveReadingStats()
            // Cross-chapter local page numbers are discontinuous; count a forward boundary as
            // one page and never count a backwards landing as dozens of newly read pages.
            sessionTracker.rebasePosition(if (index > state.currentChapterIndex) page - 1 else page)
            lastPositionUpdateTime = 0L
            lastUiPositionUpdateTime = 0L
            val updatedChapters = state.chapters.toMutableList().apply { set(index, prepared.chapter) }
            _uiState.update {
                it.copy(
                    chapters = updatedChapters,
                    currentChapter = prepared.chapter,
                    currentChapterIndex = index,
                    currentPosition = page,
                    chapterImages = prepared.images,
                    chapterMarks = prepared.marks,
                    landAtChapterEnd = false
                )
            }
            preloadAdjacentChapters(index, state.chapters.size)
        }
        // A rendered page index is not a source offset. Use the measured fragment range.
        val sourceOffset = prepared.pages[page].firstOrNull { !it.isTitle }
            ?.sourceRanges?.firstOrNull()?.startUtf16?.plus(prepared.bodyShift) ?: 0
        lastConfirmedLocator = buildCurrentLocator(index, sourceOffset)
        updatePosition(page, fraction, requestVersion)
        if (changedChapter) debouncedSaveProgress(index, page, weightedFraction(index, fraction), debounceMillis = 0L)
    }

    fun goToPreviousChapter() {
        val state = _uiState.value
        if (state.currentChapterIndex > 0) {
            goToChapter(state.currentChapterIndex - 1)
        }
    }

    /**
     * Enters the previous chapter from its *end*, which is what scrolling up past the start of the
     * current chapter means. [ReaderScreen] performs the scroll once the new chapter is measured.
     */
    fun goToPreviousChapterAtEnd() {
        val state = _uiState.value
        if (state.isLoading || state.isChapterSwitching || state.currentChapterIndex <= 0) return
        chapterPositions.remove(state.currentChapterIndex - 1)
        goToChapter(state.currentChapterIndex - 1, positionOverride = null, landAtEnd = true)
    }

    fun clearLandAtChapterEnd(position: Int? = null, requestVersion: Long = _uiState.value.scrollRequestVersion) {
        val current = _uiState.value
        if (current.isLoading || current.isChapterSwitching || current.scrollRequestVersion != requestVersion) return
        if (position != null) {
            chapterPositions[current.currentChapterIndex] = position
            chapterFraction = 1f
            debouncedSaveProgress(
                current.currentChapterIndex,
                position,
                progressEngine.fraction(current.currentChapterIndex, 1f, current.chapters.size)
            )
        }
        _uiState.update { state ->
            state.copy(
                landAtChapterEnd = false,
                currentPosition = position ?: state.currentPosition
            )
        }
    }

    fun goToChapter(index: Int) {
        goToChapter(index, null, false)
    }

    fun setCurrentComicPage(index: Int) {
        val state = _uiState.value
        if (state.book?.format != BookFormat.COMIC || index !in state.chapters.indices || index == state.currentChapterIndex) return
        val progress = weightedFraction(index, 0f)
        chapterFraction = 0f
        _uiState.update {
            it.copy(
                currentChapterIndex = index,
                currentChapter = state.chapters[index],
                currentPosition = 0
            )
        }
        debouncedSaveProgress(index, 0, progress)
        updateWidgetSnapshot(progress)
    }

    private fun goToChapter(index: Int, positionOverride: Int?, landAtEnd: Boolean = false) {
        val requested = _uiState.value
        if (requested.isLoading || requested.isChapterSwitching) return
        if (index !in requested.chapters.indices) return
        if (requested.searchTarget != null && requested.searchTarget.sectionId != "section_$index") clearSearchTarget()

        val previousState = _uiState.value
        // `chapterPositions` is written on every updatePosition() call, before the UI throttle, so
        // it is always at least as fresh as uiState.currentPosition (which lags up to 250ms).
        // Only seed it when the chapter was never scrolled, or leaving rewinds the saved spot.
        chapterPositions.getOrPut(previousState.currentChapterIndex) { previousState.currentPosition }
        ttsCoordinator.stop()
        saveReadingStats()

        val previousSave = progressSaveJob
        previousSave?.cancel()
        // Claim the transition before launching, including when the destination is cached.
        // Deliberately *not* isLoading: the reader stays mounted so the previous chapter remains
        // visible until the new content replaces it (no spinner, no empty-chapter flash).
        _uiState.update { it.copy(isChapterSwitching = true) }
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val existingChapter = state.chapters[index]
                // The row id / chapter index stored in the database is `chapter.index`, which is not
                // guaranteed to equal the list position for every book shape — EPUB books that skip a
                // spine entry used to shift the two apart. Resolve the stored index explicitly so a TOC
                // tap always lands on the tapped chapter.
                val storedIndex = existingChapter.index
                val content = if (existingChapter.content.isNotEmpty()) {
                    existingChapter.content
                } else {
                    chapterRepository.getChapterContent(bookId, storedIndex) ?: ""
                }
                val chapter = existingChapter.copy(content = content)
                // S5：图片必须在发布状态前拿到，分页才能一次算对（避免换章跳动）。
                val chapterImages = loadChapterImages(state.book, index)
                // schema v3：呈现标注同理 —— 列表前缀/注音影响排版与显示，必须与正文同时就绪。
                val chapterMarks = loadChapterMarks(state.book, index)
                val updatedChapters = state.chapters.toMutableList().apply {
                    if (index < size) set(index, chapter)
                }

                val restoredPosition = (if (landAtEnd) 0 else positionOverride ?: chapterPositions[index] ?: if (index == state.book?.currentChapter) state.book.currentPosition else 0)
                    .coerceAtLeast(0)
                chapterFraction = 0f
                val progress = weightedFraction(index, 0f)
                val nextRequestVersion = _uiState.value.scrollRequestVersion + 1
                pendingPagedEntry = if (state.readingSettings.pageMode == com.flowreader.app.domain.model.PageMode.PAGED &&
                    state.book?.format !in listOf(BookFormat.PDF, BookFormat.COMIC)) nextRequestVersion to index else null

                _uiState.update {
                    it.copy(
                        chapters = updatedChapters,
                        currentChapter = chapter,
                        currentChapterIndex = index,
                        currentPosition = restoredPosition,
                        chapterImages = chapterImages,
                        chapterMarks = chapterMarks,
                        isChapterSwitching = false,
                        showChapterList = false,
                        landAtChapterEnd = landAtEnd,
                        scrollRequestVersion = nextRequestVersion
                    )
                }

                if (positionOverride != null) {
                    chapterPositions[index] = restoredPosition
                    // Search/bookmark locators remain authoritative; a plain explicit jump does
                    // not inherit the old source anchor from a previous visit to the same chapter.
                    if (_uiState.value.searchTarget == null) lastConfirmedLocator = null
                }
                // Publish ready content before storage; slow writes must never hold a page turn.
                // Construct the locator after publishing so its context uses the destination text.
                if (!landAtEnd && _uiState.value.searchTarget == null) {
                    debouncedSaveProgress(index, restoredPosition, progress, debounceMillis = 0L)
                }
                calculateReadingPrediction()
                preloadAdjacentChapters(index, _uiState.value.chapters.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The previous chapter is still on screen, so a failed switch is recoverable: clear
                // the in-flight flag (instead of leaving the reader stuck) and report it.
                _uiState.update { it.copy(isChapterSwitching = false, message = "章节切换失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    private fun preloadAdjacentChapters(currentIndex: Int, totalChapters: Int) {
        chapterPreloadJob?.cancel()
        if (_uiState.value.book?.format in listOf(BookFormat.PDF, BookFormat.COMIC)) return
        chapterPreloadJob = viewModelScope.launch {
            for (delta in intArrayOf(1, 2, -1)) {
                val target = currentIndex + delta
                if (target !in 0 until totalChapters) continue
                try {
                    loadChapterForPagination(target)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ReaderViewModel", "Failed to preload chapter $target", e)
                }
            }
        }
    }

    /**
     * Loads a chapter's text for the pager's background pre-typesetting, without touching UI state.
     *
     * Returns null for an out-of-range chapter or one whose text cannot be read; the pager then
     * simply skips that neighbour. Reading through the repository (rather than the DAO) means the
     * in-memory chapter cache is populated exactly as a real navigation would populate it, so the
     * actual chapter change is a cache hit.
     */
    /**
     * 当前章的结构图片（S5）。失败、旧书、无内容包都返回空列表 —— 渲染层按「没有图」处理，
     * 但**不会**因为一张坏图让整章打不开。
     */
    private suspend fun loadChapterImages(book: Book?, chapterIndex: Int): List<ChapterImageRef> {
        return prepareChapterPresentation(book, chapterIndex).images
    }

    /** 供分页器给**相邻章**预排版时使用：图片必须一起算，否则预热出来的页与真实页不一致。 */
    suspend fun loadChapterImagesForPagination(index: Int): List<ChapterImageRef> = loadChapterImages(_uiState.value.book, index)

    /**
     * 当前章的呈现标注（schema v3）。失败、旧书、无内容包都返回 [ChapterMarks.EMPTY] ——
     * 渲染层按「没有标注」处理，但**不会**因为一次坏读取让整章打不开。
     */
    private suspend fun loadChapterMarks(book: Book?, chapterIndex: Int): ChapterMarks {
        return prepareChapterPresentation(book, chapterIndex).marks
    }

    private suspend fun prepareChapterPresentation(book: Book?, chapterIndex: Int): ChapterPresentation {
        val publicationId = book?.publicationId ?: return ChapterPresentation(emptyList(), ChapterMarks.EMPTY)
        val revision = book.activeRevision ?: return ChapterPresentation(emptyList(), ChapterMarks.EMPTY)
        val key = Triple(publicationId, revision, chapterIndex)
        chapterPresentations[key]?.let { return it }
        val pending = presentationLoads.getOrPut(key) {
            viewModelScope.async(start = CoroutineStart.LAZY) {
                try {
                    val images = try {
                        chapterImageResolver.imagesForSection(publicationId, revision, chapterIndex)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val marks = try {
                        chapterImageResolver.marksForSection(publicationId, revision, chapterIndex)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ChapterMarks.EMPTY
                    }
                    ChapterPresentation(images, marks).also { chapterPresentations[key] = it }
                } finally {
                    presentationLoads.remove(key)
                }
            }
        }
        return pending.await()
    }

    /** 供分页器给**相邻章**预排版时使用：标注必须与正文、图片一起算进缓存键。 */
    suspend fun loadChapterMarksForPagination(index: Int): ChapterMarks = loadChapterMarks(_uiState.value.book, index)

    suspend fun loadChapterForPagination(index: Int): Chapter? {
        val meta = _uiState.value.chapters.getOrNull(index) ?: return null
        val content = if (meta.content.isNotEmpty()) {
            meta.content
        } else {
            try {
                chapterRepository.getChapterContent(bookId, meta.index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            } ?: return null
        }
        prepareChapterPresentation(_uiState.value.book, index)
        return meta.copy(content = content)
    }

    /**
     * True when [updatePosition] is fed a rendered page index instead of a character offset. The
     * two units need different reading-stats math — see [ReaderPositionUnit].
     */
    private val positionIsPageIndex: Boolean
        get() {
            val state = _uiState.value
            return ReaderPositionUnit.of(state.readingSettings.pageMode, state.book?.format).isPageIndex
        }

    fun updatePosition(
        position: Int,
        chapterScrollFraction: Float = chapterFraction,
        requestVersion: Long = _uiState.value.scrollRequestVersion
    ) {
        val state = _uiState.value
        if (position < 0 || state.isLoading || state.isChapterSwitching) return
        if (state.scrollRequestVersion != requestVersion) return
        chapterFraction = if (chapterScrollFraction.isNaN()) 0f else chapterScrollFraction.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        chapterPositions[state.currentChapterIndex] = position
        val pageUnits = positionIsPageIndex

        // A page index moves by 1 per turn, so the 200-character scroll threshold can never fire;
        // any page change is significant on its own.
        val moveThreshold = if (pageUnits) 1 else 200
        val movedEnough = abs(position - sessionTracker.lastPosition) >= moveThreshold
        val waitedEnough = now - lastPositionUpdateTime >= positionUpdateIntervalMs
        if (lastPositionUpdateTime > 0 && !movedEnough && !waitedEnough) return

        if (sessionTracker.recordInteraction(position)) {
            saveReadingStats()
        }
        val charsPerPage = progressEngine.estimateCharsPerPage(
            state.readingSettings.fontSize,
            state.readingSettings.lineSpacing
        )
        if (pageUnits) {
            sessionTracker.recordPageProgress(pageIndex = position, charsPerPage = charsPerPage)
        } else {
            sessionTracker.recordProgress(
                position = position,
                content = state.currentChapter?.content,
                charsPerPage = charsPerPage
            )
        }
        _uiState.update { it.copy(readingSpeed = sessionTracker.readingSpeed) }

        lastPositionUpdateTime = now

        val progress = weightedFraction(state.currentChapterIndex, chapterFraction)
        if (state.currentChapterIndex == state.chapters.lastIndex && chapterFraction >= 1f) offerNextVolume()

        if (now - lastUiPositionUpdateTime >= positionUpdateIntervalMs) {
            lastUiPositionUpdateTime = now
            _uiState.update { it.copy(currentPosition = position) }
        }
        debouncedSaveProgress(state.currentChapterIndex, position, progress)
        updateWidgetSnapshot(progress)

        if (now - lastPredictionUpdateTime >= predictionUpdateIntervalMs) {
            lastPredictionUpdateTime = now
            calculateReadingPrediction()
        }
    }

    private fun updateWidgetSnapshot(progress: Float) {
        val title = _uiState.value.book?.title ?: return
        val percent = (progress * 100).roundToInt().coerceIn(0, 100)
        if (percent == lastWidgetProgressPercent) return
        lastWidgetProgressPercent = percent
        viewModelScope.launch {
            settingsRepository.updateReadingWidgetSnapshot(title, percent)
        }
    }

    private fun calculateReadingPrediction() {
        predictionJob?.cancel()
        predictionJob = viewModelScope.launch {
            val state = _uiState.value

            // 剩余时间按**内容权重**算，单位与进度一致（F02）：
            // 旧实现用 `chapter.content.length - currentPosition`，元数据章 content 为空时
            // 直接报 0 分钟；页下标还会被当成字符偏移减掉。
            // 速度没有合格样本时返回 null（UI 显示“估算中”），不再用 coerceAtLeast(100f) 伪造。
            val estimatedMinutes = remainingMinutesAt(state.currentChapterIndex, chapterFraction)
            val sessionTime = sessionTracker.elapsedSeconds / 60

            _uiState.update {
                it.copy(
                    estimatedTimeRemaining = estimatedMinutes?.toLong() ?: 0L,
                    sessionReadTime = sessionTime,
                    suggestedBreakTime = progressEngine.suggestedBreakMinutes(sessionTime)
                )
            }
        }
    }

    private fun startEyeProtectionTimer() {
        eyeProtectionJob?.cancel()
        eyeProtectionJob = viewModelScope.launch {
            while (true) {
                val intervalMinutes = _uiState.value.readingSettings.eyeProtectionIntervalMinutes.coerceAtLeast(15)
                delay(intervalMinutes * 60 * 1000L)
                _uiState.update { it.copy(showEyeProtectionReminder = true) }
            }
        }
    }

    private var searchJob: Job? = null
    private var searchGeneration = 0L

    fun cancelSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false, searchProgress = null, hasSearched = false) }
    }

    fun showSearch(show: Boolean) {
        cancelSearch()
        _uiState.update {
            it.copy(showSearch = show, searchQuery = "", searchResults = emptyList(), searchError = null)
        }
    }

    /** 打开/关闭目录。首次打开时按当前章自动展开祖先（计划 7.2 第 4 条）。 */
    fun showChapterList(show: Boolean) {
        if (show) ensureTocInitialExpansion()
        _uiState.update { it.copy(showChapterList = show) }
    }

    fun updateSearchQuery(query: String) {
        cancelSearch()
        _uiState.update { it.copy(searchQuery = query, searchResults = emptyList(), searchError = null) }
    }

    fun searchInBook() {
        val query = _uiState.value.searchQuery.trim()
        val book = _uiState.value.book ?: return
        if (query.isEmpty()) return
        cancelSearch()
        val request = searchGeneration
        _uiState.update { it.copy(isSearching = true, hasSearched = false, searchError = null) }
        searchJob = viewModelScope.launch {
            try {
                val hits = chapterTextSearch.search(listOf(book), query, 100, 0) { progress ->
                    if (request == searchGeneration) _uiState.update { it.copy(searchProgress = progress) }
                }
                currentCoroutineContext().ensureActive()
                if (request != searchGeneration) return@launch
                val located = hits.map { result ->
                    FtsSearchResult(
                        chapterIndex = result.chapterIndex,
                        chapterTitle = result.chapterTitle,
                        matchedText = result.matchedText,
                        bookId = result.bookId,
                        locatorOffset = result.firstStartUtf16 ?: -1,
                        locatorConfidence = result.confidence
                    )
                }
                _uiState.update {
                    it.copy(searchResults = located, isSearching = false, hasSearched = true, searchProgress = null)
                }
                settingsRepository.addSearchHistory(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ReaderViewModel", "Text search failed", e)
                if (request == searchGeneration) _uiState.update {
                    it.copy(isSearching = false, hasSearched = false, searchProgress = null,
                        searchError = "搜索失败：${e.localizedMessage ?: "无法读取正文"}")
                }
            }
        }
    }

    /**
     * 跳到书内搜索命中。有已验证偏移时**精确跳转**，否则退回章首并如实标注。
     *
     * 这修掉了旧行为：无论命中在章内哪里，点击都只跳到章首。
     */
    fun goToSearchResult(chapterIndex: Int, locatorOffset: Int = -1) {
        val ordinal = _uiState.value.chapters.indexOfFirst { it.index == chapterIndex }
        if (ordinal < 0) return
        cancelSearch()
        if (locatorOffset >= 0) {
            goToSearchHit(ordinal, locatorOffset)
        } else {
            // 无可靠坐标：明确标注近似，不假装精确。
            pendingRecoveryQuality = ResolutionQuality.APPROXIMATE
            pendingRecoveryReason = "SEARCH_HIT_UNRESOLVED"
            goToChapter(ordinal)
        }
        _uiState.update { it.copy(showSearch = false) }
    }

    fun dismissEyeProtectionReminder() {
        _uiState.update { it.copy(showEyeProtectionReminder = false) }
        startEyeProtectionTimer()
    }

    /**
     * 设置目录展开状态。
     *
     * 用户在目录里手动折叠**优先**：我们只是记录它，不会因为「当前章变了」就自动弹开
     * （主计划 7.2 第 5 条：目录已打开期间不因每次重组马上弹开）。
     */
    fun setTocExpandedNodes(nodeIds: Set<String>) {
        _uiState.update { it.copy(tocExpandedNodeIds = nodeIds) }
    }

    /**
     * 点击目录项的跳转（主计划 7.2 第 3 条）。
     *
     * 这里在 ViewModel 侧完成「目录项 → section → 章」的解析，而不是让 UI 自己按标题找：
     * 目录节点的 target 是**稳定 section id**，同名章不会串。
     */
    fun goToTocNode(tree: com.flowreader.app.domain.model.TocTree, nodeId: String?) {
        if (_uiState.value.isLoading || _uiState.value.isChapterSwitching) return
        if (nodeId == null) return
        val node = tree.node(nodeId) ?: return
        val sectionId = node.targetSectionId ?: return
        // 真实目录树的 target 是**清单 sectionId**（结构锚点哈希），必须经 reading_sections
        // 的 ordinal 映射才能得到章节下标；只有旧兼容树才是 section_<ordinal> 形式。
        val ordinal = tree.sectionOrdinalById[sectionId]
            ?: sectionId.removePrefix("section_").toIntOrNull()
            ?: return
        if (ordinal !in _uiState.value.chapters.indices) return
        // 记录入口节点，供目录高亮在「同名别名」中优先选它（7.2 第 12 条）。
        entryNodeId = nodeId
        clearSearchTarget()
        pendingScrollTarget = null
        goToChapter(ordinal, positionOverride = 0)
    }

    /** 用户从哪个目录入口进入的；用于别名优先级。 */
    private var entryNodeId: String? = null

    /**
     * 章节目录下标 → 该棵目录树使用的 section 坐标。
     *
     * 真实树的 target 是清单 sectionId；兼容树是 `section_<ordinal>`。
     * 二者必须按树本身选择，混用会让「当前章高亮」永远匹配不上。
     */
    private fun currentSectionIdFor(tree: com.flowreader.app.domain.model.TocTree, chapterIndex: Int): String {
        tree.sectionOrdinalById.entries.firstOrNull { it.value == chapterIndex }?.let { return it.key }
        return com.flowreader.app.util.LegacyTocAdapter.sectionIdOf(chapterIndex)
    }

    /**
     * 打开目录时按当前章**自动展开祖先**（主计划 7.2 第 4 条），但只在该书还没有
     * 任何用户显式展开记录时执行——否则会覆盖用户的手动折叠。
     */
    private fun ensureTocInitialExpansion() {
        val state = _uiState.value
        if (state.tocExpandedNodeIds.isNotEmpty()) return
        if (state.chapters.isEmpty()) return
        val tree = state.tocTree ?: com.flowreader.app.util.LegacyTocAdapter.toTocTree(
            publicationId = state.book?.publicationId ?: "book-${state.book?.id ?: 0}",
            revision = state.book?.activeRevision.orEmpty(),
            chapters = state.chapters
        )
        // 当前定位用该树自己的 section 坐标：真实树用清单 sectionId，
        // 兼容树用 section_<ordinal>。两者不能混用，否则高亮永远找不到节点。
        val currentSectionId = currentSectionIdFor(tree, state.currentChapterIndex)
        val location = com.flowreader.app.domain.model.TocLocation(sectionId = currentSectionId)
        val initial = com.flowreader.app.domain.model.TocOutline.initialExpanded(tree, location)
        if (initial.isNotEmpty()) {
            _uiState.update { it.copy(tocExpandedNodeIds = initial) }
        }
    }

    /**
     * 跳到搜索结果命中的**精确位置**（主计划第 9 节）。
     *
     * 先读取目标章并验证源偏移，再交给真实布局映射到页/滚动位置。
     * 源字符偏移不能作为页码或像素。调用方应刷新重新解析后的搜索结果。
     */
    fun goToSearchHit(chapterIndex: Int, offsetUtf16: Int) {
        val sequence = navigationSequence
        viewModelScope.launch {
            val state = _uiState.value
            val chapter = state.chapters.getOrNull(chapterIndex) ?: return@launch
            val content = try {
                chapterRepository.getChapterContentsForSearch(bookId, listOf(chapter.index))[chapter.index]
                    ?: throw IllegalStateException("章节正文不可用，请重新搜索")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "无法读取搜索命中：${e.localizedMessage ?: "正文不可用"}") }
                return@launch
            }
            if (sequence != navigationSequence || _uiState.value.isChapterSwitching) return@launch
            if (offsetUtf16 !in 0..content.length) {
                // 越界说明结果与当前正文不一致：如实退回章首，不猜位置。
                pendingRecoveryQuality = ResolutionQuality.APPROXIMATE
                pendingRecoveryReason = "SEARCH_OFFSET_OUT_OF_RANGE"
                goToChapter(chapterIndex, 0)
                return@launch
            }
            val locator = TextLocator(
                version = TextLocator.CURRENT_VERSION,
                publicationId = state.book?.publicationId ?: "legacy-$bookId",
                revision = state.book?.activeRevision ?: "legacy",
                normalizationVersion = 1,
                sectionId = "section_$chapterIndex",
                documentKey = "chapter:${chapter.index}",
                offsetUtf16 = offsetUtf16,
                affinity = Affinity.AFTER,
                context = ContentLocatorResolver.captureContext(content, offsetUtf16)
            )
            pendingScrollTarget = locator
            lastConfirmedLocator = locator
            pendingRecoveryQuality = ResolutionQuality.EXACT
            pendingRecoveryReason = "SEARCH_HIT"
            _uiState.update {
                it.copy(searchTarget = locator, chapters = it.chapters.mapIndexed { index, value ->
                    if (index == chapterIndex) value.copy(content = content) else value
                })
            }
            // A source character offset is neither a page number nor a scroll pixel.
            // The renderer resolves the locator using the measured layout before reporting progress.
            goToChapter(chapterIndex, 0)
        }
    }

    fun goToBookmark(bookmark: Bookmark) {
        if (bookmark.bookId != bookId) return
        val locator = bookmark.locator
        val locatorOrdinal = locator?.sectionId?.substringAfter(':')?.substringAfter('_')?.toIntOrNull()
        if (locator != null && (locatorOrdinal == null || locatorOrdinal == bookmark.chapterIndex)) {
            // 有权威内容坐标：走六级恢复，按**源偏移**定位（改字号后仍回到同一处内容，
            // 而不是同一个页号/像素）。恢复失败才回退旧字段。
            navigateToLocator(locator, bookmark.chapterIndex)
            _uiState.update { it.copy(showBookmarks = false) }
            return
        }
        // 旧数据（locator == null）：只能按旧整数回退，且**必须**如实标注质量，
        // 不得声称这是精确内容定位（主计划 5.4）。
        pendingRecoveryQuality = ResolutionQuality.APPROXIMATE
        pendingRecoveryReason = "LEGACY_BOOKMARK_" + bookmark.legacyUnit.name
        goToChapter(bookmark.chapterIndex, bookmark.position)
        _uiState.update { it.copy(showBookmarks = false) }
    }

    /**
     * 按权威 [TextLocator] 导航（主计划 6.2 GoTo 意图）。
     *
     * 先经六级恢复把 locator 求值到活动 revision；成功后按解析出的章与偏移跳转，
     * 并把结果写回 `pendingScrollTarget` 让滚动模式精准滚到锚点行（S5 接线）。
     */
    private fun navigateToLocator(locator: TextLocator, fallbackChapterIndex: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val book = state.book
            val chapterMetadata = state.chapters
            if (book == null || chapterMetadata.isEmpty()) return@launch

            val resolution = runCatching {
                resolveStoredLocator(locator, book, chapterMetadata)
            }.getOrNull()

            pendingRecoveryQuality = resolution?.quality ?: ResolutionQuality.UNRESOLVED
            pendingRecoveryReason = resolution?.reasonCode ?: "RESOLVE_FAILED"

            val resolved = resolution?.locator
            if (resolved == null) {
                // 六级全部失败：如实报告 UNRESOLVED，保留用户记录，回到该书第一正文起点。
                goToChapter(fallbackChapterIndex.coerceIn(0, chapterMetadata.size - 1), 0)
                return@launch
            }
            val ordinal = resolved.sectionId?.substringAfter(':')?.substringAfter('_')?.toIntOrNull()
                ?: resolved.documentKey.removePrefix("chapter:").toIntOrNull()
                ?: fallbackChapterIndex
            val index = ordinal.coerceIn(0, chapterMetadata.size - 1)
            pendingScrollTarget = resolved
            lastConfirmedLocator = resolved
            // Both renderers map this source anchor using their actual measured layout.
            _uiState.update { it.copy(searchTarget = resolved) }
            goToChapter(index, 0)
        }
    }

    /**
     * Keep the legacy page/pixel position for old-bookmark fallback. New bookmarks use the last
     * measured source locator; the renderer maps that locator after a font or mode change.
     * Without a measured locator, retain the legacy coordinate and mark it unresolved rather
     * than pretending that a page number is a source character offset.
     */
    fun addBookmark(text: String) {
        val state = _uiState.value
        val position = chapterPositions[state.currentChapterIndex] ?: state.currentPosition
        val bookmarkLocator = lastConfirmedLocator?.takeIf {
            it.sectionId == "section_${state.currentChapterIndex}" &&
                it.documentKey == "chapter:${state.currentChapter?.index}"
        }
        viewModelScope.launch {
            if (bookId <= 0L || state.currentChapterIndex !in state.chapters.indices) return@launch
            // 权威内容坐标（主计划第 9 节）：优先用滚动/恢复已确认的 locator；
            // 分页模式尚未产生 locator 时，用当前章内偏移构造一个，并如实标注质量。
            val bookmark = Bookmark(
                bookId = bookId,
                chapterIndex = state.currentChapterIndex,
                text = text,
                position = position.coerceAtLeast(0),
                locator = bookmarkLocator,
                locatorQuality = if (bookmarkLocator != null) {
                    ResolutionQuality.EXACT
                } else {
                    ResolutionQuality.UNRESOLVED
                },
                // 滚动模式的位置单位是像素；分页模式是页下标。如实记录，便于旧数据迁移判定。
                legacyUnit = if (positionIsPageIndex) {
                    LegacyPositionUnit.PAGE_INDEX
                } else {
                    LegacyPositionUnit.SCROLL_PX
                }
            )
            try {
                val savedBookmark = bookmarkRepository.addBookmark(bookmark)
                _uiState.update {
                    it.copy(bookmarks = (it.bookmarks + savedBookmark).sortedWith(bookmarkComparator), message = "书签已添加")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(message = "添加书签失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    /**
     * 把选择层的真实字符范围转成权威 [RangeLocator]（主计划第 9 节）。
     *
     * 约束：
     * - 范围必须是**开区间** `[start, endExclusive)`，与 `Annotation.endPosition` 的既有约定一致
     *   （F14：闭/开区间混用曾导致少一个字符）；
     * - 偏移必须落在本章正文长度内，越界即判失败（返回 null → UNRESOLVED），
     *   不能截断后假装精确；
     * - 空范围不得生成 locator。
     */
    private fun buildRangeLocatorForSelection(chapterIndex: Int, startPosition: Int, endPositionExclusive: Int): RangeLocator? {
        val state = _uiState.value
        val chapter = state.chapters.getOrNull(chapterIndex) ?: state.currentChapter ?: return null
        val length = chapter.content.length
        if (startPosition < 0 || endPositionExclusive <= startPosition || endPositionExclusive > length) return null
        val documentKey = "chapter:$chapterIndex"
        return RangeLocator(
            publicationId = state.book?.publicationId ?: "legacy-$bookId",
            revision = state.book?.activeRevision ?: "legacy",
            ranges = listOf(SourceRange(documentKey, startPosition, endPositionExclusive)),
            quality = ResolutionQuality.EXACT
        )
    }

    fun toggleTts() {
        if (!_uiState.value.speechEnabled) {
            _uiState.update { it.copy(message = "请先在设置 → 应用中开启听书") }
            return
        }
        val state = _uiState.value
        if (state.isTtsPlaying) {
            ttsCoordinator.pause()
            _uiState.update { it.copy(isTtsPlaying = false) }
            return
        }

        val chapter = state.currentChapter ?: return
        // PAGED / comic positions are page indices, so speaking "from position" would start a few
        // characters in; only a character offset is a meaningful start point.
        val start = if (positionIsPageIndex) 0 else state.currentPosition
        ttsCoordinator.speakFrom(chapter.content, start)
    }

    fun stopTts() {
        ttsCoordinator.stop()
        _uiState.update { it.copy(isTtsPlaying = false) }
    }

    fun toggleImmersiveMode() {
        _uiState.update { it.copy(isImmersiveMode = !it.isImmersiveMode) }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            if (bookmark.id <= 0L || bookmark.bookId != bookId) return@launch
            try {
                bookmarkRepository.deleteBookmarkById(bookmark.id)
                _uiState.update { it.copy(bookmarks = it.bookmarks.filter { it.id != bookmark.id }) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "删除书签失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    /**
     * 跨块选择 → 多区间批注（主计划第 9 节）。
     *
     * 与单区间 [addAnnotation] 的区别：这里落库的是 [MultiBlockSelection] 已经投影好的
     * **多段源范围**，因此：
     * - 跨段落选择不会把中间未选中的文字一并吞掉（范围保持分离）；
     * - 相接/重叠区间已合并，同一段文字不会既进 start/end 又进 ranges 而被重复输出。
     *
     * 旧的 `startPosition/endPosition` 仍按首个范围的起止写入，供尚未升级的读取路径
     *（导出、列表预览）保持兼容；`rangeLocator` 才是权威坐标。
     */
    fun addAnnotationRanges(
        selection: com.flowreader.app.ui.screens.reader.components.MultiBlockSelection,
        color: AnnotationColor = AnnotationColor.YELLOW,
        note: String = ""
    ) {
        if (selection.chapterIndex !in _uiState.value.chapters.indices) return
        viewModelScope.launch {
            val state = _uiState.value
            val publicationId = state.book?.publicationId ?: "legacy-$bookId"
            val revision = state.book?.activeRevision ?: "legacy"
            val locator = selection.toRangeLocator(publicationId, revision)
            val first = selection.ranges.first()
            val last = selection.ranges.last()
            val annotation = Annotation(
                bookId = bookId,
                chapterIndex = selection.chapterIndex,
                selectedText = selection.text,
                startPosition = first.startUtf16,
                endPosition = last.endUtf16Exclusive,
                color = color,
                note = note,
                rangeLocator = locator,
                locatorQuality = selection.quality
            )
            val newId = annotationRepository.insertAnnotation(annotation)
            _uiState.update { it.copy(annotations = it.annotations + annotation.copy(id = newId)) }
        }
    }

    /**
     * 跨块选择 → 书签（主计划第 9 节）。位置取**选择的首个真实源范围起点**，
     * 而不是当前视口位置——这正是不再"把字符位置传给旧像素接口"的落点。
     */
    fun addBookmarkRanges(selection: com.flowreader.app.ui.screens.reader.components.MultiBlockSelection) {
        if (selection.chapterIndex !in _uiState.value.chapters.indices) return
        viewModelScope.launch {
            val state = _uiState.value
            val publicationId = state.book?.publicationId ?: "legacy-$bookId"
            val revision = state.book?.activeRevision ?: "legacy"
            val meta = state.chapters.getOrNull(selection.chapterIndex) ?: return@launch
            val chapter = state.currentChapter?.takeIf { state.currentChapterIndex == selection.chapterIndex }
                ?: meta.takeIf { it.content.isNotEmpty() }
                ?: meta.copy(content = chapterRepository.getChapterContent(bookId, meta.index).orEmpty())
            val locator = TextLocator(
                version = TextLocator.CURRENT_VERSION,
                publicationId = publicationId,
                revision = revision,
                normalizationVersion = 1,
                sectionId = "section_${selection.chapterIndex}",
                documentKey = "chapter:${chapter.index}",
                offsetUtf16 = selection.firstRangeStartUtf16.coerceIn(0, chapter.content.length),
                affinity = Affinity.AFTER,
                context = ContentLocatorResolver.captureContext(
                    chapter.content,
                    selection.firstRangeStartUtf16.coerceIn(0, chapter.content.length)
                )
            )
            val bookmark = Bookmark(
                bookId = bookId,
                chapterIndex = selection.chapterIndex,
                text = selection.text.take(200).ifBlank { "书签" },
                position = selection.firstRangeStartUtf16,
                locator = locator,
                locatorQuality = selection.quality,
                legacyUnit = LegacyPositionUnit.SCROLL_PX
            )
            try {
                val saved = bookmarkRepository.addBookmark(bookmark)
                _uiState.update {
                    it.copy(bookmarks = (it.bookmarks + saved).sortedWith(bookmarkComparator), message = "书签已添加")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(message = "添加书签失败: ${e.localizedMessage ?: "未知错误"}") }
            }
        }
    }

    fun addAnnotation(text: String, start: Int, end: Int, color: AnnotationColor = AnnotationColor.YELLOW, note: String = "") {
        val state = _uiState.value
        viewModelScope.launch {
            // 选中文字的权威源范围（主计划第 9 节）：来自选择层的真实字符范围，
            // 直接落成 [start, end) 单区间；跨块选择由上层传入多条区间后走 addAnnotationRanges。
            val rangeLocator = buildRangeLocatorForSelection(state.currentChapterIndex, start, end)
            val annotation = Annotation(
                bookId = bookId,
                chapterIndex = state.currentChapterIndex,
                selectedText = text,
                startPosition = start,
                endPosition = end,
                color = color,
                note = note,
                rangeLocator = rangeLocator,
                locatorQuality = if (rangeLocator != null) {
                    ResolutionQuality.EXACT
                } else {
                    ResolutionQuality.UNRESOLVED
                }
            )
            val newId = annotationRepository.insertAnnotation(annotation)
            _uiState.update { it.copy(annotations = it.annotations + annotation.copy(id = newId)) }
        }
    }

    /** PDF region highlight (v55): the region is packed into start/end positions. */
    fun addPdfAnnotation(page: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        viewModelScope.launch {
            val state = _uiState.value
            val packedStart = com.flowreader.app.ui.screens.reader.components.PdfRegionCodec.pack(x0, y0)
            val packedEnd = com.flowreader.app.ui.screens.reader.components.PdfRegionCodec.pack(x1, y1)
            val annotation = Annotation(
                bookId = bookId,
                chapterIndex = page,
                startPosition = packedStart,
                endPosition = packedEnd,
                selectedText = "PDF 标注",
                color = AnnotationColor.YELLOW,
                note = ""
            )
            val newId = annotationRepository.insertAnnotation(annotation)
            _uiState.update { it.copy(annotations = it.annotations + annotation.copy(id = newId)) }
        }
    }

    fun deleteAnnotation(annotation: Annotation) {
        viewModelScope.launch {
            annotationRepository.deleteAnnotation(annotation)
            _uiState.update { it.copy(annotations = it.annotations.filter { it.id != annotation.id }) }
        }
    }

    fun updateAnnotationNote(id: Long, note: String) {
        viewModelScope.launch {
            val annotation = _uiState.value.annotations.find { it.id == id }
            annotation?.let {
                annotationRepository.updateAnnotation(it.copy(note = note))
                _uiState.update { state ->
                    state.copy(
                        annotations = state.annotations.map { a ->
                            if (a.id == id) a.copy(note = note) else a
                        }
                    )
                }
            }
        }
    }

    fun showSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
    }

    fun showBookmarks(show: Boolean) {
        _uiState.update { it.copy(showBookmarks = show) }
    }

    fun showAnnotations(show: Boolean) {
        _uiState.update { it.copy(showAnnotations = show) }
    }

    /**
     * Toggles the control layer.
     *
     * Debounced by [CONTROLS_TOGGLE_DEBOUNCE_MS] as a guard against a tap being delivered twice by
     * two overlapping gesture handlers: a doubled toggle is invisible to the user (the bar ends up
     * where it started) and is exactly the "single tap does nothing" report this fixes.
     */
    fun toggleControls() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastControlsToggleUptime < CONTROLS_TOGGLE_DEBOUNCE_MS) return
        lastControlsToggleUptime = now
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    fun toggleNightMode() {
        val currentSettings = _uiState.value.readingSettings
        val currentPalette = com.flowreader.app.core.designsystem.reader.ReaderPalettes.of(currentSettings.palette)
        val targetPalette = if (currentPalette.isDark) {
            com.flowreader.app.domain.model.ReaderPaletteId.PAPER
        } else {
            currentSettings.nightPalette
        }
        updateReadingSettings(currentSettings.copy(palette = targetPalette))
    }

    /**
     * Single entry point for every reader preference. The v51 ViewModel carried six near-identical
     * mutators; the settings sheet now hands back a whole [ReadingSettings].
     *
     * The in-memory state updates synchronously so the page previews live, but the DataStore write
     * is debounced: a single drag of the 字号 / 行距 / 段距 slider emits dozens of changes, and one
     * fsync-backed write per change is what made dragging feel like it was fighting the finger.
     */
    fun updateReadingSettings(settings: ReadingSettings) {
        val previousInterval = _uiState.value.readingSettings.eyeProtectionIntervalMinutes
        pendingReadingSettings = settings
        val generation = ++settingsSaveGeneration
        _uiState.update { it.copy(readingSettings = settings) }
        settingsSaveJob?.cancel()
        settingsSaveJob = viewModelScope.launch {
            try {
                delay(SETTINGS_SAVE_DEBOUNCE_MS)
                // Persist the requested snapshot, never a value overwritten by another emission.
                settingsRepository.updateReadingSettings(settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == settingsSaveGeneration) {
                    _uiState.update { it.copy(message = "阅读设置保存失败: ${e.localizedMessage ?: "未知错误"}") }
                }
            } finally {
                if (generation == settingsSaveGeneration) pendingReadingSettings = null
            }
        }
        if (settings.eyeProtectionIntervalMinutes != previousInterval) {
            startEyeProtectionTimer()
        }
    }

    /**
     * Imports a picked image as the reader background. Decoding and re-encoding happen off the main
     * thread; the resulting path is applied through [updateReadingSettings] so the background is
     * persisted by the same single entry point as every other reader preference.
     */
    fun onBackgroundImageSelected(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { backgroundImporter.import(uri) }
            result.fold(
                onSuccess = { path ->
                    // The importer prunes older files, so the previous background is already gone.
                    updateReadingSettings(_uiState.value.readingSettings.copy(backgroundImagePath = path))
                },
                onFailure = { error ->
                    // Keep the existing background on failure rather than clearing to a blank state.
                    _uiState.update { it.copy(message = "导入背景图失败: ${error.message}") }
                }
            )
        }
    }

    /** Removes the reader background image and deletes the stored file. */
    fun clearBackgroundImage() {
        val current = _uiState.value.readingSettings
        val path = current.backgroundImagePath
        updateReadingSettings(current.copy(backgroundImagePath = null))
        viewModelScope.launch(Dispatchers.IO) { backgroundImporter.clear(path) }
    }

    /**
     * Imports a picked `.ttf` / `.otf` and switches the reader to it immediately, so the settings
     * sheet preview and the page body update without leaving the reader.
     */
    fun onCustomFontSelected(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { fontImporter.import(uri) }
            result.fold(
                onSuccess = { path ->
                    updateReadingSettings(_uiState.value.readingSettings.copy(customFontPath = path))
                    _uiState.update { it.copy(message = "已导入字体") }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(message = "导入字体失败: ${error.message}") }
                }
            )
        }
    }

    /** Drops the imported font and falls back to the selected built-in face. */
    fun clearCustomFont() {
        val current = _uiState.value.readingSettings
        val path = current.customFontPath ?: return
        updateReadingSettings(current.copy(customFontPath = null))
        viewModelScope.launch(Dispatchers.IO) { fontImporter.clear(path) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /** Jumps to whichever chapter the reader progress slider was released over. */
    fun goToProgress(fraction: Float) {
        val state = _uiState.value
        if (state.chapters.isEmpty()) return
        // 滑杆逆映射同样按权重（主计划 10.2 末段）：拖到 10% 在 100/900 字的书里应落到第二章，
        // 旧实现按章节数反推会落在第一章。
        val index = progressIndex
        val target = if (index.isReady) {
            index.sectionAt(fraction.toDouble())
        } else {
            progressEngine.chapterAt(fraction, state.chapters.size)
        }
        goToChapter(target.coerceIn(0, state.chapters.size - 1))
    }

    fun shareProgress() {
        val state = _uiState.value
        val book = state.book ?: return
        val chapter = state.currentChapter ?: return

        val progress = (weightedFraction(state.currentChapterIndex, chapterFraction) * 100).roundToInt()

        val shareText = "📚 正在阅读《${book.title}》\n" +
            "第 ${state.currentChapterIndex + 1} 章：${chapter.title}\n" +
            "进度：$progress%\n\n" +
            "#一卷"

        _uiState.update { it.copy(shareText = shareText) }
    }

    fun clearShareText() {
        _uiState.update { it.copy(shareText = null) }
    }

    private companion object {
        const val MAX_PREPARED_CHAPTERS = 4
        val bookmarkComparator = compareBy<Bookmark> { it.chapterIndex }
            .thenBy { it.position }
            .thenByDescending { it.createdTime.time }

        const val CONTROLS_TOGGLE_DEBOUNCE_MS = 80L
        const val SETTINGS_SAVE_DEBOUNCE_MS = 350L
    }
}
