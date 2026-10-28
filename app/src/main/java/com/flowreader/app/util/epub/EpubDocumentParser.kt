package com.flowreader.app.util.epub

import com.flowreader.app.domain.model.AnchorTarget
import com.flowreader.app.domain.model.BlockRole
import com.flowreader.app.domain.model.ContentBlock
import com.flowreader.app.domain.model.DocumentDiagnostic
import com.flowreader.app.domain.model.FigureBlock
import com.flowreader.app.domain.model.HeadingBlock
import com.flowreader.app.domain.model.ImageBlock
import com.flowreader.app.domain.model.InlineSpan
import com.flowreader.app.domain.model.InlineSpanKind
import com.flowreader.app.domain.model.ListBlock
import com.flowreader.app.domain.model.ListItem
import com.flowreader.app.domain.model.ListMark
import com.flowreader.app.domain.model.ParagraphBlock
import com.flowreader.app.domain.model.PreformattedBlock
import com.flowreader.app.domain.model.QuoteBlock
import com.flowreader.app.domain.model.ResourceReference
import com.flowreader.app.domain.model.ResourceTargetKind
import com.flowreader.app.domain.model.RubyBlock
import com.flowreader.app.domain.model.RubyMark
import com.flowreader.app.domain.model.SourceSpan
import com.flowreader.app.domain.model.StructuredDocument
import com.flowreader.app.domain.model.TableBlock
import com.flowreader.app.domain.model.TableCell
import com.flowreader.app.domain.model.TableMark
import com.flowreader.app.domain.model.TableRow
import com.flowreader.app.domain.model.TableRowMark
import com.flowreader.app.domain.model.TableCellMark
import com.flowreader.app.domain.model.TextUnits
import com.flowreader.app.domain.model.UnsupportedBlock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * XHTML → [StructuredDocument]（主计划 3.2 规范源文本 v1、4.5 结构化正文合同、8.1 锚点索引）。
 *
 * 替换的对象是旧 `BookParser.htmlToFormattedText`：那把粗体/斜体/链接/列表压成文本或 `[IMG:…]`
 * 标记（F09/F10），语义与链接目标在导入阶段就不可逆地丢了。
 *
 * 本类只做**纯解析**：不碰 ZIP I/O、不读 Android 资源、不落盘。资源身份由注入的
 * [ResourceResolver] 决定（生产实现会用 `EpubResourceResolver` + 容器字节算
 * `resourceId = SHA256(containerPath + contentHash)`），因此可以在 JVM 上直接测试。
 *
 * ## 规范源文本 v1 的十条规则（3.2）在本文件里的落点
 *
 * | 规则 | 落点 |
 * |---|---|
 * | 1 保留原始字节、按声明解码、失败点名文档 | 解码在 `EpubZipContainer.decodeText`；本类只接收已解码文本 |
 * | 2 实体解码、可折叠空白归一为一个 U+0020、`pre` 保留空白、CRLF/CR→LF、NBSP 不折叠 | `TextEmitter.emitText` + `normalizeLineEndings` + `COLLAPSIBLE_WHITESPACE` |
 * | 3 保留可见文字顺序/标点/大小写/字形，不 NFC、不简繁转换、不删"重复标题" | 全程原文直通，不做任何归一化 |
 * | 4 独立文本叶块后追加 LF（最后一个也追加）；容器不额外加分隔符；空块不生成空行；`br` 生成 LF；列表项文字参与、自动编号不参与；表格单元格间 TAB、行末 LF、单元格内不再加段尾 LF | `TextEmitter.endLeaf`/`emitLineFeed`/`emitTab` + 各 `emit*` 方法 |
 * | 5 图片/无文本原子对象占一个 U+FFFC，alt 进元数据不进字符计数 | `TextEmitter.emitObject` + [ImageBlock.objectOffsetUtf16] |
 * | 6 `ruby` 基字进主文本、`rt/rp` 进注音语义、不重复计算 | `emitInline` 的 `RUBY_*` 分支 + [RubyBlock] |
 * | 7 脚注保留源坐标与 AUXILIARY role，引用标记保留坐标但不计权重 | `noterefTargets` 两遍扫描 + [BlockRole.AUXILIARY] |
 * | 8 不绘制的 `script/style` 不进文本流，CSS 不参与 | `SKIPPED_TAGS` |
 * | 9 每个文本片段/块/元素 id 记录规范源范围与 DOM 路径 | [SourceSpan] + `InlineSpan.range` + `ContentBlock.domPath` |
 * | 10 TXT 换行统一（本类只处理 XHTML） | 由 TXT 路径负责 |
 */
object EpubDocumentParser {

    /** 输入上限（主计划 11.2：XML 嵌套 128）。元素数上限是 v1 约定，防止单文档拖垮导入。 */
    data class StructureLimits(
        val maxDepth: Int = 128,
        val maxElements: Int = 500_000
    )

    val DEFAULT_STRUCTURE_LIMITS = StructureLimits()

    /**
     * 资源引用解析器。[baseDocumentKey] 是引用它的文档路径，[rawHref] 是原始属性值。
     *
     * 生产实现必须走 `EpubResourceResolver`（4.2「只允许这一处实现路径逻辑」）；
     * 测试可以注入简单实现。
     */
    fun interface ResourceResolver {
        fun resolve(baseDocumentKey: String, rawHref: String): ResourceReference
    }

    /** 便捷构造：直接用 [EpubResourceResolver] 的判定结果。 */
    fun resolverFromPathLogic(): ResourceResolver = ResourceResolver { base, raw ->
        when (val target = EpubResourceResolver.resolve(base, raw)) {
            is EpubResourceResolver.ResourceTarget.Internal -> ResourceReference(
                raw = raw,
                target = ResourceTargetKind.INTERNAL,
                containerPath = target.containerPath,
                fragment = target.fragment
            )

            is EpubResourceResolver.ResourceTarget.External -> ResourceReference(
                raw = raw,
                target = ResourceTargetKind.EXTERNAL,
                scheme = target.scheme
            )

            is EpubResourceResolver.ResourceTarget.Rejected -> ResourceReference(
                raw = raw,
                target = ResourceTargetKind.REJECTED,
                errorCode = target.code.name,
                detail = target.detail
            )
        }
    }

    /** 规范源文本 v1 里代表"一个不含文本的可见原子对象"（图片等）的字符。 */
    const val OBJECT_REPLACEMENT: Char = '\uFFFC'

    /** 可折叠空白：NBSP 明确**不在**此列（3.2 第 2 条）。 */
    private val COLLAPSIBLE_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r', '\u000C')

