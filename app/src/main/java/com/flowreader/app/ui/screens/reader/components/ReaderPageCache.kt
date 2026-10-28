package com.flowreader.app.ui.screens.reader.components

import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.ReadingSettings
import com.flowreader.app.util.epub.ChapterImageRef
import com.flowreader.app.util.epub.ChapterMarks
import com.flowreader.feature.reader.ChapterPaginator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A renderable chapter in the bounded, continuous pager window. Text is shared with CacheManager. */
data class ReaderChapterPages(
    val chapterIndex: Int,
    val chapter: Chapter,
    val pages: List<List<ChapterPaginator.Fragment>>,
    val images: List<ChapterImageRef>,
    val marks: ChapterMarks,
    val bodyShift: Int
)

/** Stable identity survives prepending and evicting chapters from the pager window. */
data class ReaderPageAddress(val chapterIndex: Int, val pageIndex: Int) {
    val key: String get() = "$chapterIndex:$pageIndex"

    companion object {
        fun fromKey(key: Any?): ReaderPageAddress? {
            val parts = (key as? String)?.split(':') ?: return null
            if (parts.size != 2) return null
            val chapter = parts[0].toIntOrNull() ?: return null
            val page = parts[1].toIntOrNull() ?: return null
            return ReaderPageAddress(chapter, page)
        }
    }
}

/** A missing/empty neighbour is a boundary, never permission to jump over it. */
fun readerWindowPages(
    chapters: Collection<ReaderChapterPages>,
    anchorChapter: Int = chapters.minOfOrNull { it.chapterIndex } ?: 0
): List<ReaderPageAddress> {
    val available = chapters.filter { it.pages.isNotEmpty() }.associateBy { it.chapterIndex }
    if (anchorChapter !in available) return emptyList()
    var first = anchorChapter
    var last = anchorChapter
    while (first - 1 in available) first--
    while (last + 1 in available) last++
    return (first..last).flatMap { index ->
        available.getValue(index).pages.indices.map { ReaderPageAddress(index, it) }
    }
}

/**
 * Identity of one typeset chapter.
 *
 * Typography is the expensive part of opening a chapter in `PAGED` mode: every block is measured
 * with the live reader styles, and a block taller than a page is split by binary search over raw
 * text length. The result only depends on the chapter text, the styles and the page geometry, so it
 * is worth keeping and it is unsafe to reuse when any of those differ.
 *
 * [contentHash] plus [contentLength] stand in for the text itself: keeping the whole chapter (and
 * therefore the pages) keyed by the string would retain a second copy of the text for every cached
 * chapter. A hash collision would have to also match length, title, chapter index and book, which
 * cannot happen for a real book.
 */
data class ReaderPageKey(
    val bookId: Long,
    val chapterIndex: Int,
    val contentLength: Int,
    val contentHash: Int,
    val title: String,
    val settings: ReadingSettings,
    /** Stable key for the resolved font family — an imported font changes every measurement. */
    val fontFamilyKey: String,
    val widthPx: Int,
    val heightPx: Int,
    val gapPx: Int,
    /**
     * 本章结构图片的指纹（偏移 + 解析结果）。S5 之前这里没有图片，页列表与图片无关；
     * 现在**有图与无图必须分成两个键**，否则会复用一个「少了几张图」的旧排版结果。
     */
    val imageKey: String,
    /**
     * 本章呈现标注的指纹（schema v3）：列表项的编号/层级/前缀、表头、注音。
     * 有标注与无标注、编号不同都必须是两份排版缓存（与 [imageKey] 同一纪律）。
     */
    val marksKey: String,
    val contentFingerprint: String = "",
    val environmentKey: String = ""
)

/**
 * Small LRU of already-typeset chapters, shared across chapter switches.
 *
 * Two problems it solves, both reported as "跨章节很卡":
 *
 * 1. `ReaderScreen` re-creates the reader for every navigation (a new `scrollRequestVersion`), so
 *    all remembered state — including the page list — was thrown away and re-measured from
 *    scratch. Hoisting the result here makes returning to a chapter instant.
 * 2. Because the page list was re-measured from an empty initial value, the pager briefly had no
 *    pages at all and rendered "本章暂无内容" on top of a chapter that was perfectly fine.
 *
 * Only [DEFAULT_MAX_ENTRIES] chapters are retained; entries are a few hundred KB at most, and the
 * least recently used one is dropped first.
 */
/** What the pager should draw for the current pagination result. */
enum class PageRenderState {
    /** No result yet — the chapter is being measured. Must never be shown as "empty". */
    TYPESETTING,

    /** Measured, and there is genuinely nothing to draw. */
    EMPTY,

    /** Ready to page through. */
    PAGES
}

/**
 * Single source of truth for "not measured yet" vs "measured as empty".
 *
 * The pager used to hold `emptyList()` as its initial value, which made those two states
 * indistinguishable: every chapter change re-created the reader, the page list restarted empty, and
 * the reader told the user "本章暂无内容" for as long as re-measuring took.
 */
