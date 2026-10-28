package com.flowreader.app.domain.model

/**
 * 结构化正文合同（主计划 4.5、3.2、2.1）。
 *
 * 这一层的存在理由：旧导入把 EPUB 正文**不可逆地压成纯文本**（F09 `htmlToFormattedText`），
 * 粗体、斜体、链接目标、列表、引用、表格结构在导入阶段就丢了；图片退化成
 * `[IMG:absolutePath]` 字符串标记（F10），渲染分支只能靠字符串猜。
 *
 * 因此本文件定义的是**内容层的合同**，与"当前屏幕上的这一页"无关：
 * - [StructuredDocument] 是一个 XHTML 文档（或 TXT 的虚拟文档）的结构化结果，带稳定源坐标；
 * - `blocks` 是规范阅读顺序上的结构块，每个块持有自己在**规范源文本**里的 UTF-16 范围；
 * - `text` 是规范源文本 v1（3.2 的十条规则），所有定位、统计、听书、搜索都以它为唯一基准。
 *
 * 不变量：
 * - INV02：同一原始文件、同一规范化版本重复解析，块 ID / 锚点 / 源范围必须逐字一致
 *   （所以块 ID 由 documentKey + DOM 结构路径生成，**禁止随机 UUID**）。
 * - INV03：所有源范围是 UTF-16 边界 `[start, endExclusive)`。
 * - INV04：不得落在代理对中间。
 * - INV05：显示转换不得修改 [SourceDocument.text]。
 *
 * 本文件不引用任何 Android 类型。
 */
data class StructuredDocument(
    /** 解引用后相对于容器根目录的规范路径；TXT 固定 `text/main`。 */
    val documentKey: String,
    /** 规范源文本 v1。所有偏移都以它为准。 */
    val text: String,
    /** 按源顺序排列的结构块。 */
    val blocks: List<ContentBlock>,
    /** 元素 id / xml:id / `a[name]` → 源偏移。重复 id 保留诊断并取文档顺序第一个。 */
    val anchorIndex: Map<String, AnchorTarget>,
    /** 文档内出现的资源引用（图片、链接），已按引用它的文档解析成容器路径。 */
    val resourceRefs: List<ResourceReference>,
    /** 主文本有效字数：非空白 Unicode code point 数，排除 U+FFFC、辅助 role 与生成分隔符。 */
    val mainTextUnits: Long,
    val normalizationVersion: Int = CURRENT_NORMALIZATION_VERSION,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** 结构性问题（非法范围消解、重复 id、不支持的标签等），**不允许静默丢弃**。 */
    val diagnostics: List<DocumentDiagnostic> = emptyList(),
    /**
     * 本文档内**每一个**图片对象，按源顺序（4.6：图片是结构对象）。
     *
     * 为什么要单独留一份：[blocks] 只保留「整块就是一张图」的块级图片；段落**内部**的
     * `<p>文字<img/>文字</p>` 只会在文本里留一个 U+FFFC，图片对象本身进不了 blocks。
     * 没有这份清单，就再也说不清「哪个偏移是哪张图」，插图在渲染层无图可画。
     */
    val images: List<ImageBlock> = emptyList(),
    /**
     * 本文档内每个**列表项**的呈现标注（schema v3）。与 [images] 同一条链路：
     * 规范正文里没有列表标记（规则 4：自动编号不参与文本），项目符号/编号只能由**结构**推导。
     * 每项携带它所在列表的有序性、起始值与嵌套深度，渲染层据此注入显示前缀。
     */
    val lists: List<ListMark> = emptyList(),
    /**
     * 本文档内每个表格的结构标注（schema v3）。正文里单元格之间只有 TAB，
     * 表头/跨行跨列信息只在 [TableBlock] 里；这里按唯一创建点平铺，供渲染层使用。
     */
    val tables: List<TableMark> = emptyList(),
    /**
     * 本文档内每个注音（ruby）的基字范围与注音文本（schema v3）。
     * 注音**不进主文本**（3.2 第 6 条），正文与字数口径都不变；这里只留显示层的注音。
     */
    val ruby: List<RubyMark> = emptyList()
) {
    /** UTF-16 长度，与 [text] 的定位单位一致。 */
    val textLengthUtf16: Int get() = text.length

    companion object {
        /** 规范源文本 v1（主计划 3.2）。改变生成规则必须递增它。 */
        const val CURRENT_NORMALIZATION_VERSION = 1

        /**
         * 结构块模型版本。
         *
         * v3：文档 JSON 增加 `lists` / `tables` / `ruby` 三个数组（列表项呈现标注、表格结构、
         * 注音显示层，见 4.5 与 NEXT-BATCH 规格）。规范正文里**没有**这三类语义
         * （列表无标记、表格只有 TAB、注音不进正文），不持久化它们渲染层就永远无米下锅。
         *
         * v2：文档 JSON 增加 `images`（每个图片对象的 U+FFFC 偏移 + 资源 + alt，见 4.6）。
         * v1 只把文档写成了纯文本 + blocks，**说不清哪个偏移是哪张图**，于是渲染层无图可画。
         *
         * [BookParser] 把本值算进 revision，所以升级本值后**重新导入同一本书**会得到一个新
         * revision（旧文件按 5.3 保留不覆盖），这正是既有书拿到图片与标注的路径。
         */
        const val CURRENT_SCHEMA_VERSION = 3
    }
}

