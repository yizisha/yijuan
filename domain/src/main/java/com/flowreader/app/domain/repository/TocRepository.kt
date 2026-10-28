package com.flowreader.app.domain.repository

import com.flowreader.app.domain.model.TocTree

/**
 * 目录树读取（主计划 7.1）。
 *
 * 与 `LegacyTocAdapter` 的分工：
 * - 本接口服务**已重解析**的书（有 `toc_nodes`），目录反映出版物真实结构；
 * - `LegacyTocAdapter` 只服务尚未重解析的旧书，从扁平章节兼容重建。
 */
interface TocRepository {

    /**
     * 读取某 revision 的目录树。
     *
     * @return 该 revision **没有任何目录节点**时返回 null，调用方据此回退到兼容适配器；
     *   有节点但数据有问题时仍返回树（坏数据会被提升/记诊断，不让目录整段消失）。
     */
    suspend fun getTocTree(bookId: Long, revision: String, publicationId: String): TocTree?
}
