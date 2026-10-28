package com.flowreader.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.flowreader.app.data.local.entity.ReaderStateEntity
import com.flowreader.app.data.local.entity.ReadingSessionDeltaEntity
import com.flowreader.app.data.local.entity.ReadingStatsEntity

/** 写入结果：调用方必须区分“已保存 / 过期被拒 / 失败”，不能盲覆盖。 */
enum class ReaderStateSaveOutcome { SAVED, STALE_REJECTED }

/**
 * 阅读状态与阅读增量（主计划 5.2 / 6.7 / 10.5）。
 *
 * 两条硬约束：
 * - **位置只有一个权威源**：[ReaderStateEntity.locatorJson]；`navigationSequence` 单调递增，
 *   旧 sequence 不得覆盖新 sequence（防止慢的旧写入晚于新章节落地，F04/§6.7）。
 * - **增量幂等**：`(sessionId, sequence, localDate)` 唯一；重复提交被识别后**不再**累加日统计，
 *   因此重试安全，取消/重进也不会重复消费同一快照（T080）。
 */
@Dao
interface ReaderStateDao {

    // ---------------------------------------------------------------- 阅读状态

    @Query("SELECT * FROM reader_states WHERE bookId = :bookId")
    suspend fun getState(bookId: Long): ReaderStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: ReaderStateEntity)

    /**
     * 带序列检查的保存。旧 sequence 直接拒绝，返回值必须被调用方检查。
     */
    @Transaction
    suspend fun saveIfNewer(state: ReaderStateEntity): ReaderStateSaveOutcome {
        val current = getState(state.bookId)
        if (current != null && current.navigationSequence > state.navigationSequence) {
            return ReaderStateSaveOutcome.STALE_REJECTED
        }
        upsertState(state)
        return ReaderStateSaveOutcome.SAVED
    }

    @Query("SELECT navigationSequence FROM reader_states WHERE bookId = :bookId")
    suspend fun getSequence(bookId: Long): Long?

    @Query("SELECT COUNT(*) FROM reader_states")
    suspend fun countStates(): Int

    // ---------------------------------------------------------------- 阅读增量

    /**
     * 幂等插入。命中 `(sessionId, sequence, localDate)` 唯一键时返回 -1，表示该增量已被消费。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelta(delta: ReadingSessionDeltaEntity): Long

    @Query("SELECT COUNT(*) FROM reading_session_deltas WHERE bookId = :bookId")
    suspend fun countDeltas(bookId: Long): Int

    @Query("SELECT * FROM reading_session_deltas WHERE bookId = :bookId ORDER BY sequence")
    suspend fun getDeltas(bookId: Long): List<ReadingSessionDeltaEntity>

    /**
     * 把 v2 增量累加到当日 `reading_stats` 的**既有行**。
     *
     * 刻意不用 `INSERT … ON CONFLICT DO UPDATE`：SQLite 的 UPSERT 需要 3.24+，而本项目 minSdk 26
     * （Android 8.0）自带 SQLite 3.18，真机上会直接语法错误。改为「先 UPDATE，未命中再 INSERT」，
     * 两步都在 [commitDelta] 的同一事务内，配合 (bookId,date) 唯一索引即可幂等且可移植。
     *
     * 只累加 v2 列，历史 `readPages`/`readTimeSeconds` 原样不动，避免同一段时间在旧/新字段里
     * 各算一次（主计划 10.5）。
     *
     * @return 受影响行数；0 表示当日还没有该书的统计行。
     */
    @Query(
        "UPDATE reading_stats SET " +
            "visualReadMillis = visualReadMillis + :visual, " +
            "ttsReadMillis = ttsReadMillis + :tts, " +
            "qualifiedTextUnits = qualifiedTextUnits + :units, " +
            "settledTurns = settledTurns + :turns, " +
            "statsVersion = 2, " +
            "lastReadTime = :now " +
            "WHERE bookId = :bookId AND date = :date"
    )
    suspend fun accumulateV2IntoExisting(bookId: Long, date: String, now: Long, visual: Long, tts: Long, units: Long, turns: Int): Int

    /** 当日尚无统计行时插入一条只含 v2 分量的新行（历史列保持 0）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatsRow(stats: ReadingStatsEntity): Long

    /**
     * 幂等提交：插入 delta 与累加日统计在**同一事务**内完成。
     *
     * @return true 表示本次真的累加了；false 表示该 delta 之前已提交过（重试/重复消费）。
     */
    @Transaction
    suspend fun commitDelta(delta: ReadingSessionDeltaEntity, now: Long): Boolean {
        val rowId = insertDelta(delta)
        if (rowId == -1L) return false
        val updated = accumulateV2IntoExisting(
            bookId = delta.bookId,
            date = delta.localDate,
            now = now,
            visual = delta.visualReadMillis,
            tts = delta.ttsReadMillis,
            units = delta.qualifiedTextUnits,
            turns = delta.settledTurns
        )
        if (updated == 0) {
            insertStatsRow(
                ReadingStatsEntity(
                    bookId = delta.bookId,
                    date = delta.localDate,
                    readPages = 0,
                    readChapters = 0,
                    readTimeSeconds = 0,
                    lastReadTime = now,
                    visualReadMillis = delta.visualReadMillis,
                    ttsReadMillis = delta.ttsReadMillis,
                    qualifiedTextUnits = delta.qualifiedTextUnits,
                    settledTurns = delta.settledTurns,
                    statsVersion = 2
                )
            )
        }
        return true
    }
}
