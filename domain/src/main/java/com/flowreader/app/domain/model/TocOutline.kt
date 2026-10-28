package com.flowreader.app.domain.model

/**
 * 目录的**当前阅读位置**（主计划 7.1）。
 *
 * @param sectionId 当前主线 section；null 表示尚未定位。
 * @param entryNodeId 用户**从哪个目录入口**进来的；用于同名别名时优先高亮该入口
 *   （计划 7.2 第 12 条）。
 */
data class TocLocation(
    val sectionId: String? = null,
    val entryNodeId: String? = null
)

/**
 * 一个分组的**覆盖信息**（计划 4.3 末段 / 7.1）。
 *
 * 规则：集合来自所有**可读后代**目标的 section 去重；连续集合显示首尾范围，
 * 非连续集合显示「共 N 节 · 非连续」，绝不能假装覆盖夹在中间但不属于该卷的章。
 */
data class TocCoverage(
    val readableCount: Int,
    val firstOrdinal: Int?,
    val lastOrdinal: Int?,
    val isContiguous: Boolean
) {
    enum class DisplayKind {
        /** 无任何可读后代：UI 显示「暂无可读章节」且不显示误导性展开箭头。 */
        NONE,

        /** 连续覆盖：可显示「第 X—Y 章」。 */
        RANGE,

        /** 非连续覆盖：只能显示「共 N 节 · 非连续」。 */
        NON_CONTIGUOUS
    }

    val displayKind: DisplayKind
        get() = when {
            readableCount <= 0 -> DisplayKind.NONE
            isContiguous && firstOrdinal != null && lastOrdinal != null -> DisplayKind.RANGE
            else -> DisplayKind.NON_CONTIGUOUS
        }
}

/** 展平后的一行。UI 只渲染它，不再自行遍历树。 */
data class TocRow(
    val node: TocNode,
    /** 真实层级（从 0 开始）。无障碍读出与面包屑用**真实**层级。 */
    val depth: Int,
    /** 是否处于展开态。 */
    val isExpanded: Boolean,
    /** 仅 GROUP 有值。 */
    val coverage: TocCoverage?,
    val isCurrent: Boolean,
    /** 搜索命中角色，UI 据此加粗/展开。 */
    val matchKind: TocMatchKind
) {
    /** 缩进层级，**封顶 3 层**：更深层级靠面包屑/语义表达，不能把文字挤成一列。 */
    val indentDepth: Int get() = depth.coerceAtMost(TocOutline.MAX_INDENT_DEPTH)

    /** 是否显示展开/收起箭头：只有真的有子节点才显示。 */
    val showExpandArrow: Boolean get() = node.hasChildren

    /** 无子节点且无可读目标的空分组：不能显示误导性箭头。 */
    val isDeadGroup: Boolean
        get() = node.kind == TocNodeKind.GROUP && !node.hasChildren && !node.hasTarget
}

/** 一行在搜索结果中的角色。 */
enum class TocMatchKind {
    /** 不是搜索命中（无查询词，或该行仅为上下文）。 */
    NONE,

    /** 自身命中。 */
    SELF,

    /** 祖先链上的行：为命中项提供上下文，强制展开但不标为命中。 */
    ANCESTOR
}

/**
 * 目录展平与搜索的**纯逻辑**（主计划 7.2 的交互规格）。
 *
 * 为什么单独一层：这些规则（自动展开祖先、搜索强制展开但不覆写用户折叠、
 * 覆盖范围连续/非连续、死分组识别、深度封顶）全是可判定的逻辑，
 * 放在 Composable 里就只能靠手点验证。这里全部可 JVM 单测。
 */
object TocOutline {

    /** 缩进封顶层级：第 4 层及更深不再增加缩进。 */
    const val MAX_INDENT_DEPTH = 3

