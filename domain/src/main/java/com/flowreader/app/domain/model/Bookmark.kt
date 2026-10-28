package com.flowreader.app.domain.model

import java.util.Date

data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    /** **旧**位置字段（主计划 5.4）。单位可能是页码或滚动像素，由 [legacyUnit] 标注。 */
    val position: Int,
    val text: String,
    val createdTime: Date = Date(),

    // ---- v10 内容坐标（主计划 3.1 / 第 9 节）。旧 position 原样保留，二者分工不同：
    // locator 是权威内容坐标，position 只在 legacyUnit 明确时作为兼容回退。
    /** 权威内容坐标；旧数据尚未解析时为 null，**不得**用 position 伪造。 */
    val locator: TextLocator? = null,
    val locatorQuality: ResolutionQuality = ResolutionQuality.UNRESOLVED,
    /** 旧 [position] 的单位证据：PAGE_INDEX | SCROLL_PX | UTF16 | UNKNOWN。 */
    val legacyUnit: LegacyPositionUnit = LegacyPositionUnit.UNKNOWN
) {
    /** 是否具备可跨重排恢复的权威坐标。 */
    val hasContentLocator: Boolean get() = locator != null

    /** 是否只有未经证实的旧整数：此时 UI 必须显示「定位待恢复」，不得假装精确。 */
    val isLegacyOnly: Boolean get() = locator == null
}

/**
 * 旧位置整数的单位证据（主计划 5.4）。
 *
 * 迁移**不能**从「当前全局阅读模式」反推历史记录的单位：同一本书的用户可能在分页模式
 * 存过页码、在滚动模式存过像素，而模式后来被改掉了。单位未知时只能标 [UNKNOWN]，
 * 保留原值并用可靠文字/上下文去找位置，绝不把旧整数直接当新字符偏移。
 */
enum class LegacyPositionUnit {
    /** 明确来自分页模式：position 是页下标。 */
    PAGE_INDEX,

    /** 明确来自滚动模式：position 是滚动像素。 */
    SCROLL_PX,

    /** 明确是正文 UTF-16 字符偏移。 */
    UTF16,

    /** 单位未知：**不得**据此送入新正文坐标。 */
    UNKNOWN
}