/** 结构块的带类型联合（主计划 4.5 的表格逐行对应）。 */
sealed interface ContentBlock {
    /** 稳定块身份：由 documentKey + DOM 结构路径生成，不用随机值、不用分页序号。 */
    val blockId: String

    /** 本块在规范源文本中的范围。 */
    val range: SourceSpan

    /** 诊断用 DOM 来源路径（如 `body/div[2]/p[1]`），不参与定位。 */
    val domPath: String

    /** 本块是否属于辅助内容（脚注/尾注/非主线注释）。辅助内容不参与阅读进度统计。 */
    val role: BlockRole
}

/** 块在阅读计量中的地位。 */
enum class BlockRole {
    /** 正常主线正文（含真实章标题、卷序、正文表格）。 */
    MAIN,

    /** 辅助注释：文字保留源坐标，但不计正文权重（4.5 Footnote/Aside、3.2 第 7 条）。 */
    AUXILIARY
}

/** 基础段落。 */
data class ParagraphBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    override val role: BlockRole = BlockRole.MAIN,
    val spans: List<InlineSpan> = emptyList(),
    /** 原始元素 id（`id` / `xml:id`），不存在为 null。 */
    val elementId: String? = null
) : ContentBlock

/** 标题。level 取 1..6（XHTML 的 h1—h6）。 */
data class HeadingBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    val level: Int,
    override val role: BlockRole = BlockRole.MAIN,
    val spans: List<InlineSpan> = emptyList(),
    val elementId: String? = null
) : ContentBlock

/** 正文内图片。图片是结构对象，不是文本标记（4.6）。 */
data class ImageBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    /** 已解析的包内目标；外部/被拒的引用保留原文并带错因。 */
    val reference: ResourceReference,
    /** 该图在规范源文本中占据的 U+FFFC 偏移；纯块级图片可能没有对象字符。 */
    val objectOffsetUtf16: Int? = null,
    /**
     * `img/@alt`。**不进主文本也不进字数统计**（3.2 第 5 条），但要随结构一起持久化：
     * 资源缺失/解码失败时占位必须显示它（4.6 第 9 条），否则用户只看到一张空白。
     */
    val alt: String? = null,
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

/** 图（figure）：图片 + 图注。 */
data class FigureBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    val image: ImageBlock?,
    /** 图注文字与它的源范围；无 caption 时为 null。 */
    val caption: String?,
    val captionRange: SourceSpan?,
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

/** 列表。 */
data class ListBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    val ordered: Boolean,
    /** `ol/@start`；无序列表为 null。 */
    val start: Int?,
    val items: List<ListItem>,
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

/** 列表项。[value] 对应 `li/@value`（只对有序列表有意义）。 */
data class ListItem(
    val blockId: String,
    val range: SourceSpan,
    val value: Int?,
    /** 嵌套层级，从 1 开始。 */
    val level: Int,
    val spans: List<InlineSpan> = emptyList(),
    val children: List<ContentBlock> = emptyList()
)