    /**
     * 首次打开该书目录时的展开集合：**只展开当前章祖先**，其余卷收起（计划 7.2 第 4 条）。
     *
     * 无当前定位时展开第一个可读章祖先（第 4 条后半）。
     */
    fun initialExpanded(tree: TocTree, location: TocLocation): Set<String> {
        val current = currentNode(tree, location)
        if (current != null) return tree.ancestorsOf(current.nodeId).toSet()
        // 无定位时的回退：取**第一个可读条目**（ENTRY）的祖先。
        // 这里刻意跳过「只有 target 的根卷」：它是根，祖先集为空，展开结果也是空，
        // 用户打开目录会看到全部收起、像坏了一样。用第一个可读章才能把首章露出来。
        val fallback = firstReadableEntry(tree) ?: firstReadableNode(tree) ?: return emptySet()
        return tree.ancestorsOf(fallback.nodeId).toSet()
    }

    /** 目录顺序上第一个**可读条目**（ENTRY）；没有条目时才退而取任意可读节点。 */
    fun firstReadableEntry(tree: TocTree): TocNode? = tree.allNodes.firstOrNull { it.hasTarget && it.kind == TocNodeKind.ENTRY }

    /**
     * 当前高亮节点。
     *
     * 多别名时优先用户进入的 [TocLocation.entryNodeId]；否则取**第一个规范主目录入口**
     * （按目录顺序最靠前者），不随意选一个（计划 7.2 第 12 条）。
     */
    fun currentNode(tree: TocTree, location: TocLocation): TocNode? {
        val sectionId = location.sectionId ?: return null
        val entry = location.entryNodeId?.let { tree.node(it) }
        if (entry != null && entry.targetSectionId == sectionId) return entry
        // 目录顺序 = allNodes 的深度优先顺序，因此 firstOrNull 即「第一个规范入口」。
        return tree.allNodes.firstOrNull { it.targetSectionId == sectionId }
    }

    /** 目录顺序上第一个可读节点。 */
    fun firstReadableNode(tree: TocTree): TocNode? = tree.allNodes.firstOrNull { it.hasTarget }

    /**
     * 展平目录为可见行。
     *
     * @param expanded 用户**长期**折叠状态；搜索时不会被改写（第 7 条）。
     * @param query 搜索词；空白表示普通浏览。
     *
     * 搜索行为（第 7、8 条）：匹配条目标签**与**卷标签；命中的章展示**完整祖先链**；
     * 卷命中展示**该卷全部后代**；按 nodeId 去重；无结果返回空列表（UI 显示空态）。
     */
    fun flatten(tree: TocTree, expanded: Set<String>, location: TocLocation = TocLocation(), query: String = ""): List<TocRow> {
        val trimmed = query.trim()
        val current = currentNode(tree, location)
        return if (trimmed.isEmpty()) {
            flattenBrowsing(tree, expanded, current)
        } else {
            flattenSearch(tree, expanded, current, trimmed)
        }
    }

    private fun flattenBrowsing(tree: TocTree, expanded: Set<String>, current: TocNode?): List<TocRow> {
        val rows = mutableListOf<TocRow>()
        fun walk(nodes: List<TocNode>, depth: Int) {
            nodes.forEach { node ->
                val isExpanded = node.nodeId in expanded
                rows.add(rowOf(tree, node, depth, isExpanded, current, TocMatchKind.NONE))
                // 只有展开的父节点才遍历子节点：这是「收起」的全部实现。
                if (isExpanded && node.hasChildren) walk(node.children, depth + 1)
            }
        }
        walk(tree.roots, 0)
        return rows
    }

