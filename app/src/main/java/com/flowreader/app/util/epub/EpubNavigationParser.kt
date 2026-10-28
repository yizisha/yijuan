package com.flowreader.app.util.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.security.MessageDigest

/**
 * EPUB 目录解析（主计划 4.3）。
 *
 * 修掉的旧实现缺陷：
 * - **F06**：旧代码用一次**后代选择器**把所有 `a` 扁平化，丢掉层级，也丢掉 `<span>` 卷头。
 *   现在按 `li` 的**直接子** `a`/`span` 读取，再递归**直接子** `ol`，因此三层树与无链接分组都能
 *   保留。
 * - **F05**：旧代码把 href `substringBefore('#')` 后存进 Map，同一 XHTML 的多个锚点互相覆盖。
 *   现在目标保留 fragment，且 `nodeId` 由「源文件 + 结构路径 + 原始 id」哈希得到，**同名不同位置
 *   必然不同 ID**。
 *
 * 其它固定规则：
 * - EPUB3 只把 `epub:type` token 列表里含 `toc` 的 `nav` 当主目录；`landmarks` / `page-list`
 *   单独返回，**不混入章节**。出现多个主目录时报诊断，不随机拼接。
 * - EPUB2 NCX 的 label **只读当前节点自己的 `navLabel`**，绝不把子节点文本拼进父标题。
 * - 纯数据、无 Android 类型，可用 JVM 直接测试。
 */
object EpubNavigationParser {

    /** 一个目录节点。卷是 [isVolume] 为 true 的 [Kind.GROUP]。 */
    data class Node(
        val nodeId: String,
        val label: String,
        val kind: Kind,
        val isVolume: Boolean = false,
        /**
         * 直写 `toc_nodes.role` 的值：卷 → [ROLE_VOLUME]，普通项 → null，
         * landmarks / page-list → 各自来源（4.3：这两类独立保存，不混入章节）。
         */
        val role: String? = null,
        /** 规范容器路径；无链接的分组标题为 null。 */
        val targetPath: String? = null,
        /** 已解码的 fragment；同一文档的多个锚点靠它区分。 */
        val targetFragment: String? = null,
        val children: List<Node> = emptyList()
    ) {
        enum class Kind { GROUP, ENTRY }
    }

    /** `toc_nodes.role` 的稳定取值。 */
    const val ROLE_VOLUME = "VOLUME"
    const val ROLE_LANDMARK = "LANDMARK"
    const val ROLE_PAGE_LIST = "PAGE_LIST"

    data class Diagnostic(val code: String, val detail: String)

    data class Result(
        /** 主目录（按目录文件中的顺序，**不重排正文**）。 */
        val main: List<Node> = emptyList(),
        val landmarks: List<Node> = emptyList(),
        val pageList: List<Node> = emptyList(),
        val diagnostics: List<Diagnostic> = emptyList()
    )

    // ---------------------------------------------------------------- EPUB 3 nav

    /**
     * @param navXml nav 文档的 XML 文本。
     * @param navPath nav 文档自身的容器路径（用于解析相对 href 与生成稳定 ID）。
     */
    fun parseEpub3Nav(navXml: String, navPath: String): Result {
        val doc = Jsoup.parse(navXml, "", Parser.xmlParser())
        val navElements = doc.select("nav")
        if (navElements.isEmpty()) {
            return Result(diagnostics = listOf(Diagnostic("NAV_MISSING", "未找到 nav 元素")))
        }

        val tocNavs = navElements.filter { nav ->
            nav.attr("epub:type").split(Regex("\\s+")).any { it == "toc" }
        }
        val diagnostics = mutableListOf<Diagnostic>()
        if (tocNavs.isEmpty()) {
            diagnostics += Diagnostic("TOC_NAV_MISSING", "没有 epub:type=toc 的 nav")
        }
        if (tocNavs.size > 1) {
            // 计划要求：多个 toc 返回可诊断格式错误，不随机拼接。
            diagnostics += Diagnostic("TOC_NAV_AMBIGUOUS", "存在 ${tocNavs.size} 个 toc nav")
        }

        val mainNav = tocNavs.firstOrNull()
        val main = mainNav?.let { parseOlChildren(it, navPath, "nav") }.orEmpty()

        val landmarks = navElements
            .filter { it.attr("epub:type").split(Regex("\\s+")).any { token -> token == "landmarks" } }
            .flatMap { parseOlChildren(it, navPath, "landmarks") }

        val pageList = navElements
            .filter { it.attr("epub:type").split(Regex("\\s+")).any { token -> token == "page-list" } }
            .flatMap { parseOlChildren(it, navPath, "page-list") }

        return Result(main = main, landmarks = landmarks, pageList = pageList, diagnostics = diagnostics)
    }