/** 引用块。 */
data class QuoteBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    /** 引用层级，从 1 开始（blockquote 嵌套）。 */
    val level: Int,
    val children: List<ContentBlock> = emptyList(),
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

/** 表格。跨行跨列必须保留，复杂嵌套表格允许降级但不能丢数据（4.5）。 */
data class TableBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    val rows: List<TableRow>,
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

data class TableRow(val cells: List<TableCell>)

data class TableCell(
    val blockId: String,
    val range: SourceSpan,
    /** 表头单元格（`th`）。 */
    val isHeader: Boolean,
    val rowSpan: Int = 1,
    val colSpan: Int = 1,
    /** 单元格内文本（保留单元格内的显式段落换行）。 */
    val text: String,
    val spans: List<InlineSpan> = emptyList()
)

/** 注音：主坐标仍是基字，注音不进正文统计（3.2 第 6 条）。 */
data class RubyBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    val base: String,
    /** `rt` 的注音内容，可能多段。 */
    val annotations: List<String>,
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

/** 预格式块：换行与空白必须保留。 */
data class PreformattedBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    val text: String,
    val spans: List<InlineSpan> = emptyList(),
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

/**
 * 显式兼容占位：无法表达的标签保留原标签名、原文引用与替代文字，并提供诊断。
 * **不允许**把"保留源资源"说成"已正确渲染"（4.5 末段）。
 */
data class UnsupportedBlock(
    override val blockId: String,
    override val range: SourceSpan,
    override val domPath: String,
    /** 原标签名，如 `svg`、`math`、`object`。 */
    val tagName: String,
    /** 可用的替代文字（alt / title / 纯文本后代），可能为空串。 */
    val fallbackText: String,
    /** 涉及的源资源引用（如果有）。 */
    val reference: ResourceReference? = null,
    override val role: BlockRole = BlockRole.MAIN,
    val elementId: String? = null
) : ContentBlock

// ------------------------------------------------------------------ 呈现标注（schema v3）

/**
 * 一个列表项的呈现标注。规范正文里没有列表标记（3.2 规则 4：自动编号不参与文本），
 * 项目符号/编号只能由**结构位置**推导 —— 所以在唯一创建点把结构信息平铺下来。
 *
 * 编号规则（渲染层推导，**绝不写进正文**）：`itemValue`（`li/@value`）优先；
 * 否则 = `listStart`（默认 1）+ 同一列表内前面同层项的个数。
 * 编号来自结构而不是页码，天然满足「长列表续页编号不重置」。
 */
data class ListMark(
    /** 所属 [ListBlock] 的 blockId：同一列表的项靠它分组编号。 */
    val listBlockId: String,
    /** 该项自己的 blockId（[ListItem.blockId]）。 */
    val blockId: String,
    val domPath: String,
    /** 该项首字符在**本文档**规范源文本中的 UTF-16 偏移（[ListItem.range] 起点）。 */
    val offsetUtf16: Int,
    val ordered: Boolean,
    /** `ol/@start`；无值（含无序列表）为 null，推导时按 1 处理。 */
    val listStart: Int?,
    /**
     * 显示嵌套深度，从 1 开始（按 DOM 里 `ul/ol` 祖先数推导）。
     * 注意与 [ListItem.level]（"本列表内层级"恒为 1）语义不同。
     */
    val level: Int,
    /** `li/@value`，只对有序列表有意义。 */
    val itemValue: Int?,
    val role: BlockRole = BlockRole.MAIN
)

/** 一个表格的结构标注：单元格源范围 + 表头/跨行跨列信息（正文里只有 TAB 分隔）。 */
data class TableMark(
    val blockId: String,
    val domPath: String,
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
    val rows: List<TableRowMark>,
    val role: BlockRole = BlockRole.MAIN
)

data class TableRowMark(val cells: List<TableCellMark>)

