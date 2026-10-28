package com.flowreader.app.domain.model

/**
 * `toc_nodes` 的一行（扁平形式）。
 *
 * 为什么要有这个 DTO：[TocNodeEntity] 在 data 层且带 Room 注解，domain 不能依赖它；
 * 但「把扁平行组装成树」这件事本身是**纯逻辑**，必须在 domain 里可测。
 */
data class TocNodeRow(
    val nodeId: String,
    val parentId: String?,
    val siblingOrder: Int,
    val label: String,
    /** 稳定字符串：`GROUP` / `ENTRY`（与 `TocNodeEntity.KIND_*` 一致）。 */
    val kind: String,
    /** `toc_nodes.role`：卷为 `VOLUME`；landmarks / page-list 另有取值。 */
    val role: String? = null,
    /** 已解析的目标 section（`targetJson.sectionId`）；无链接分组为 null。 */
    val targetSectionId: String? = null
)

/**
 * 扁平目录行 → [TocTree] 的装配器（主计划 4.3 / 7.1）。
 *
 * 这是 S8 的「真实目录树」入口：EPUB 导入时 [EpubNavigationParser] 的层级被
 * `flattenToc` 写进 `toc_nodes`（nodeId/parentId/siblingOrder），这里再还原成树。
 *
 * **为什么不在 UI 里直接建树**：坏数据的处理（孤儿节点、环、重复 id、未知 kind）
 * 全是可判定的边界条件，放在 Composable 里就只能靠手点验证。
 *
 * 坏数据策略（全部**不静默丢弃**，且不影响正常数据）：
 * - 未知 kind → 按 ENTRY 处理（可读优先，不因为标签奇怪就让章节点不开）；
 * - 重复 nodeId → 保留第一个，记诊断；
 * - 孤儿（parentId 指向不存在的节点）→ **提升为根**并记诊断，
 *   这样它的子树仍可见；直接丢弃会让整段目录凭空消失；
 * - 成环 → 环上的节点提升为根，避免无限递归。
 */
object TocTreeBuilder {

    /** 装配结果。[diagnostics] 为空表示数据完全健康。 */
    data class Built(
        val tree: TocTree,
        val diagnostics: List<String>
    ) {
        val isHealthy: Boolean get() = diagnostics.isEmpty()
    }

    fun build(publicationId: String, revision: String, rows: List<TocNodeRow>, sectionOrdinalById: Map<String, Int> = emptyMap()): Built {
        if (rows.isEmpty()) {
            return Built(
                tree = TocTree(publicationId, revision, emptyList(), sectionOrdinalById),
                diagnostics = emptyList()
            )
        }

        val diagnostics = mutableListOf<String>()

        // 去重：重复 nodeId 保留第一个（后续同 id 行无法同时挂在树上）。
        val unique = LinkedHashMap<String, TocNodeRow>()
        rows.forEach { row ->
            if (unique.containsKey(row.nodeId)) {
                diagnostics += "DUPLICATE_NODE_ID:${row.nodeId}"
            } else {
                unique[row.nodeId] = row
            }
        }

        // 分组子节点；顺序按 siblingOrder，稳定且与目录原顺序一致。
        val childrenByParent = LinkedHashMap<String?, MutableList<TocNodeRow>>()
        unique.values.forEach { row ->
            childrenByParent.getOrPut(row.parentId) { mutableListOf() }.add(row)
        }
        childrenByParent.values.forEach { list ->
            list.sortWith(compareBy({ it.siblingOrder }, { it.nodeId }))
        }

        // 环检测：沿 parentId 上溯，若回到自己则视为兄弟顺序里的根（提升）。
        fun isInCycle(row: TocNodeRow): Boolean {
            var current = row.parentId
            var guard = 0
            while (current != null && guard < MAX_GUARD) {
                if (current == row.nodeId) return true
                current = unique[current]?.parentId
                guard++
            }
            return guard >= MAX_GUARD
        }

        val rootRows = mutableListOf<TocNodeRow>()
        childrenByParent[null]?.let { rootRows.addAll(it) }
        unique.values.forEach { row ->
            val parentId = row.parentId ?: return@forEach
            when {
                parentId !in unique -> {
                    diagnostics += "ORPHAN_PROMOTED_TO_ROOT:${row.nodeId}"
                    rootRows.add(row)
                }
                isInCycle(row) -> {
                    diagnostics += "CYCLE_PROMOTED_TO_ROOT:${row.nodeId}"
                    rootRows.add(row)
                }
            }
        }
        rootRows.sortWith(compareBy({ it.siblingOrder }, { it.nodeId }))

        // 已在根上的 nodeId，避免提升后的节点又被当作某个父的子节点重复挂载。
        val promoted = rootRows.map { it.nodeId }.toSet()

        fun buildNode(row: TocNodeRow): TocNode {
            val childRows = childrenByParent[row.nodeId]
                .orEmpty()
                .filter { it.nodeId !in promoted }
            val children = childRows.map { buildNode(it) }
            return TocNode(
                nodeId = row.nodeId,
                parentId = row.parentId,
                order = row.siblingOrder,
                label = row.label,
                kind = parseKind(row.kind),
                isVolume = row.role == ROLE_VOLUME,
                targetSectionId = row.targetSectionId,
                children = children
            )
        }

        val roots = rootRows.map { buildNode(it) }
        return Built(
            tree = TocTree(publicationId, revision, roots, sectionOrdinalById),
            diagnostics = diagnostics
        )
    }

    /** 未知 kind 按 ENTRY 处理：不让异常标签把章节变得点不开。 */
    private fun parseKind(raw: String): TocNodeKind = when (raw.uppercase()) {
        KIND_GROUP -> TocNodeKind.GROUP
        else -> TocNodeKind.ENTRY
    }

    const val KIND_GROUP = "GROUP"
    const val ROLE_VOLUME = "VOLUME"
    private const val MAX_GUARD = 256
}
