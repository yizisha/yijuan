package com.flowreader.app.util

import com.flowreader.app.domain.model.Chapter
import com.flowreader.app.domain.model.TocNode
import com.flowreader.app.domain.model.TocNodeKind
import com.flowreader.app.domain.model.TocTree

/**
 * 旧书目录 → [TocTree] 的兼容适配器（主计划 7.1 末段：「旧数据的兼容识别放在 LegacyTocAdapter」）。
 *
 * ## 为什么要把这件事从 UI 里搬出来
 *
 * 旧 `ReaderTocSheet` 在 Composable 内部用 `TxtChapterDetector.VOLUME_PATTERN.matches(...)`
 * **现场猜**哪些章是卷。后果：
 * - 猜错就把普通章节显示成卷，或把真卷当章节；
 * - 无法展开/收起（没有层级）；
 * - 正则改动会意外改变阅读 UI，而它没有任何测试覆盖。
 *
 * 搬到这里之后：输入输出都是纯数据，可 JVM 单测；UI 只消费 [TocTree]，不含任何结构猜测。
 * 新导入的书走真正的 EPUB 目录树，本适配器只服务尚未重解析的旧书。
 *
 * ## 与旧行为保持一致的规则
 * 1. `chapter.volume` 非空：按卷分组，卷内每章都是**可读条目**；
 * 2. `chapter.volume` 为空且标题命中卷模式：该章**只**作为分组标题，不产生可读条目
 *    （主计划 4.4：结构性卷标题不占正常导航步骤）；
 * 3. 其余章节是可读条目：若前面刚出现过卷标题（显式或旧式），则归入该卷；否则挂在根上。
 *
 * 稳定身份：卷节点用「出现序号 + 标题」生成 nodeId，因此**同名卷不会冲突**（F07）。
 * 条目用「章节列表下标」，因为下标就是阅读序，与阅读器构造 locator 的约定一致。
 */
object LegacyTocAdapter {

    /**
     * @param publicationId 书身份；展开状态按它隔离，因此必须稳定。
     * @param chapters 旧表的扁平章节列表（按阅读顺序）。
     */
    fun toTocTree(
        publicationId: String,
        revision: String,
        chapters: List<Chapter>
    ): TocTree {
        // roots 本身按插入顺序保存根节点，因此 order 直接用下标即可。
        val roots = mutableListOf<MutableVolume>()
        var currentVolume: MutableVolume? = null
        var volumeSequence = 0
        val sectionOrdinals = mutableMapOf<String, Int>()

        fun openVolume(label: String): MutableVolume {
            val volume = MutableVolume(
                nodeId = "vol:${volumeSequence}:$label",
                label = label
            )
            volumeSequence++
            roots.add(volume)
            currentVolume = volume
            return volume
        }

        chapters.forEachIndexed { ordinal, chapter ->
            val sectionId = sectionIdOf(ordinal)
            val volumeLabel = chapter.volume?.takeIf { it.isNotBlank() }
            when {
                // 规则 1：显式分卷字段。同名卷也必须各自成节，故比较「是否同一个卷对象」。
                volumeLabel != null -> {
                    val volume = currentVolume?.takeIf { it.label == volumeLabel } ?: openVolume(volumeLabel)
                    volume.entries.add(
                        TocNode(
                            nodeId = "ch:$ordinal",
                            parentId = volume.nodeId,
                            order = volume.entries.size,
                            label = chapter.title,
                            kind = TocNodeKind.ENTRY,
                            targetSectionId = sectionId
                        )
                    )
                    sectionOrdinals[sectionId] = ordinal
                }

                // 规则 2：旧式卷标题只做分组，不产生可读条目。
                isLegacyVolumeHeading(chapter.title) -> {
                    openVolume(chapter.title.trim())
                }

                // 规则 3：普通章节。
                else -> {
                    // 旧书把卷标题存成章节时，其后的普通章节必须**归入该卷**，
                    // 否则「卷」只是一个分隔符，折叠它没有任何效果——而计划 7.2 要求
                    // 卷能独立展开/收起、卷范围清楚。
                    val volume = currentVolume
                    if (volume != null) {
                        volume.entries.add(
                            TocNode(
                                nodeId = "ch:$ordinal",
                                parentId = volume.nodeId,
                                order = volume.entries.size,
                                label = chapter.title,
                                kind = TocNodeKind.ENTRY,
                                targetSectionId = sectionId
                            )
                        )
                    } else {
                        val entry = TocNode(
                            nodeId = "ch:$ordinal",
                            parentId = null,
                            order = roots.size,
                            label = chapter.title,
                            kind = TocNodeKind.ENTRY,
                            targetSectionId = sectionId
                        )
                        roots.add(MutableVolume(nodeId = entry.nodeId, label = chapter.title, entry = entry))
                    }
                    sectionOrdinals[sectionId] = ordinal
                }
            }
        }

        val finalRoots = roots.mapIndexed { index, volume -> volume.toNode(order = index) }
        return TocTree(
            publicationId = publicationId,
            revision = revision,
            roots = finalRoots,
            // section 的阅读序就等于章节列表下标：与阅读器构造 locator 的约定一致。
            sectionOrdinalById = sectionOrdinals
        )
    }

    /** 章节在阅读顺序里的稳定 section id；与 ReaderViewModel 的 `section_<ordinal>` 一致。 */
    fun sectionIdOf(ordinal: Int): String = "section_$ordinal"

    /** 标题是否像一个旧式卷标题（结构性标题，不作为可读章节）。 */
    private fun isLegacyVolumeHeading(title: String): Boolean =
        TxtChapterDetector.VOLUME_PATTERN.matches(title.trim())

    /** 构建期的可变卷节点：避免在循环里反复 copy 已加入的不可变节点（O(n²)）。 */
    private class MutableVolume(
        val nodeId: String,
        val label: String,
        val entry: TocNode? = null
    ) {
        val entries = mutableListOf<TocNode>()

        fun toNode(order: Int): TocNode = entry ?: TocNode(
            nodeId = nodeId,
            parentId = null,
            order = order,
            label = label,
            kind = TocNodeKind.GROUP,
            isVolume = true,
            children = entries.toList()
        )
    }
}
