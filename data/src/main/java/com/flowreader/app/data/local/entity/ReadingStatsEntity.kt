package com.flowreader.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Separate the restarted foreground clock from all historical counters, without a schema change. */
const val CURRENT_READING_STATS_VERSION = 3

@Entity(
    tableName = "reading_stats",
    indices = [
        androidx.room.Index(value = ["bookId", "date"], unique = true),
        androidx.room.Index(value = ["date"])
    ]
)
data class ReadingStatsEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bookId: Long,
    val date: String,
    /** 历史页数：v2 之前的口径，**不覆写、不倒推**，展示为“历史页数”。 */
    val readPages: Int = 0,
    val readChapters: Int = 0,
    /** 历史时长（秒）：来源为旧口径，保留原值。 */
    val readTimeSeconds: Long = 0,
    val lastReadTime: Long = System.currentTimeMillis(),

    // ---- v10 有效阅读 v2（主计划 10.5）。与上面三列**分开展示与聚合**，防止同一段时间在
    // 旧/新字段里各算一次。默认值必须与 MIGRATION_9_10 的 DEFAULT 完全一致，否则 Room 打开
    // 数据库时的 schema 校验会失败。
    /** 满足前台有效规则的正文阅读时长（毫秒，单调时钟）。TTS 播放期间不累计。 */
    @ColumnInfo(defaultValue = "0")
    val visualReadMillis: Long = 0,
    /** TTS 实际处于播放状态的时长（毫秒）。与 visualReadMillis 互斥。 */
    @ColumnInfo(defaultValue = "0")
    val ttsReadMillis: Long = 0,
    /** 满足可见停留规则的非空白 code point 数，会话内去重。 */
    @ColumnInfo(defaultValue = "0")
    val qualifiedTextUnits: Long = 0,
    /** 被接受且完成的页面/屏导航次数（不是“阅读页数”）。 */
    @ColumnInfo(defaultValue = "0")
    val settledTurns: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val statsVersion: Int = 1
)