fun pageRenderState(pages: List<List<ChapterPaginator.Fragment>>?): PageRenderState = when {
    pages == null -> PageRenderState.TYPESETTING
    pages.isEmpty() -> PageRenderState.EMPTY
    else -> PageRenderState.PAGES
}

interface ReaderPageStore {
    fun get(key: ReaderPageKey): List<List<ChapterPaginator.Fragment>>?
    fun put(key: ReaderPageKey, pages: List<List<ChapterPaginator.Fragment>>)
}

/** Only geometry-affecting settings belong in a typesetting identity. */
internal fun paginationSettings(settings: ReadingSettings): ReadingSettings = ReadingSettings().copy(
    fontSize = settings.fontSize, lineSpacing = settings.lineSpacing,
    paragraphSpacing = settings.paragraphSpacing, firstLineIndent = settings.firstLineIndent,
    fontFamily = settings.fontFamily, customFontPath = settings.customFontPath,
    horizontalMarginDp = settings.horizontalMarginDp, lineChars = settings.lineChars,
    textAlign = settings.textAlign, chineseConversion = settings.chineseConversion
)

internal fun contentFingerprint(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

class ReaderPageCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val store: ReaderPageStore? = null,
    private val persistenceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val previews = mutableMapOf<ReaderPageKey, MutableMap<Int, List<ChapterPaginator.Fragment>>>()
    private val _previewVersion = MutableStateFlow(0L)
    val previewVersion: StateFlow<Long> = _previewVersion

    @Synchronized
    fun preview(key: ReaderPageKey, index: Int): List<ChapterPaginator.Fragment>? =
        entries[key]?.getOrNull(index) ?: previews[key]?.get(index)

    @Synchronized
    fun publishPreview(key: ReaderPageKey, index: Int, fragments: List<ChapterPaginator.Fragment>) {
        if (pending[key]?.isActive != true) return
        previews.getOrPut(key) { mutableMapOf() }[index] = fragments
        _previewVersion.value++
    }

    private val pending = mutableMapOf<ReaderPageKey, Deferred<List<List<ChapterPaginator.Fragment>>>>()
    private val computationMutex = Mutex()
    private var generation = 0L

    private val entries = object : LinkedHashMap<ReaderPageKey, List<List<ChapterPaginator.Fragment>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReaderPageKey, List<List<ChapterPaginator.Fragment>>>?): Boolean =
            size > maxEntries
    }

    /** Pages for [key], or null when that chapter has not been typeset at this geometry yet. */
    @Synchronized
    fun get(key: ReaderPageKey): List<List<ChapterPaginator.Fragment>>? = entries[key]

    @Synchronized
    fun put(key: ReaderPageKey, pages: List<List<ChapterPaginator.Fragment>>) {
        entries[key] = pages
    }

    /** The screen owns the work: leaving a chapter cancels the waiter, not its pre-typesetting. */
    suspend fun getOrCompute(
        key: ReaderPageKey,
        scope: CoroutineScope,
        foreground: Boolean = true,
        compute: suspend () -> List<List<ChapterPaginator.Fragment>>
    ): List<List<ChapterPaginator.Fragment>> {
        val work = synchronized(this) {
            entries[key]?.let { return it }
            if (foreground) {
                // A TOC/font change must not queue behind a different chapter's long layout.
                // Keep matching work so entering a prefetched chapter still shares its result.
                pending.filterKeys { it.bookId == key.bookId && it != key }.values.toList().forEach { it.cancel() }
            }
            if (pending[key]?.isCancelled == true) pending.remove(key)
            pending.getOrPut(key) {
                val startedIn = generation
                scope.async(start = CoroutineStart.LAZY) {
                    // TextMeasurer's internal cache is shared by a chapter's measurements.
                    // Keep prefetch and foreground misses from mutating it concurrently.
                    val restored = if (store == null) null else withContext(Dispatchers.IO) { runCatching { store.get(key) }.getOrNull() }
                    val pages = restored ?: computationMutex.withLock { compute() }
                    synchronized(this@ReaderPageCache) {
                        if (generation == startedIn) {
                            entries[key] = pages
                            previews.remove(key)
                            _previewVersion.value++
                        }
                    }
                    if (restored == null && store != null) {
                        persistenceScope.launch { runCatching { store.put(key, pages) } }
                    }
                    pages
                }.also { deferred ->
                    deferred.invokeOnCompletion {
                        synchronized(this@ReaderPageCache) {
                            if (pending[key] === deferred) {
                                pending.remove(key)
                                previews.remove(key)
                                _previewVersion.value++
                            }
                        }
                    }
                }
            }
        }
        work.start()
        return work.await()
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() {
        generation++
        entries.clear()
        previews.clear()
        _previewVersion.value++
        val jobs = pending.values.toList()
        pending.clear()
        jobs.forEach { it.cancel() }
    }

    companion object {
        /**
         * Enough for the visible chapter, both neighbours on either side of a few turns, and the
         * one just left. Larger than this only helps pathological back-and-forth scrolling.
         */
        const val DEFAULT_MAX_ENTRIES = 6
    }
}