data class TableCellMark(
    val blockId: String,
    /** 单元格文本在**本文档**规范源文本中的 UTF-16 范围（不含前置 TAB）。 */
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
    val isHeader: Boolean,
    val rowSpan: Int = 1,
    val colSpan: Int = 1
)

/**
 * 一个注音的显示标注：基字源范围 + 注音文本。
 * 基字在正文里原样保留（坐标与字数口径都不动）；注音只活在显示层（3.2 第 6 条）。
 */
data class RubyMark(
    val blockId: String,
    val domPath: String,
    val baseStartUtf16: Int,
    val baseEndUtf16Exclusive: Int,
    val annotations: List<String>,
    val role: BlockRole = BlockRole.MAIN
)

// ------------------------------------------------------------------ 行内语义

/**
 * 行内 span。范围是**规范源文本**的范围，链接目标与 `noteref` 单独保存，
 * 不再像 F09 那样把语义压成文本标记。
 */
data class InlineSpan(
    val kind: InlineSpanKind,
    val range: SourceSpan,
    /** 嵌套深度，从 1 开始；`<strong><em>` 时 em 为 2。 */
    val depth: Int = 1,
    /** 链接/注音引用的已解析目标；非链接类为 null。 */
    val target: ResourceReference? = null,
    /**
     * 不进主文本的附属文字：`ruby` 的 `rt/rp` 注音（3.2 第 6 条）。
     * 它带自己的语义与坐标来源，但**不参与字数统计**，也不能让主坐标偏离基字。
     */
    val annotations: List<String> = emptyList()
)

enum class InlineSpanKind {
    STRONG,
    EM,
    CODE,
    LINK,

    /** 脚注引用（`epub:type` 含 noteref，或 `a` 指向脚注容器）。 */
    NOTEREF,
    RUBY_BASE,
    RUBY_TEXT,
    SUPERSCRIPT,
    SUBSCRIPT,
    STRIKETHROUGH,
    UNDERLINE,
    SMALL_CAPS,
    MARK
}

// ------------------------------------------------------------------ 资源引用

/**
 * 文档里的一个资源引用（图片/链接）。**保留原始引用字符串**，便于诊断回显。
 */
data class ResourceReference(
    /** 原始 href / src 属性值。 */
    val raw: String,
    /** 解析结果：包内 / 外部 / 被拒。 */
    val target: ResourceTargetKind,
    /** 包内目标：规范容器路径；外部或被拒时为 null。 */
    val containerPath: String? = null,
    /** 已单独解码一次的 fragment，未提供为 null。 */
    val fragment: String? = null,
    /** 外部目标的 scheme，如 `https`。 */
    val scheme: String? = null,
    /** 被拒/不支持的错因。 */
    val errorCode: String? = null,
    /** 外部或包内目标都不成立时的原样说明。 */
    val detail: String? = null
)

enum class ResourceTargetKind {
    /** 包内资源，[ResourceReference.containerPath] 有效。 */
    INTERNAL,

    /** 外部目标，只识别不加载。 */
    EXTERNAL,

    /** 被拒绝（危险 scheme、越界、非法编码等）。 */
    REJECTED
}

// ------------------------------------------------------------------ 锚点与诊断

/**
 * 锚点目标。[offsetUtf16] 一定是合法源边界（不落在代理对中间）。
 */
data class AnchorTarget(
    /** 原始 id 值（已按元素属性解码，保持大小写）。 */
    val id: String,
    val offsetUtf16: Int,
    /** 该锚点所属块，便于跳转时知道落在哪个块。 */
    val blockId: String
)

/** 文档级诊断。UI 只做中文映射，禁止各层散落字符串比较。 */
data class DocumentDiagnostic(
    val code: String,
    val detail: String,
    /** 相关 DOM 路径，便于定位源文件位置。 */
    val domPath: String? = null
)

/**
 * 源区间。与 [SourceRange] 的区别：这里是**文档内部**不变量检查用的值对象，
 * 允许为空区间（空元素 id、空 span），并额外携带块身份。
 */
data class SourceSpan(
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

    fun toSourceRange(): SourceRange = SourceRange(documentKey, startUtf16, endUtf16Exclusive)
}
