package com.flowreader.app.domain.model

/**
 * 目录树（主计划 7.1 / 4.3）。
 *
 * ## 为什么要有独立的领域模型
 *
 * 旧 UI（`ReaderTocSheet`）直接接收扁平 `List<Chapter>`，再用正则从标题里**猜**哪些是卷：
 * - 猜错就把普通章节显示成卷，或者把真卷当章节；
 * - 没有层级，卷无法独立展开/收起（计划 7.2 第 1 条要求卷行主体与箭头都能收起）；
 * - 同名卷用标题当 key 会冲突（F07）。
 *
 * 本模型把目录变成**带稳定身份与层级**的树：
 * - [TocNode.nodeId] 由来源文件 + 结构路径 + 原始 id 哈希得到，**同名不同位置必然不同 ID**；
 * - 卷是带 `isVolume` 的 [TocNodeKind.GROUP]，**允许同时有 target**（计划 7.2 第 1 条：
 *   有 target 的卷要提供独立的「读卷首」按钮，不能同一点击既展开又跳转）；
 * - 无链接的分组（`span` 卷头）保留为 GROUP 且 target 为空——它们**不是**错误数据。
 *
 * 不变量：
 * - 树内 nodeId 唯一；父子关系无环；
 * - GROUP 与 ENTRY 都可能可读（有 targetSectionId），也都可能不可读；
 * - 折叠状态**绝不**影响正文可读性（计划 2.1：不让卷折叠决定正文是否可读）。
 */
data class TocNode(
    val nodeId: String,
    val parentId: String?,
    /** 同一父节点内的顺序，从 0 开始。 */
    val order: Int,
    val label: String,
    val kind: TocNodeKind,
    /** 是否为卷（`toc_nodes.role = VOLUME`）。 */
    val isVolume: Boolean = false,
    /** 指向的逻辑 section；无链接分组为 null。 */
    val targetSectionId: String? = null,
    val children: List<TocNode> = emptyList()
) {
    /** 是否可以跳到真实正文。 */
    val hasTarget: Boolean get() = targetSectionId != null

    /** 是否还有子节点（决定是否显示展开箭头）。 */
    val hasChildren: Boolean get() = children.isNotEmpty()
}

/** 目录节点类型。 */
enum class TocNodeKind {
    /** 分组/卷：可以折叠，允许同时有 target。 */
    GROUP,

    /** 普通条目：点击即跳转。 */
    ENTRY
}

/**
 * 一本出版物的目录树。
 *
 * [sectionOrdinalById] 提供「sectionId → 阅读序」的映射，用于计算卷的覆盖范围；
 * 缺少映射时覆盖信息退化为「按可读节数」，**不猜范围**。
 */
data class TocTree(
    val publicationId: String,
    val revision: String,
    val roots: List<TocNode>,
    val sectionOrdinalById: Map<String, Int> = emptyMap()
) {
    /** 按深度优先（阅读/目录顺序）展开的全部节点。 */
    val allNodes: List<TocNode> = flattenAll(roots)

    private val byId: Map<String, TocNode> = allNodes.associateBy { it.nodeId }

    fun node(nodeId: String): TocNode? = byId[nodeId]

    /** 某节点的祖先链（由根到直接父），用于「展开当前章祖先」。 */
    fun ancestorsOf(nodeId: String): List<String> {
        val chain = mutableListOf<String>()
        var current = byId[nodeId]?.parentId
        // 防御环：坏数据不得让这里死循环。
        var guard = 0
        while (current != null && guard < MAX_DEPTH_GUARD) {
            chain.add(0, current)
            current = byId[current]?.parentId
            guard++
        }
        return chain
    }

    /** 某节点的全部后代 nodeId（不含自身）。 */
    fun descendantsOf(nodeId: String): List<String> = byId[nodeId]?.let { flattenAll(it.children).map { child -> child.nodeId } }.orEmpty()

    /** 所有带子节点的 GROUP id；「全部展开」用它，不会展开叶子。 */
    val expandableIds: List<String>
        get() = allNodes.filter { it.hasChildren }.map { it.nodeId }

    /** 是否存在同名不同位置的节点（用于诊断；ID 已保证不冲突）。 */
    val duplicateLabels: List<String>
        get() = allNodes.groupBy { it.label }.filterValues { it.size > 1 }.keys.toList()

    private companion object {
        /** 防御坏数据成环：祖先链最多回溯这么多层。 */
        const val MAX_DEPTH_GUARD = 128

        fun flattenAll(nodes: List<TocNode>): List<TocNode> {
            val out = mutableListOf<TocNode>()
            nodes.forEach { node ->
                out.add(node)
                out.addAll(flattenAll(node.children))
            }
            return out
        }
    }
}