    /** 只遍历 `nav`/`li` 的**直接子** ol/li 结构。 */
    private fun parseOlChildren(container: Element, basePath: String, sourceKind: String): List<Node> {
        val ol = container.children().firstOrNull { it.tagName() == "ol" } ?: return emptyList()
        return parseLiList(ol, basePath, sourceKind, parentPath = "")
    }

    private fun parseLiList(ol: Element, basePath: String, sourceKind: String, parentPath: String): List<Node> {
        val result = mutableListOf<Node>()
        var order = 0
        for (li in ol.children().filter { it.tagName() == "li" }) {
            val anchor = li.children().firstOrNull { it.tagName() == "a" }
            val span = li.children().firstOrNull { it.tagName() == "span" }
            val labelHolder = anchor ?: span
            val label = labelHolder?.text()?.trim().orEmpty()

            val href = anchor?.attr("href")?.takeIf { it.isNotBlank() }
            val resolved = href?.let { EpubResourceResolver.resolve(basePath, it) }
            val targetPath = (resolved as? EpubResourceResolver.ResourceTarget.Internal)?.containerPath
            val targetFragment = (resolved as? EpubResourceResolver.ResourceTarget.Internal)?.fragment

            val childOl = li.children().firstOrNull { it.tagName() == "ol" }
            val structuralPath = if (parentPath.isEmpty()) "$order" else "$parentPath.$order"
            val children = childOl?.let { parseLiList(it, basePath, sourceKind, structuralPath) }.orEmpty()

            // 既没有标签也没有子节点的空 li 跳过。
            if (label.isNotEmpty() || children.isNotEmpty()) {
                val isGroup = children.isNotEmpty()
                result += Node(
                    nodeId = stableId(sourceKind, basePath, structuralPath, label),
                    label = label,
                    kind = if (isGroup) Node.Kind.GROUP else Node.Kind.ENTRY,
                    isVolume = isGroup,
                    role = roleFor(sourceKind, isGroup, hasAnchor = anchor != null),
                    targetPath = targetPath,
                    targetFragment = targetFragment,
                    children = children
                )
                order++
            }
        }
        return result
    }

    /**
     * 目录行的 role。
     *
     * - `landmarks` / `page-list` 各自来源（4.3：独立保存，不混入章节）；
     * - **只有 EPUB3 nav 里"用 `<span>` 作分组标题"的节点**才是卷（4.3：「span 是有效分组标题」）。
     *
     * 关键约束（本条是实测抓出来的缺陷）：**NCX 里"有子节点"不等于卷**。
     * NCX 的 navPoint 嵌套在真实书里普遍表示"章 → 节"的两级标题，把它当卷会凭空造出一个
     * 覆盖全书的假卷，并让子章节错误归属。EPUB2 没有卷的可靠标记，所以一律不标卷 ——
     * 宁可没有卷，也不伪造卷（4.4「无法判定时保留为可读卷扉页，不要误删/误判」）。
     */
    private fun roleFor(sourceKind: String, isGroup: Boolean, hasAnchor: Boolean): String? = when {
        sourceKind == "landmarks" -> ROLE_LANDMARK
        sourceKind == "page-list" -> ROLE_PAGE_LIST
        sourceKind == "nav" && isGroup && !hasAnchor -> ROLE_VOLUME
        else -> null
    }

