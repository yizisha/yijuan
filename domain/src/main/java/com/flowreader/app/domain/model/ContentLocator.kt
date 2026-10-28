package com.flowreader.app.domain.model

/**
 * 内容定位合同（主计划 3.1 / INV01—INV04）。
 *
 * 这一层的唯一目的：让“用户读到哪儿了”不再由**页码或像素**表达，而是由一个与屏幕、字体、
 * 排版模式无关的**源坐标**表达。旧实现把 PAGE_INDEX 与 SCROLL_PX 塞进同一个 `Book.currentPosition: Int`
 * （见 F01），导致改字号、换字体、翻转屏后无法回到同一处内容。
 *
 * 单位约定（不可混用）：
 * - [TextLocator.offsetUtf16] 是**规范源文本**的 UTF-16 边界，范围 `0..textLengthUtf16`。
 * - 页下标、滚动像素只允许存在于布局层的临时状态里（`LayoutState` / `layoutKey` 缓存）。
 * - 本文件不引入任何 Android 类型，纯数据合同。
 */
data class TextLocator(
    val version: Int = CURRENT_VERSION,
    /** 首次入库生成 UUID；移动文件或重新解析都不改变，SQLite bookId 只是本机关联。 */
    val publicationId: String,
    /** 原始文件 SHA-256 + 规范化版本 + parserSchemaVersion 的确定性摘要。 */
    val revision: String,
    val normalizationVersion: Int = 1,
    /** 逻辑章节稳定 ID，仅作提示与目录选中依据；定位以 [documentKey] + [offsetUtf16] 为准。 */
    val sectionId: String? = null,
    /** EPUB 解引用后相对容器根目录的规范路径；TXT 固定 `text/main`。 */
    val documentKey: String,
    val offsetUtf16: Int,
    /** 边界落在两个块/页之间时归属前侧还是后侧；文档末尾使用 [Affinity.BEFORE]。 */
    val affinity: Affinity = Affinity.AFTER,
    val blockId: String? = null,
    val elementId: String? = null,
    val context: TextContext = TextContext.EMPTY
) {
    init {
        require(offsetUtf16 >= 0) { "offsetUtf16 must not be negative: $offsetUtf16" }
        require(documentKey.isNotBlank()) { "documentKey must not be blank" }
        require(publicationId.isNotBlank()) { "publicationId must not be blank" }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** [TextLocator] 附近的原文，用于修订后按上下文重新定位；最多各 32 个 code point，不跨文档。 */
data class TextContext(val before: String, val after: String) {
    companion object {
        val EMPTY = TextContext("", "")
        const val MAX_CODE_POINTS = 32
    }
}

/** 边界归属：`AFTER` 表示该偏移属于后一个块/页的首字符。 */
enum class Affinity { BEFORE, AFTER }

/**
 * 一次定位解析的可信度。UI 必须据此给出可见提示，**不得**把 APPROXIMATE 当成精确位置，
 * 也不得让批注自动贴到近似范围（主计划 3.4 第 5 条）。
 */
enum class ResolutionQuality {
    /** 同 revision、同 documentKey、范围有效。 */
    EXACT,

    /** revision 已变，但靠 elementId + context 唯一命中。 */
    CONTEXT_MATCH,

    /** 只辨认出 section，定位到其开头；原 locator 保留。 */
    APPROXIMATE,

    /** 完全无法解析；保留原记录，展示“定位待恢复”。 */
    UNRESOLVED
}

/**
 * 源范围，UTF-16 边界 `[startUtf16, endUtf16Exclusive)`（INV03）。
 * 跨文档的范围由多个 [SourceRange] 的有序集合表达，不用单章全局偏移猜测。
 */
data class SourceRange(
    val documentKey: String,
    val startUtf16: Int,
    val endUtf16Exclusive: Int
) {
    init {
        require(startUtf16 >= 0) { "startUtf16 must not be negative: $startUtf16" }
        require(endUtf16Exclusive >= startUtf16) {
            "endUtf16Exclusive ($endUtf16Exclusive) must be >= startUtf16 ($startUtf16)"
        }
    }

    val lengthUtf16: Int get() = endUtf16Exclusive - startUtf16
    val isEmpty: Boolean get() = endUtf16Exclusive == startUtf16

    fun intersects(other: SourceRange): Boolean {
        return documentKey == other.documentKey &&
            startUtf16 < other.endUtf16Exclusive &&
            other.startUtf16 < endUtf16Exclusive
    }
}

/** 解析结果：**只解析，不写当前位置**（主计划 12.3）。 */
data class LocatorResolution(
    val locator: TextLocator?,
    val quality: ResolutionQuality,
    val reasonCode: String? = null
) {
    val isUsable: Boolean get() = locator != null && quality != ResolutionQuality.UNRESOLVED
}

/**
 * 内容定位的带类型联合（主计划 3.1 末段）。
 *
 * PDF 与漫画只走 [FixedPage]，它们的页码是**固定页序**而不是字符位置，正文统计与固定页统计
 * 分开计算，不允许把页码伪装成字符偏移。
 */
sealed interface ContentLocator {
    val publicationId: String
    val revision: String

    data class Text(val locator: TextLocator) : ContentLocator {
        override val publicationId: String get() = locator.publicationId
        override val revision: String get() = locator.revision
    }

    data class FixedPage(
        override val publicationId: String,
        override val revision: String,
        val pageId: String,
        val pageOrdinal: Int,
        /** 页内纵向比例 0..1，用于固定版式文档的粗略恢复。 */
        val verticalFraction: Float = 0f
    ) : ContentLocator
}

/**
 * 统一进度快照（主计划 10.1 / R3）。书架、详情页、页脚、widget 必须读同一份快照，
 * 不再各自用 `chapterIndex / totalChapters` 现算。
 */
data class ContentProgressSnapshot(
    /** 当前主线内容定位对应的累计文本占比，可前进也可后退。 */
    val currentContentProgress: Double,
    val visualReadMillis: Long = 0L,
    val ttsReadMillis: Long = 0L,
    val qualifiedTextUnits: Long = 0L,
    val settledTurns: Int = 0,
    /** 剩余分钟；样本不足时为 null（UI 显示“估算中”），不得伪造数值。 */
    val remainingMinutes: Int? = null,
    /** 进度分母索引尚未就绪时为 true，UI 显示“正在计算”。 */
    val isProgressIndexPending: Boolean = false
) {
    companion object {
        val UNKNOWN = ContentProgressSnapshot(
            currentContentProgress = 0.0,
            isProgressIndexPending = true
        )
    }
}

/**
 * 集中错误码（主计划第 16 节末）。UI 做中文映射，禁止在各层散落字符串比较。
 */
enum class ContentErrorCode {
    INVALID_URI,
    PATH_ESCAPE,

    /**
     * 容器/包结构无效：不是 ZIP、中央目录损坏、重复 ZIP 条目、重复 manifest id 等。
     *
     * 计划 4.2 第 7 条要求「返回结构错误并停止激活新 revision」，但错误码清单里没有给这一类
     * 名字；这里补一个稳定名字，避免实现侧退化成各层自行拼字符串。
     */
    CONTAINER_INVALID,
    DUPLICATE_RESOURCE,
    RESOURCE_MISSING,
    IMAGE_DECODE_FAILED,
    UNSUPPORTED_MEDIA,
    ANCHOR_NOT_FOUND,
    DUPLICATE_ANCHOR,
    LOCATOR_AMBIGUOUS,
    SOURCE_UNAVAILABLE,
    REVISION_MISMATCH,
    LAYOUT_CANCELLED,
    CONTENT_LIMIT_EXCEEDED,
    CONTENT_ACTIVATION_FAILED,
    BACKUP_VERSION_UNSUPPORTED
}