    /** 完全不进文本流的标签（3.2 第 8 条）。 */
    private val SKIPPED_TAGS = setOf("script", "style", "head", "title", "meta", "link", "base")

    /** 语义行内标签 → span 种类。 */
    private val INLINE_SPAN_TAGS = mapOf(
        "strong" to InlineSpanKind.STRONG,
        "b" to InlineSpanKind.STRONG,
        "em" to InlineSpanKind.EM,
        "i" to InlineSpanKind.EM,
        "code" to InlineSpanKind.CODE,
        "samp" to InlineSpanKind.CODE,
        "kbd" to InlineSpanKind.CODE,
        "sup" to InlineSpanKind.SUPERSCRIPT,
        "sub" to InlineSpanKind.SUBSCRIPT,
        "s" to InlineSpanKind.STRIKETHROUGH,
        "strike" to InlineSpanKind.STRIKETHROUGH,
        "del" to InlineSpanKind.STRIKETHROUGH,
        "u" to InlineSpanKind.UNDERLINE,
        "ins" to InlineSpanKind.UNDERLINE,
        "mark" to InlineSpanKind.MARK
    )

    /** 结构化块标签：出现在行内上下文里时表示结构打断，不当行内内容处理。 */
    private val BLOCKISH_TAGS = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "aside", "nav",
        "ul", "ol", "li", "dl", "dt", "dd", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
        "blockquote", "figure", "figcaption", "pre", "hr", "h1", "h2", "h3", "h4", "h5", "h6"
    )

    /**
     * 解析一个 XHTML 文档。
     *
     * @param documentKey 该文档的规范容器路径（如 `OEBPS/Text/ch1.xhtml`）。
     * @param xhtml 已按声明解码的 XHTML 文本。
     */
    fun parse(
        documentKey: String,
        xhtml: String,
        resolver: ResourceResolver = resolverFromPathLogic(),
        limits: StructureLimits = DEFAULT_STRUCTURE_LIMITS
    ): StructuredDocument {
        val diagnostics = mutableListOf<DocumentDiagnostic>()
        val jsoupDocument = Jsoup.parse(xhtml, "", Parser.xmlParser())
        val body = jsoupDocument.selectFirst("body")
        val root = body ?: jsoupDocument.children().firstOrNull()
        if (body == null && root != null) {
            // 缺 `<body>` 的 XHTML 仍然可能可读；继续解析但必须留下诊断，不能装作结构完整。
            diagnostics += DocumentDiagnostic("DOCUMENT_BODY_MISSING", "文档没有 body 元素，已按根元素解析")
        }

        if (root == null) {
            diagnostics += DocumentDiagnostic("DOCUMENT_BODY_MISSING", "文档没有可读的根元素")
            return StructuredDocument(
                documentKey = documentKey,
                text = "",
                blocks = emptyList(),
                anchorIndex = emptyMap(),
                resourceRefs = emptyList(),
                mainTextUnits = 0L,
                diagnostics = diagnostics
            )
        }

        checkStructure(root, limits, diagnostics)
        val truncated = diagnostics.any { it.code == "DOCUMENT_TOO_DEEP" || it.code == "DOCUMENT_TOO_LARGE" }
        if (truncated) {
            // 结构超限属于"受控失败"：不返回半解析的正文冒充成功（11.2 末条）。
            return StructuredDocument(
                documentKey = documentKey,
                text = "",
                blocks = emptyList(),
                anchorIndex = emptyMap(),
                resourceRefs = emptyList(),
                mainTextUnits = 0L,
                diagnostics = diagnostics
            )
        }

        val emitter = TextEmitter(documentKey)
        val context = WalkContext(
            documentKey = documentKey,
            resolver = resolver,
            emitter = emitter,
            diagnostics = diagnostics,
            noterefTargets = collectNoterefTargets(root),
            duplicateAnchorReported = mutableSetOf()
        )

        val blocks = mutableListOf<ContentBlock>()
        walkChildren(root, context, blocks, auxContext = isAuxiliaryElement(root, context), depth = 1)

        val document = StructuredDocument(
            documentKey = documentKey,
            text = emitter.text(),
            blocks = blocks,
            anchorIndex = context.anchorIndex,
            resourceRefs = context.resourceRefs.toList(),
            images = context.images.toList(),
            lists = context.lists.toList(),
            tables = context.tables.toList(),
            ruby = context.ruby.toList(),
            mainTextUnits = 0L,
            diagnostics = diagnostics
        )
        // 字数只由 TextUnits 一处口径决定（3.2 末段）：辅助 role、U+FFFC、生成分隔符都不计。
        return document.copy(mainTextUnits = TextUnits.countMainUnits(document))
    }

    // ------------------------------------------------------------------ 结构检查

    private fun checkStructure(root: Element, limits: StructureLimits, diagnostics: MutableList<DocumentDiagnostic>) {
        var count = 0
        var deepest = 0
        var tooDeep = false
        val stack = ArrayDeque<Pair<Element, Int>>()
        stack.addLast(root to 1)
        while (stack.isNotEmpty()) {
            val (element, depth) = stack.removeLast()
            count++
            if (depth > deepest) deepest = depth
            if (depth > limits.maxDepth) {
                tooDeep = true
                break
            }
            if (count > limits.maxElements) break
            element.children().forEach { child -> stack.addLast(child to depth + 1) }
        }
        if (tooDeep) {
            diagnostics += DocumentDiagnostic(
                "DOCUMENT_TOO_DEEP",
                "XML 嵌套深度超过上限 ${limits.maxDepth}（最深 $deepest）"
            )
        }
        if (count > limits.maxElements) {
            diagnostics += DocumentDiagnostic(
                "DOCUMENT_TOO_LARGE",
                "元素数超过上限 ${limits.maxElements}"
            )
        }
    }

    // ------------------------------------------------------------------ DOM 遍历状态

    private class WalkContext(
        val documentKey: String,
        val resolver: ResourceResolver,
        val emitter: TextEmitter,
        val diagnostics: MutableList<DocumentDiagnostic>,
        /** noteref 指向的 fragment id：这些容器里的文字是辅助注释（3.2 第 7 条）。 */
        val noterefTargets: Set<String>,
        val duplicateAnchorReported: MutableSet<String>
    ) {
        val anchorIndex = LinkedHashMap<String, AnchorTarget>()
        val resourceRefs = mutableListOf<ResourceReference>()

    /** 本文档内的全部图片对象（含段落内部的行内图），按源顺序。见 [StructuredDocument.images]。 */
    val images = mutableListOf<ImageBlock>()

    // schema v3 的三类呈现标注：与 images 同一收集纪律 —— 只在唯一创建点收，
    // **绝不**靠事后遍历 blocks 反推（0.2.9 之前行内图就是这么漏掉的）。
    val lists = mutableListOf<ListMark>()
    val tables = mutableListOf<TableMark>()
    val ruby = mutableListOf<RubyMark>()

        /** 最近一次行内发射的图片：用来把"整块只有一张图"的容器提升成 [ImageBlock]。 */
        var lastInlineImage: ImageBlock? = null

        fun span(start: Int, end: Int) = SourceSpan(documentKey, start, end)

        /** 容器元素自己的 id 也要能当锚点（8.1：锚点可挂在空 span、段中、标题前）。 */
        fun registerElementAnchor(element: Element, domPath: String) {
            val id = elementIdOf(element) ?: return
            registerAnchor(id, emitter.position(), blockId(documentKey, domPath), domPath)
        }

        /**
         * 登记锚点。重复 id 属于无效源数据：**保留诊断 + 取文档顺序第一个**（8.1），
         * 不静默覆盖、也不声称精准唯一定位。
         */
        fun registerAnchor(id: String, offset: Int, blockId: String, domPath: String) {
            val trimmed = id.trim()
            if (trimmed.isEmpty()) return
            if (anchorIndex.containsKey(trimmed)) {
                if (duplicateAnchorReported.add(trimmed)) {
                    diagnostics += DocumentDiagnostic(
                        "DUPLICATE_ANCHOR",
                        "元素 id 重复，兼容模式取文档顺序第一个: $trimmed",
                        domPath
                    )
                }
                return
            }
            anchorIndex[trimmed] = AnchorTarget(trimmed, offset, blockId)
        }
    }

    /** 纯容器标签：下潜遍历子节点，自身不产块。 */
    private val CONTAINER_TAGS = setOf(
        "html", "body", "section", "article", "main", "header", "footer", "nav",
        "div", "dl", "dd", "li", "td", "th", "tr", "thead", "tbody", "tfoot", "figcaption"
    )

    /** 段落语义标签：没有块级子元素时整块按一个段落处理。 */
    private val TEXT_BLOCK_TAGS = setOf("p", "dt")

    // ------------------------------------------------------------------ 块级遍历

    private fun walkChildren(
        parent: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        auxContext: Boolean,
        depth: Int
    ) {
        parent.childNodes().forEach { child -> walkNode(child, context, out, auxContext, depth) }
    }

    private fun walkNode(
        node: Node,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        auxContext: Boolean,
        depth: Int
    ) {
        when (node) {
            is TextNode -> {
                // 顶层裸文本（`<div>文字</div>`、`<li>文字</li>`）：包成段落，不丢弃可见文字。
                if (node.wholeText.isNotBlank()) {
                    emitTextBlock(node, context, out, auxContext, depth)
                }
            }

            is Element -> {
                if (!walkElement(node, context, out, auxContext, depth)) {
                    // 未识别的块级位置元素：按容器下潜，并记诊断，**不静默丢文字**。
                    context.diagnostics += DocumentDiagnostic(
                        "UNKNOWN_BLOCK_ELEMENT",
                        "按容器处理未识别元素: <${node.tagName()}>",
                        domPathOf(node)
                    )
                    walkChildren(node, context, out, auxContext, depth + 1)
                }
            }

            else -> Unit
        }
    }

    /** 返回 true 表示该元素已被当作块处理。 */
    private fun walkElement(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        auxContext: Boolean,
        depth: Int
    ): Boolean {
        val tag = element.tagName().lowercase()
        if (tag in SKIPPED_TAGS) return true

        val aux = auxContext || isAuxiliaryElement(element, context)
        val domPath = domPathOf(element)

        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                emitHeading(element, tag[1] - '0', context, out, aux, domPath)
                return true
            }

            "p", "dt" -> {
                emitTextBlock(element, context, out, aux, depth)
                return true
            }

            "div", "section", "article", "main", "header", "footer", "nav", "dl", "dd" -> {
                if (hasBlockChild(element)) {
                    context.registerElementAnchor(element, domPath)
                    walkChildren(element, context, out, aux, depth + 1)
                } else {
                    emitTextBlock(element, context, out, aux, depth)
                }
                return true
            }

            "ul", "ol" -> {
                emitList(element, context, out, aux, depth)
                return true
            }

            "blockquote" -> {
                emitQuote(element, context, out, aux, depth)
                return true
            }

            "figure" -> {
                emitFigure(element, context, out, aux, domPath)
                return true
            }

            "table" -> {
                emitTable(element, context, out, aux, domPath)
                return true
            }

            "pre" -> {
                emitPreformatted(element, context, out, aux, domPath)
                return true
            }

            "hr" -> {
                emitUnsupported(element, context, out, aux, domPath, "hr", "")
                return true
            }

            "svg", "math", "object", "embed", "iframe", "canvas", "video", "audio" -> {
                emitUnsupported(element, context, out, aux, domPath, tag, fallbackTextOf(element))
                return true
            }

            "img", "image" -> {
                emitImageBlock(element, context, out, aux, domPath)
                return true
            }

            "aside" -> {
                context.registerElementAnchor(element, domPath)
                // aside 一律按辅助内容处理：保留源坐标，不进主线权重（3.2 第 7 条）。
                walkChildren(element, context, out, auxContext = true, depth = depth + 1)
                return true
            }

            "li", "td", "th", "tr", "thead", "tbody", "tfoot", "figcaption", "body", "html" -> {
                // 这些只应在各自的专用处理里出现；走到这里说明结构异常，按容器继续下潜而不是丢弃。
                walkChildren(element, context, out, aux, depth + 1)
                return true
            }

            else -> return false
        }
    }

    private fun hasBlockChild(element: Element): Boolean =
        element.children().any { it.tagName().lowercase() in BLOCKISH_TAGS }

    private fun emitHeading(
        element: Element,
        level: Int,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        domPath: String
    ) {
        val blockId = blockId(context.documentKey, domPath)
        val elementId = elementIdOf(element)
        val start = context.emitter.position()
        elementId?.let { context.registerAnchor(it, start, blockId, domPath) }
        context.emitter.beginLeaf()
        val spans = mutableListOf<InlineSpan>()
        emitInlineChildren(element, context, spans, depth = 1)
        val hasContent = context.emitter.hasPendingContentSince(start)
        val end = context.emitter.endLeaf()
        if (!hasContent) {
            context.emitter.rollbackEmptyLeaf(start)
            return
        }
        out += HeadingBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            level = level.coerceIn(1, 6),
            role = auxRole(aux),
            spans = spans,
            elementId = elementId
        )
    }

    private fun emitTextBlock(
        node: Node,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        depth: Int
    ) {
        val domPath = domPathOf(node)
        val blockId = blockId(context.documentKey, domPath)
        val element = node as? Element
        val elementId = element?.let { elementIdOf(it) }
        val start = context.emitter.position()
        elementId?.let { context.registerAnchor(it, start, blockId, domPath) }
        context.emitter.beginLeaf()
        val spans = mutableListOf<InlineSpan>()
        if (element == null) {
            // 顶层裸文本节点：整个节点的文字都属于这个段落。
            context.emitter.emitText((node as TextNode).wholeText, preserveWhitespace = false)
        } else {
            context.lastInlineImage = null
            emitInlineChildren(element, context, spans, depth = 1)
        }
        val hasContent = context.emitter.hasPendingContentSince(start)
        val end = context.emitter.endLeaf()
        if (!hasContent) {
            context.emitter.rollbackEmptyLeaf(start)
            return
        }
        // `<div><img …/></div>` 这类"整块就是一张图"的情况必须是 ImageBlock，
        // 否则 4.6 的"图片是结构对象"在顶层就退化成段落里的一个占位字符。
        val emitter = context.emitter
        if (element != null && spans.isEmpty() && start + 1 == end && emitter.text()[start] == OBJECT_REPLACEMENT) {
            val staged = context.lastInlineImage
            context.lastInlineImage = null
            if (staged != null) {
                // `<div><img …/></div>` 这类"整块就是一张图"必须是 ImageBlock，
                // 否则 4.6 的"图片是结构对象"在顶层就退化成段落里的一个占位字符。
                out += staged.copy(
                    blockId = blockId,
                    range = context.span(start, end),
                    role = auxRole(aux),
                    elementId = elementId
                )
                return
            }
        }
        context.lastInlineImage = null
        out += ParagraphBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            role = auxRole(aux),
            spans = spans,
            elementId = elementId
        )
    }

    private fun emitList(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        depth: Int
    ) {
        val domPath = domPathOf(element)
        val blockId = blockId(context.documentKey, domPath)
        val elementId = elementIdOf(element)
        val ordered = element.tagName().equals("ol", ignoreCase = true)
        val startValue = element.attr("start").takeIf { it.isNotBlank() }?.toIntOrNull()
        val start = context.emitter.position()
        elementId?.let { context.registerAnchor(it, start, blockId, domPath) }

        val items = mutableListOf<ListItem>()
        val listRole = auxRole(aux)
        // 显示层级按 DOM 嵌套深度（ul/ol 祖先数 + 1）：直接项与本列表同层。
        val displayLevel = listLevel(element)
        context.emitter.beginLeaf()
        element.children()
            .filter { it.tagName().equals("li", ignoreCase = true) }
            .forEach { li ->
                items += emitListItem(
                    li,
                    context,
                    ordered,
                    level = 1,
                    aux = aux,
                    depth = depth,
                    listBlockId = blockId,
                    listStart = startValue,
                    role = listRole,
                    displayLevel = displayLevel
                )
            }

        val end = context.emitter.endLeaf()
        if (items.isEmpty()) {
            context.emitter.rollbackEmptyLeaf(start)
            return
        }
        val role = auxRole(aux)
        out += ListBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            ordered = ordered,
            start = startValue,
            items = items,
            role = role,
            elementId = elementId
        )
    }

    /**
     * 显示嵌套深度（1 起）：数 `ul/ol` 祖先。与 [quoteLevel] 同一套做法 ——
     * 从 DOM 结构推导，不依赖遍历顺序。注意 [ListItem.level] 是"本列表内层级"（恒 1），
     * 推导不了跨列表的缩进，呈现标注必须用这里的值。
     */
    private fun listLevel(element: Element): Int {
        var level = 1
        var parent: Element? = element.parent()
        while (parent != null) {
            val tag = parent.tagName().lowercase()
            if (tag == "ul" || tag == "ol") level++
            parent = parent.parent()
        }
        return level
    }

    private fun emitListItem(
        li: Element,
        context: WalkContext,
        ordered: Boolean,
        level: Int,
        aux: Boolean,
        depth: Int,
        listBlockId: String,
        listStart: Int?,
        role: BlockRole,
        displayLevel: Int
    ): ListItem {
        val domPath = domPathOf(li)
        val blockId = blockId(context.documentKey, domPath)
        val start = context.emitter.position()
        elementIdOf(li)?.let { context.registerAnchor(it, start, blockId, domPath) }

        val spans = mutableListOf<InlineSpan>()
        val children = mutableListOf<ContentBlock>()
        val emitter = context.emitter
        emitter.beginLeaf()
        // 列表项的嵌套列表属于子块；其余直接内容进本项的行内 span。
        // 顺序：先把所有行内内容与子块按 DOM 顺序写下，再统一收尾，避免每段各加一个 LF。
        li.childNodes().forEach { child ->
            val element = child as? Element
            val tag = element?.tagName()?.lowercase()
            if (element != null && tag != null && tag in setOf("ul", "ol")) {
                // 规则 4：父项文字与嵌套列表之间要有一个 LF，否则源文本会把
                // 两个独立叶块粘成一段（定位与统计都会跟着偏）。
                if (emitter.position() > start && !emitter.endsWithLineFeed()) emitter.emitLineFeed()
                val listStart = emitter.position()
                walkElement(element, context, children, aux, depth + 1)
                if (listStart == emitter.position()) emitter.rollbackEmptyLeaf(listStart)
            } else {
                emitInline(child, context, spans, depth = 1)
            }
        }
        val end = emitter.endLeaf()
        // 呈现标注（schema v3）：编号信息只能在唯一创建点随结构留下，事后遍历 blocks 反推不出
        // 同层计数。空项（没有可见文字）不产生标注 —— 渲染层不该给空行发项目符号。
        if (emitter.text().substring(start, end).any { !it.isWhitespace() }) {
            context.lists += ListMark(
                listBlockId = listBlockId,
                blockId = blockId,
                domPath = domPath,
                offsetUtf16 = start,
                ordered = ordered,
                listStart = listStart,
                level = displayLevel,
                itemValue = li.attr("value").takeIf { it.isNotBlank() }?.toIntOrNull(),
                role = role
            )
        }
        return ListItem(
            blockId = blockId,
            range = context.span(start, end),
            value = li.attr("value").takeIf { it.isNotBlank() }?.toIntOrNull(),
            level = level,
            spans = spans,
            children = children
        )
    }

    private fun emitQuote(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        depth: Int
    ) {
        val domPath = domPathOf(element)
        val blockId = blockId(context.documentKey, domPath)
        val elementId = elementIdOf(element)
        val start = context.emitter.position()
        elementId?.let { context.registerAnchor(it, start, blockId, domPath) }

        val children = mutableListOf<ContentBlock>()
        context.emitter.beginLeaf()
        if (element.children().any { it.tagName().lowercase() in BLOCKISH_TAGS }) {
            walkChildren(element, context, children, aux, depth + 1)
        } else {
            val spans = mutableListOf<InlineSpan>()
            emitInlineChildren(element, context, spans, depth = 1)
            val textStart = start
            val end = context.emitter.endLeaf()
            if (context.emitter.hasPendingContentSince(textStart)) {
                children += ParagraphBlock(
                    blockId = "$blockId/p",
                    range = context.span(textStart, end),
                    domPath = "$domPath/p",
                    role = auxRole(aux),
                    spans = spans
                )
            } else {
                context.emitter.rollbackEmptyLeaf(textStart)
            }
        }

        val end = context.emitter.endLeaf()
        out += QuoteBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            level = quoteLevel(element),
            children = children,
            role = auxRole(aux),
            elementId = elementId
        )
    }

    private fun quoteLevel(element: Element): Int {
        var level = 1
        var parent: Element? = element.parent()
        while (parent != null) {
            if (parent.tagName().equals("blockquote", ignoreCase = true)) level++
            parent = parent.parent()
        }
        return level
    }

    private fun emitFigure(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        domPath: String
    ) {
        val blockId = blockId(context.documentKey, domPath)
        val elementId = elementIdOf(element)
        val start = context.emitter.position()
        elementId?.let { context.registerAnchor(it, start, blockId, domPath) }

        val imageElement = element.selectFirst("img, image")
        context.emitter.beginLeaf()
        val image = imageElement?.let { emitImageInline(it, context, domPathOf(it)) }

        val captionElement = element.selectFirst("figcaption")
        var caption: String? = null
        var captionRange: SourceSpan? = null
        if (captionElement != null) {
            val captionStart = context.emitter.position()
            emitInlineChildren(captionElement, context, mutableListOf(), depth = 1)
            val captionEnd = context.emitter.position()
            if (context.emitter.hasPendingContentSince(captionStart)) {
                caption = context.emitter.text().substring(captionStart, captionEnd)
                captionRange = context.span(captionStart, captionEnd)
            }
        }

        // 图内除图片/图注之外的块（如散落的 p）仍然保留，不能吞字。
        element.children()
            .filter { child -> child.tagName().lowercase() !in setOf("img", "image", "figcaption") }
            .forEach { child ->
                child.select("img, image").remove()
                walkElement(child, context, out, aux, depth = 2)
            }

        val end = context.emitter.endLeaf()
        if (image == null && caption == null && !context.emitter.hasPendingContentSince(start)) {
            context.emitter.rollbackEmptyLeaf(start)
            return
        }
        out += FigureBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            image = image,
            caption = caption,
            captionRange = captionRange,
            role = auxRole(aux),
            elementId = elementId
        )
    }

    private fun emitTable(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        domPath: String
    ) {
        val blockId = blockId(context.documentKey, domPath)
        val elementId = elementIdOf(element)
        val start = context.emitter.position()
        elementId?.let { context.registerAnchor(it, start, blockId, domPath) }

        val rows = mutableListOf<TableRow>()
        context.emitter.beginLeaf()
        element.select("tr").forEach { rowElement ->
            val cells = mutableListOf<TableCell>()
            rowElement.children()
                .filter { it.tagName().lowercase() == "td" || it.tagName().lowercase() == "th" }
                .forEachIndexed { index, cellElement ->
                    cells += emitTableCell(cellElement, context, domPathOf(cellElement), isFirstInRow = index == 0)
                }
            if (cells.isNotEmpty()) rows += TableRow(cells)
            context.emitter.endTableRow()
        }

        val end = context.emitter.endLeaf()
        if (rows.isEmpty()) {
            context.emitter.rollbackEmptyLeaf(start)
            return
        }
        out += TableBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            rows = rows,
            role = auxRole(aux),
            elementId = elementId
        )
        // 呈现标注（schema v3）：正文里表格只有 TAB，表头/跨行跨列信息只能在这里随结构留下。
        context.tables += TableMark(
            blockId = blockId,
            domPath = domPath,
            startUtf16 = start,
            endUtf16Exclusive = end,
            rows = rows.map { row ->
                TableRowMark(
                    cells = row.cells.map { cell ->
                        TableCellMark(
                            blockId = cell.blockId,
                            startUtf16 = cell.range.startUtf16,
                            endUtf16Exclusive = cell.range.endUtf16Exclusive,
                            isHeader = cell.isHeader,
                            rowSpan = cell.rowSpan,
                            colSpan = cell.colSpan
                        )
                    }
                )
            },
            role = auxRole(aux)
        )
    }

    /**
     * 一个表格单元格。规则 4：**单元格之间的分隔符是 TAB，行末是 LF**。
     *
     * TAB 在这里**前置**补齐（除行首单元格）：`nextElementSibling()` 在 Jsoup 的 XML 解析下
     * 对首行/末行并不可靠（实测首行首格会返回 null），按位置判定才是确定的。
     */
    private fun emitTableCell(
        cellElement: Element,
        context: WalkContext,
        domPath: String,
        isFirstInRow: Boolean
    ): TableCell {
        val blockId = blockId(context.documentKey, domPath)
        elementIdOf(cellElement)?.let {
            context.registerAnchor(it, context.emitter.position(), blockId, domPath)
        }
        if (!isFirstInRow) context.emitter.emitTab()
        val start = context.emitter.position()
        val spans = mutableListOf<InlineSpan>()
        emitInlineChildren(cellElement, context, spans, depth = 1)
        val end = context.emitter.position()
        return TableCell(
            blockId = blockId,
            range = context.span(start, end),
            isHeader = cellElement.tagName().equals("th", ignoreCase = true),
            rowSpan = cellElement.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1,
            colSpan = cellElement.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1,
            text = context.emitter.text().substring(start, end),
            spans = spans
        )
    }

    private fun emitPreformatted(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        domPath: String
    ) {
        val blockId = blockId(context.documentKey, domPath)
        val elementId = elementIdOf(element)
        val start = context.emitter.position()
        elementId?.let { context.registerAnchor(it, start, blockId, domPath) }
        // 规则 2/8：`pre` 的空白逐字保留。用 wholeText() 会丢掉换行后的缩进，
        // 所以这里按节点走：文本节点逐字写入、`br` 生成 LF、其余元素按行内处理。
        context.lastInlineImage = null
        context.emitter.beginLeaf()
        emitPreformattedContent(element, context)
        val hasContent = context.emitter.hasPendingContentSince(start)
        val end = context.emitter.endLeaf()
        if (!hasContent) {
            context.emitter.rollbackEmptyLeaf(start)
            return
        }
        out += PreformattedBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            text = context.emitter.text().substring(start, end),
            role = auxRole(aux),
            elementId = elementId
        )
    }

    private fun emitPreformattedContent(element: Element, context: WalkContext) {
        element.childNodes().forEach { child ->
            when (child) {
                is TextNode -> context.emitter.emitText(child.wholeText, preserveWhitespace = true)
                is Element -> {
                    if (child.tagName().equals("br", ignoreCase = true)) {
                        context.emitter.emitLineFeed()
                    } else {
                        emitPreformattedContent(child, context)
                    }
                }

                else -> Unit
            }
        }
    }

    private fun emitImageBlock(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        domPath: String
    ) {
        val blockId = blockId(context.documentKey, domPath)
        val start = context.emitter.position()
        elementIdOf(element)?.let { context.registerAnchor(it, start, blockId, domPath) }
        context.emitter.beginLeaf()
        val image = emitImageInline(element, context, domPath)
        val end = context.emitter.endLeaf()
        out += image.copy(blockId = blockId, range = context.span(start, end), role = auxRole(aux))
    }

    private fun emitUnsupported(
        element: Element,
        context: WalkContext,
        out: MutableList<ContentBlock>,
        aux: Boolean,
        domPath: String,
        tagName: String,
        fallbackText: String
    ) {
        val blockId = blockId(context.documentKey, domPath)
        val start = context.emitter.position()
        elementIdOf(element)?.let { context.registerAnchor(it, start, blockId, domPath) }
        var reference: ResourceReference? = null
        if (element.tagName().equals("object", ignoreCase = true)) {
            val data = element.attr("data").takeIf { it.isNotBlank() }
            if (data != null) {
                reference = context.resolver.resolve(context.documentKey, data).also { context.resourceRefs += it }
            }
        }
        if (fallbackText.isNotEmpty()) {
            context.emitter.beginLeaf()
            context.emitter.emitText(fallbackText, preserveWhitespace = false)
        }
        val end = context.emitter.endLeaf()
        context.emitter.rollbackEmptyLeafIf(start, end)
        context.diagnostics += DocumentDiagnostic(
            "UNSUPPORTED_ELEMENT",
            "保留原资源与替代文字，本期不渲染该标签: <$tagName>",
            domPath
        )
        out += UnsupportedBlock(
            blockId = blockId,
            range = context.span(start, end),
            domPath = domPath,
            tagName = tagName,
            fallbackText = fallbackText,
            reference = reference,
            role = auxRole(aux),
            elementId = elementIdOf(element)
        )
    }

    private fun emitImageInline(element: Element, context: WalkContext, domPath: String): ImageBlock {
        val rawHref = imageSource(element)
        val reference = if (rawHref.isEmpty()) {
            ResourceReference(
                raw = "",
                target = ResourceTargetKind.REJECTED,
                errorCode = "IMAGE_SOURCE_MISSING",
                detail = "img 既没有 src 也没有 xlink:href"
            )
        } else {
            context.resolver.resolve(context.documentKey, rawHref)
        }
        context.resourceRefs += reference
        if (reference.target == ResourceTargetKind.REJECTED) {
            context.diagnostics += DocumentDiagnostic(
                "IMAGE_REFERENCE_REJECTED",
                "图片引用被拒绝: $rawHref（${reference.errorCode}）",
                domPath
            )
        }
        val alt = element.attr("alt").takeIf { it.isNotBlank() }
        val objectOffset = context.emitter.emitObject()
        if (alt != null) {
            // alt 进元数据、不进正文字符计数（3.2 第 5 条）。诊断保留它，便于核对来源。
            context.diagnostics += DocumentDiagnostic("IMAGE_ALT", alt, domPath)
        }
        val block = ImageBlock(
            blockId = blockId(context.documentKey, domPath),
            range = context.span(objectOffset, objectOffset + 1),
            domPath = domPath,
            reference = reference,
            objectOffsetUtf16 = objectOffset,
            alt = alt,
            elementId = elementIdOf(element)
        )
        // 行内图也必须进文档清单：段落内部的图片不进 blocks，只能靠这里留住映射。
        context.images += block
        context.lastInlineImage = block
        return block
    }

    /** `src` 优先，其次 `xlink:href`（SVG `image`），最后 `href`。 */
    private fun imageSource(element: Element): String =
        (element.attr("src").takeIf { it.isNotBlank() }
            ?: element.attr("xlink:href").takeIf { it.isNotBlank() }
            ?: element.attr("href").takeIf { it.isNotBlank() }
            ?: "").trim()

    // ------------------------------------------------------------------ 行内遍历

    private fun emitInlineChildren(element: Element, context: WalkContext, spans: MutableList<InlineSpan>, depth: Int) {
        element.childNodes().forEach { child -> emitInline(child, context, spans, depth) }
    }

    private fun emitInline(node: Node, context: WalkContext, spans: MutableList<InlineSpan>, depth: Int) {
        when (node) {
            is TextNode -> context.emitter.emitText(node.wholeText, preserveWhitespace = false)

            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag in SKIPPED_TAGS) return
                when (tag) {
                    "br" -> context.emitter.emitLineFeed()

                    "img", "image" -> {
                        // 图片是结构对象，不是文本标记：**不**为它生成 CODE span
                        // （否则块级图片会被误判成"有行内内容的段落"，4.6 就在顶层失效了）。
                        emitImageInline(node, context, domPathOf(node))
                    }

                    "ruby" -> emitRuby(node, context, spans, depth)

                    "rt", "rp" -> Unit // 注音只通过 emitRuby 进入元数据

                    "a" -> {
                        val start = context.emitter.position()
                        // 8.1：兼容 `<a name="x">` 这种"空 span 上的锚点"。
                        node.attr("name").takeIf { it.isNotBlank() }?.let { name ->
                            context.registerAnchor(name, start, blockId(context.documentKey, domPathOf(node)), domPathOf(node))
                        }
                        node.attr("id").takeIf { it.isNotBlank() }?.let { id ->
                            context.registerAnchor(id, start, blockId(context.documentKey, domPathOf(node)), domPathOf(node))
                        }
                        emitInlineChildren(node, context, spans, depth + 1)
                        val end = context.emitter.position()
                        val href = node.attr("href")
                        val reference = if (href.isBlank()) {
                            null
                        } else {
                            context.resolver.resolve(context.documentKey, href).also { context.resourceRefs += it }
                        }
                        val isNoteref = isNoteref(node, reference, context)
                        spans += InlineSpan(
                            kind = if (isNoteref) InlineSpanKind.NOTEREF else InlineSpanKind.LINK,
                            range = context.span(start, end),
                            depth = depth,
                            target = reference
                        )
                    }

                    "svg", "math", "object", "iframe", "canvas", "video", "audio" -> {
                        val fallback = fallbackTextOf(node)
                        if (fallback.isNotEmpty()) context.emitter.emitText(fallback, preserveWhitespace = false)
                        context.diagnostics += DocumentDiagnostic(
                            "UNSUPPORTED_INLINE_ELEMENT",
                            "行内不支持标签，仅保留替代文字: <$tag>",
                            domPathOf(node)
                        )
                    }

                    else -> {
                        val kind = INLINE_SPAN_TAGS[tag]
                        if (kind == null) {
                            emitInlineChildren(node, context, spans, depth)
                        } else {
                            val start = context.emitter.position()
                            emitInlineChildren(node, context, spans, depth + 1)
                            val end = context.emitter.position()
                            if (end > start) spans += InlineSpan(kind, context.span(start, end), depth)
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    private fun emitRuby(element: Element, context: WalkContext, spans: MutableList<InlineSpan>, depth: Int) {
        val domPath = domPathOf(element)
        val blockId = blockId(context.documentKey, domPath)
        val start = context.emitter.position()
        elementIdOf(element)?.let { context.registerAnchor(it, start, blockId, domPath) }

        // 基字：ruby 的直接文本节点与除 rt/rp 之外的子元素（3.2 第 6 条）。
        val html = org.jsoup.Jsoup.parseBodyFragment(element.html()).body()
        org.jsoup.select.Elements(html.select("rt, rp")).remove()
        val baseText = html.wholeText()

        // 注音跟着基字 span 走，不另立一套坐标（4.5 Ruby：主坐标仍是基字）。
        val annotations = element.children()
            .filter { it.tagName().equals("rt", ignoreCase = true) }
            .map { it.wholeText().trim() }
            .filter { it.isNotEmpty() }
        if (baseText.isNotEmpty()) {
            val baseStart = context.emitter.position()
            context.emitter.emitText(baseText, preserveWhitespace = false)
            val baseEnd = context.emitter.position()
            if (baseEnd > baseStart) spans += InlineSpan(InlineSpanKind.RUBY_BASE, context.span(baseStart, baseEnd), depth)
            if (annotations.isNotEmpty()) {
                // schema v3：注音只进显示层标注，**不进**主文本、不进字数口径（3.2 第 6 条）。
                context.ruby += RubyMark(
                    blockId = blockId,
                    domPath = domPath,
                    baseStartUtf16 = baseStart,
                    baseEndUtf16Exclusive = baseEnd,
                    annotations = annotations
                )
            }
        }
        if (annotations.isNotEmpty()) {
            spans += InlineSpan(
                kind = InlineSpanKind.RUBY_TEXT,
                range = context.span(context.emitter.position(), context.emitter.position()),
                depth = depth,
                annotations = annotations
            )
        }
    }

    private fun isNoteref(node: Element, reference: ResourceReference?, context: WalkContext): Boolean {
        val epubType = node.attr("epub:type").ifBlank { node.attr("type") }
        if (epubType.split(Regex("\\s+")).any { it.equals("noteref", ignoreCase = true) }) return true
        val fragment = reference?.fragment ?: return false
        return context.noterefTargets.contains(fragment)
    }

    /** 第一遍扫描：所有 noteref 指向的 fragment id。第二遍才知道哪些容器是辅助注释。 */
    private fun collectNoterefTargets(root: Element): Set<String> {
        val targets = mutableSetOf<String>()
        root.select("a").forEach { anchor ->
            val epubType = anchor.attr("epub:type").ifBlank { anchor.attr("type") }
            val isNoteref = epubType.split(Regex("\\s+")).any { it.equals("noteref", ignoreCase = true) }
            if (!isNoteref) return@forEach
            val href = anchor.attr("href")
            val fragment = href.substringAfter('#', missingDelimiterValue = "")
            if (fragment.isNotEmpty()) {
                EpubResourceResolver.percentDecode(fragment)?.let { targets += it }
            }
        }
        return targets
    }

    private fun isAuxiliaryElement(element: Element, context: WalkContext): Boolean {
        val tag = element.tagName().lowercase()
        if (tag == "aside") return true
        val epubType = element.attr("epub:type").ifBlank { element.attr("type") }
        if (epubType.split(Regex("\\s+")).any {
                it.equals("footnote", ignoreCase = true) || it.equals("endnote", ignoreCase = true) ||
                    it.equals("rearnote", ignoreCase = true)
            }
        ) {
            return true
        }
        val id = elementIdOf(element)
        return id != null && context.noterefTargets.contains(id)
    }

    private fun auxRole(aux: Boolean): BlockRole = if (aux) BlockRole.AUXILIARY else BlockRole.MAIN

    // ------------------------------------------------------------------ 标识与路径

    private fun elementIdOf(element: Element): String? {
        val raw = element.attr("id").takeIf { it.isNotBlank() }
            ?: element.attr("xml:id").takeIf { it.isNotBlank() }
            ?: element.attr("name").takeIf { it.isNotBlank() && element.tagName().equals("a", ignoreCase = true) }
        return raw?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 块 ID：`documentKey` + DOM 结构路径的确定性摘要（INV02）。
     *
     * **不是**随机 UUID，也不是分页序号 —— 同一输入重复解析必须得到同一份 ID。
     * 原始元素 id 重复时由结构路径天然消歧（12.3 第 5 条）。
     */
    private fun blockId(documentKey: String, domPath: String): String = "b:" + sha256Hex("$documentKey|$domPath").substring(0, 24)

    /** 诊断用 DOM 路径，如 `body/div[2]/p[1]`；同标签兄弟按出现序号编号。 */
    private fun domPathOf(node: Node): String {
        val parts = ArrayDeque<String>()
        var current: Node? = node
        while (current is Element) {
            val element = current
            val tag = element.tagName().lowercase()
            val parent: Element? = element.parent()
            val ordinal = if (parent == null) {
                1
            } else {
                parent.children().takeWhile { it !== element }.count { it.tagName() == element.tagName() } + 1
            }
            parts.addFirst("$tag[$ordinal]")
            current = parent
        }
        return parts.joinToString("/")
    }

    private fun fallbackTextOf(element: Element): String {
        val alt = element.attr("alt").takeIf { it.isNotBlank() }
        if (alt != null) return alt
        val title = element.attr("title").takeIf { it.isNotBlank() }
        if (title != null) return title
        return element.text().trim()
    }

    private fun sha256Hex(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte -> out.append("%02x".format(byte)) }
        return out.toString()
    }

    // ------------------------------------------------------------------ 文本发射器

    /**
     * 规范源文本 v1 的唯一写入点。
     *
     * 之所以集中在一个类里：3.2 的十条规则互相约束（空块不加空行、表格分隔符不计字、图片占 U+FFFC
     * 但 alt 不进计数），分散写入必然出现父子各加一次分隔符之类的偏差。
     */
    internal class TextEmitter(private val documentKey: String) {

        private val builder = StringBuilder()
        private var pendingSpace = false
        private var codePointCount = 0L
        /** 上一次 `endLeaf()` 加过的 LF 位置，用于回滚空块。 */
        private var lastLeafLineFeedAt = -1
        /** 当前叶块的起点（`beginLeaf()` 记录）。 */
        private var leafStart = 0
        /** 是否有叶块处于"打开"状态：容器块收尾时据此决定要不要再补 LF。 */
        private var leafOpen = false

        fun text(): String = builder.toString()
        fun position(): Int = builder.length
        fun codePoints(): Long = codePointCount

        /** 自 [start] 起是否产生了可见内容（空块判断，避免空块生成空行）。 */
        fun hasPendingContentSince(start: Int): Boolean {
            var index = start
            while (index < builder.length) {
                val ch = builder[index]
                if (ch != '\n' && !COLLAPSIBLE_WHITESPACE.contains(ch)) return true
                index++
            }
            return false
        }

        /**
         * 规则 2：可折叠空白归一为一个 U+0020；NBSP 不折叠；CRLF/CR 已在 [emitText] 里统一成 LF。
         * 规则 8（`pre`）：[preserveWhitespace] 为 true 时空白逐字保留。
         */
        fun emitText(raw: String, preserveWhitespace: Boolean) {
            val normalized = normalizeLineEndings(raw)
            if (preserveWhitespace) {
                normalized.forEach { ch -> appendChar(ch, countUnits = true) }
                return
            }
            normalized.forEach { ch ->
                if (COLLAPSIBLE_WHITESPACE.contains(ch)) {
                    pendingSpace = true
                } else {
                    flushPendingSpace()
                    appendChar(ch, countUnits = true)
                }
            }
        }

        /** 当前是否位于行首（容器与叶块之间不重复补 LF 用）。 */
        fun endsWithLineFeed(): Boolean = builder.isEmpty() || builder[builder.length - 1] == '\n'

        /** 规则 4：`br` 在块内生成 LF。 */
        fun emitLineFeed() {
            pendingSpace = false
            builder.append('\n')
        }

        /** 规则 4：表格单元格之间的 TAB。不计字数。 */
        fun emitTab() {
            pendingSpace = false
            builder.append('\t')
        }

        /** 规则 5：图片/无文本原子对象占一个 U+FFFC，返回它的偏移。 */
        fun emitObject(): Int {
            flushPendingSpace()
            val offset = builder.length
            builder.append(OBJECT_REPLACEMENT)
            return offset
        }

        /** 开始一个新的叶块：记录起点，避免上一个叶块的标记误伤本叶块。 */
        fun beginLeaf() {
            leafStart = builder.length
            leafOpen = true
            pendingSpace = false
        }

        /**
         * 规则 4：独立文本叶块结束后追加一个 LF（最后一个叶块也追加）。
         *
         * 两种情况不补 LF：叶块内已经由子叶块写过 LF（例如列表项里的嵌套列表），
         * 或已经位于行首。少了这个判断，容器与叶块会各加一次，源文本里凭空多出空行。
         */
        fun endLeaf(): Int {
            pendingSpace = false
            val end = builder.length
            val alreadyTerminated = leafOpen && end > leafStart && builder[end - 1] == '\n'
            leafOpen = false
            if (!alreadyTerminated && (end == 0 || builder[end - 1] != '\n')) {
                builder.append('\n')
                lastLeafLineFeedAt = end
            }
            return end
        }

        /** 空块不得留下额外的空行。 */
        fun rollbackEmptyLeaf(start: Int) {
            if (builder.length == start) return
            if (lastLeafLineFeedAt == builder.length - 1 && lastLeafLineFeedAt >= start) {
                builder.setLength(lastLeafLineFeedAt)
                lastLeafLineFeedAt = -1
            }
            pendingSpace = false
        }

        fun rollbackEmptyLeafIf(start: Int, end: Int) {
            if (!hasPendingContentSince(start)) rollbackEmptyLeaf(end)
        }

        /** 规则 4：表格行末 LF。 */
        fun endTableRow() {
            pendingSpace = false
            builder.append('\n')
        }

        /**
         * 补一个折叠出来的空格。
         *
         * 段首、行首、TAB 之后、对象字符之后都不补：否则块边界会凭空多出空格，
         * 而空格虽然没有字数，却会改变规范文本、进而改变所有后续偏移。
         */
        private fun flushPendingSpace() {
            if (!pendingSpace) return
            pendingSpace = false
            val last = if (builder.isEmpty()) null else builder[builder.length - 1]
            if (last == null || last == '\n' || last == '\t' || last == OBJECT_REPLACEMENT) return
            if (COLLAPSIBLE_WHITESPACE.contains(last)) return
            builder.append(' ')
        }

        private fun appendChar(ch: Char, countUnits: Boolean) {
            builder.append(ch)
            if (countUnits && !isUnitExcluded(ch)) codePointCount++
        }

        /** 统计口径（3.2 末段）：非空白 code point，排除 U+FFFC 与生成分隔符。 */
        private fun isUnitExcluded(ch: Char): Boolean =
            ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r' || ch == '\u000C' || ch == OBJECT_REPLACEMENT

        private fun normalizeLineEndings(value: String): String {
            if (!value.contains('\r')) return value
            return value.replace("\r\n", "\n").replace('\r', '\n')
        }

        init {
            // 文档键只用于诊断归属，避免被误当成可写状态。
            require(documentKey.isNotBlank()) { "documentKey must not be blank" }
        }
    }
}