    private fun flattenSearch(tree: TocTree, expanded: Set<String>, current: TocNode?, query: String): List<TocRow> {
        val selfMatches = tree.allNodes.filter { it.label.contains(query, ignoreCase = true) }
        if (selfMatches.isEmpty()) return emptyList()

        // 需要显示的行 = 命中行自身 + 命中行祖先 + （命中的 GROUP 的）全部后代。
        val visible = LinkedHashSet<String>()
        val selfIds = mutableSetOf<String>()
        val ancestorIds = mutableSetOf<String>()
        selfMatches.forEach { match ->
            selfIds.add(match.nodeId)
            tree.ancestorsOf(match.nodeId).forEach { ancestorIds.add(it) }
            if (match.kind == TocNodeKind.GROUP) {
                tree.descendantsOf(match.nodeId).forEach { visible.add(it) }
            }
        }
        visible.addAll(selfIds)
        visible.addAll(ancestorIds)

        // 搜索期**强制展开**命中路径：合并用户折叠状态，但**不回写**（第 7 条）。
        // 命中的 GROUP 还要把它的**全部后代分组**一并展开，否则「卷命中展示该卷全部后代」
        // 只会展开第一层，嵌套子卷仍然收着。
        val expandForSearch = mutableSetOf<String>()
        expandForSearch.addAll(ancestorIds)
        selfMatches.filter { it.kind == TocNodeKind.GROUP }.forEach { group ->
            if (group.hasChildren) expandForSearch.add(group.nodeId)
            tree.descendantsOf(group.nodeId).forEach { id ->
                if (tree.node(id)?.hasChildren == true) expandForSearch.add(id)
            }
        }
        val effectiveExpanded = expanded + expandForSearch

        val rows = mutableListOf<TocRow>()
        fun walk(nodes: List<TocNode>, depth: Int) {
            nodes.forEach { node ->
                if (node.nodeId !in visible) return@forEach
                val matchKind = when {
                    node.nodeId in selfIds -> TocMatchKind.SELF
                    node.nodeId in ancestorIds -> TocMatchKind.ANCESTOR
                    else -> TocMatchKind.NONE
                }
                val isExpanded = node.nodeId in effectiveExpanded
                rows.add(rowOf(tree, node, depth, isExpanded, current, matchKind))
                if (node.hasChildren && isExpanded) walk(node.children, depth + 1)
            }
        }
        walk(tree.roots, 0)
        return rows
    }

    private fun rowOf(tree: TocTree, node: TocNode, depth: Int, isExpanded: Boolean, current: TocNode?, matchKind: TocMatchKind) = TocRow(
        node = node,
        depth = depth,
        isExpanded = isExpanded,
        coverage = if (node.kind == TocNodeKind.GROUP) coverageOf(tree, node) else null,
        isCurrent = current != null && node.nodeId == current.nodeId,
        matchKind = matchKind
    )

    /**
     * 计算分组的覆盖信息：全部**可读后代**（含自身 target）的 section 去重后的阅读序集合。
     *
     * 缺 sectionOrdinalById 时不猜范围：`isContiguous` 判为 false，由 UI 显示「共 N 节」。
     */
    fun coverageOf(tree: TocTree, group: TocNode): TocCoverage {
        val sectionIds = LinkedHashSet<String>()
        group.targetSectionId?.let { sectionIds.add(it) }
        tree.descendantsOf(group.nodeId).forEach { id ->
            tree.node(id)?.targetSectionId?.let { sectionIds.add(it) }
        }
        val ordinals = sectionIds.mapNotNull { tree.sectionOrdinalById[it] }.sorted()
        if (ordinals.isEmpty()) {
            return TocCoverage(
                readableCount = sectionIds.size,
                firstOrdinal = null,
                lastOrdinal = null,
                isContiguous = false
            )
        }
        // 连续 = 阅读序没有断档，且去重后的数量与序号跨度一致。
        val contiguous = ordinals.size == (ordinals.last() - ordinals.first() + 1)
        return TocCoverage(
            readableCount = ordinals.size,
            firstOrdinal = ordinals.first(),
            lastOrdinal = ordinals.last(),
            isContiguous = contiguous
        )
    }

    /** 展开某个节点（幂等）。 */
    fun expand(expanded: Set<String>, nodeId: String): Set<String> = expanded + nodeId

    /** 收起某个节点（幂等）。 */
    fun collapse(expanded: Set<String>, nodeId: String): Set<String> = expanded - nodeId

    /** 折叠/展开切换：卷行主体与箭头共用它（计划 7.2 第 1 条）。 */
    fun toggle(expanded: Set<String>, nodeId: String): Set<String> = if (nodeId in expanded) expanded - nodeId else expanded + nodeId
}
