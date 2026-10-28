package com.flowreader.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowreader.app.domain.model.Bookmark
import com.flowreader.app.domain.model.LegacyPositionUnit
import com.flowreader.app.domain.model.ResolutionQuality
import com.flowreader.app.domain.model.TextLocatorCodec
import java.util.Date

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "chapterIndex", "position"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val position: Int,
    val text: String,
    val createdTime: Long = System.currentTimeMillis(),

    // ---- v10（主计划 5.2）。旧 chapterIndex/position 原样保留，绝不覆写；旧单位由
    // legacyUnit 明确标注，迁移转换后写 locatorJson，二者分工不同。
    /** ContentLocator JSON（schemaVersion=1,kind=text|fixedPage,payload）。 */
    val locatorJson: String? = null,
    /** EXACT | CONTEXT_MATCH | APPROXIMATE | UNRESOLVED；默认 UNRESOLVED 表示尚未解析。 */
    @ColumnInfo(defaultValue = "UNRESOLVED")
    val locatorQuality: String = "UNRESOLVED",
    /** PAGE_INDEX | SCROLL_PX | UTF16 | UNKNOWN；旧记录单位未知时不得猜。 */
    @ColumnInfo(defaultValue = "UNKNOWN")
    val legacyUnit: String = "UNKNOWN"
) {
    fun toDomain(): Bookmark {
        // 解析失败时 locator 为 null 且质量降级为 UNRESOLVED：宁可承认「待恢复」，
        // 也不把畸形 JSON 当成有效坐标（INV10）。
        //
        // 必须判**解析结果**而不是只看 `locatorJson` 非空：列里可能有被截断/手改的 JSON，
        // 此时 locator 解析为 null；若仍沿用列里存着的 "EXACT"，书签会声称精确却没有任何
        // 可用坐标，UI 就无法提示「定位待恢复」。
        val parsedLocator = locatorJson?.let { TextLocatorCodec.fromJson(it) }
        return Bookmark(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            position = position,
            text = text,
            createdTime = Date(createdTime),
            locator = parsedLocator,
            locatorQuality = if (parsedLocator == null) {
                ResolutionQuality.UNRESOLVED
            } else {
                parseQuality(locatorQuality)
            },
            legacyUnit = parseLegacyUnit(legacyUnit)
        )
    }

    companion object {
        fun fromDomain(bookmark: Bookmark): BookmarkEntity = BookmarkEntity(
            id = bookmark.id,
            bookId = bookmark.bookId,
            chapterIndex = bookmark.chapterIndex,
            position = bookmark.position,
            text = bookmark.text,
            createdTime = bookmark.createdTime.time,
            locatorJson = bookmark.locator?.let { TextLocatorCodec.toJson(it) },
            locatorQuality = if (bookmark.locator == null) {
                ResolutionQuality.UNRESOLVED.name
            } else {
                bookmark.locatorQuality.name
            },
            legacyUnit = bookmark.legacyUnit.name
        )

        private fun parseQuality(raw: String): ResolutionQuality =
            runCatching { ResolutionQuality.valueOf(raw) }.getOrDefault(ResolutionQuality.UNRESOLVED)

        private fun parseLegacyUnit(raw: String): LegacyPositionUnit =
            runCatching { LegacyPositionUnit.valueOf(raw) }.getOrDefault(LegacyPositionUnit.UNKNOWN)
    }
}