    // ---------------------------------------------------------------- EPUB 2 NCX

    fun parseEpub2Ncx(ncxXml: String, ncxPath: String): Result {
        val doc = Jsoup.parse(ncxXml, "", Parser.xmlParser())
        val navMap = doc.select("navMap").firstOrNull()
            ?: return Result(diagnostics = listOf(Diagnostic("NCX_NAVMAP_MISSING", "未找到 navMap")))
        return Result(main = parseNavPoints(navMap, ncxPath, "ncx", parentPath = ""))
    }

    private fun parseNavPoints(container: Element, basePath: String, sourceKind: String, parentPath: String): List<Node> {
        val result = mutableListOf<Node>()
        var order = 0
        for (point in container.children().filter { it.tagName() == "navPoint" }) {
            // 只读当前节点自己的 navLabel：不能把子节点文本拼进父标题。
            val ownLabel = point.children().firstOrNull { it.tagName() == "navLabel" }
                ?.children()?.firstOrNull { it.tagName() == "text" }
                ?.text()?.trim()
                .orEmpty()
            val src = point.children().firstOrNull { it.tagName() == "content" }?.attr("src")?.takeIf { it.isNotBlank() }
            val resolved = src?.let { EpubResourceResolver.resolve(basePath, it) }
            val targetPath = (resolved as? EpubResourceResolver.ResourceTarget.Internal)?.containerPath
            val targetFragment = (resolved as? EpubResourceResolver.ResourceTarget.Internal)?.fragment

            val structuralPath = if (parentPath.isEmpty()) "$order" else "$parentPath.$order"
            val children = parseNavPoints(point, basePath, sourceKind, structuralPath)
            if (ownLabel.isNotEmpty() || children.isNotEmpty()) {
                val isGroup = children.isNotEmpty()
                result += Node(
                    nodeId = stableId(sourceKind, basePath, structuralPath, ownLabel),
                    label = ownLabel,
                    kind = if (isGroup) Node.Kind.GROUP else Node.Kind.ENTRY,
                    isVolume = isGroup,
                    // NCX 的 navPoint 常同时带标签和子节点（"章 → 节"）；`roleFor` 对
                    // sourceKind="ncx" 一律返回 null：EPUB2 没有可靠的卷标记，不伪造卷。
                    role = roleFor(sourceKind, isGroup, hasAnchor = src != null),
                    targetPath = targetPath,
                    targetFragment = targetFragment,
                    children = children
                )
                order++
            }
        }
        return result
    }

    // ---------------------------------------------------------------- 稳定 ID

    /**
     * `nodeId` = SHA-256(源类型 + 源文件 + 结构路径 + 原始 id/标签) 的前 16 个十六进制字符。
     *
     * 三条要求：**确定性**（同一输入两次解析结果一致，INV02）、**同名不同位置必须不同**、
     * **不使用随机 UUID**。结构路径已保证唯一，标签只作为额外消歧输入。
     */
    fun stableId(sourceKind: String, sourcePath: String, structuralPath: String, label: String): String {
        val input = "$sourceKind\u0000$sourcePath\u0000$structuralPath\u0000$label"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /** 展平为一层，供写入 `toc_nodes` 表；`parentId` 指向父节点 ID。 */
    fun flatten(nodes: List<Node>): List<FlatNode> = buildList {
        fun walk(list: List<Node>, parentId: String?) {
            list.forEachIndexed { index, node ->
                add(FlatNode(node, parentId, index))
                walk(node.children, node.nodeId)
            }
        }
        walk(nodes, null)
    }

    data class FlatNode(val node: Node, val parentId: String?, val siblingOrder: Int)
}
