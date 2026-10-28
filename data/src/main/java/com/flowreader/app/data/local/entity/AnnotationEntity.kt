package com.flowreader.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.flowreader.app.domain.model.Annotation
import com.flowreader.app.domain.model.AnnotationColor
import com.flowreader.app.domain.model.AnnotationType
import com.flowreader.app.domain.model.RangeLocatorCodec
import com.flowreader.app.domain.model.ResolutionQuality
import java.util.Date

@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("bookId", "chapterIndex")]
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val startPosition: Int,
    val endPosition: Int,
    val selectedText: String,
    val note: String = "",
    val color: String = "YELLOW",
    val type: String = "HIGHLIGHT",
    val createdTime: Long = System.currentTimeMillis(),
    val modifiedTime: Long = System.currentTimeMillis(),

    // ---- v10（主计划 5.2）。旧 startPosition/endPosition（字符，开区间）原样保留。
    /** 一条或多条有序 SourceRange 的 JSON：{schemaVersion,publicationId,revision,ranges}。 */
    val rangeLocatorJson: String? = null,
    @ColumnInfo(defaultValue = "UNRESOLVED")
    val locatorQuality: String = "UNRESOLVED"
) {
    fun toDomain(): Annotation {
        // 同 BookmarkEntity：质量必须由**解析结果**决定。列里存着 "EXACT" 但 JSON 畸形时，
        // 必须降级为 UNRESOLVED，否则批注会声称精确而实际没有可用源范围。
        val parsedRange = rangeLocatorJson?.let { RangeLocatorCodec.fromJson(it) }
        return Annotation(
            id = id,
            bookId = bookId,
            chapterIndex = chapterIndex,
            startPosition = startPosition,
            endPosition = endPosition,
            selectedText = selectedText,
            note = note,
            color = try {
                AnnotationColor.valueOf(color)
            } catch (e: Exception) {
                AnnotationColor.YELLOW
            },
            type = try {
                AnnotationType.valueOf(type)
            } catch (e: Exception) {
                AnnotationType.HIGHLIGHT
            },
            createdTime = Date(createdTime),
            modifiedTime = Date(modifiedTime),
            // 解析失败 → null + UNRESOLVED；旧字段完整保留，绝不删除或自动扩缩（主计划 5.4）。
            rangeLocator = parsedRange,
            locatorQuality = if (parsedRange == null) {
                ResolutionQuality.UNRESOLVED
            } else {
                parseQuality(locatorQuality)
            }
        )
    }

    companion object {
        fun fromDomain(annotation: Annotation): AnnotationEntity = AnnotationEntity(
            id = annotation.id,
            bookId = annotation.bookId,
            chapterIndex = annotation.chapterIndex,
            startPosition = annotation.startPosition,
            endPosition = annotation.endPosition,
            selectedText = annotation.selectedText,
            note = annotation.note,
            color = annotation.color.name,
            type = annotation.type.name,
            createdTime = annotation.createdTime.time,
            modifiedTime = annotation.modifiedTime.time,
            rangeLocatorJson = annotation.rangeLocator?.let { RangeLocatorCodec.toJson(it) },
            locatorQuality = annotation.locatorQuality.name
        )

        private fun parseQuality(raw: String): ResolutionQuality =
            runCatching { ResolutionQuality.valueOf(raw) }.getOrDefault(ResolutionQuality.UNRESOLVED)
    }
}
