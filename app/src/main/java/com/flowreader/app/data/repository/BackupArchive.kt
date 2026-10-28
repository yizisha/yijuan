package com.flowreader.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.flowreader.app.data.local.AppDatabase
import com.flowreader.app.domain.repository.BackupPreview
import com.flowreader.app.domain.repository.ImportResult
import com.flowreader.app.domain.repository.RestoreMode
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 逻辑快照 + 流式文件包。绝不复制或替换正在使用的 SQLite / DataStore 文件。 */
internal class BackupArchive(private val context: Context, private val database: AppDatabase) {
    private val stagingRoot get() = File(context.cacheDir, "backup-staging").apply { mkdirs() }

    suspend fun export(output: OutputStream) {
        val stage = newStage()
        try {
            val counts = snapshot(database, stage)
            val settings = BackupPreferences.snapshot(context)
            val assets = JSONArray()
            val paths = mutableSetOf<String>()
            val warnings = mutableListOf<String>()
            fun add(file: File, required: Boolean, publication: String? = null, relative: String? = null) {
                if (!file.isFile) {
                    if (required) error("书籍文件缺失，无法生成完整备份：${file.name}")
                    warnings += "缺失可选文件：${file.name}"
                    return
                }
                val canonical = file.canonicalPath
                if (!paths.add(canonical)) return
                val name = "assets/${assets.length()}"
                // 在打包前记录摘要，写包时再核对，避免期间文件变动产生不一致快照。
                assets.put(
                    JSONObject().put("entry", name).put("path", file.absolutePath)
                        .put("size", file.length()).put("sha256", digest(file))
                        .putOpt("publication", publication).putOpt("relative", relative)
                )
            }
            rows(stage, "books") { book ->
                val pub = book.nullableString("publicationId")
                if (pub != null) {
                    safeComponent(pub)
                    val root = File(context.filesDir, "books/$pub/revisions")
                    if (root.exists()) {
                        root.walkTopDown().filter { it.isFile }.forEach { file ->
                            val relative = "revisions/" + file.relativeTo(root).invariantSeparatorsPath
                            require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                            add(file, true, pub, relative)
                        }
                    }
                }
                add(File(book.getString("filePath")), true)
                book.nullableString("coverPath")?.takeIf { it.isNotBlank() }?.let { add(File(it), false) }
            }
            rows(stage, "chapters") { chapter ->
                Regex("\\[IMG:([^\\]]+)]").findAll(chapter.getString("content")).forEach { match -> add(File(match.groupValues[1]), true) }
            }
            listOf("custom_font_path", "reader_background_path").forEach { key ->
                settings.optJSONObject(key)?.optString("value")?.takeIf { it.isNotBlank() }?.let { add(File(it), true) }
            }
            val entries = JSONArray()
            TABLES.forEach { table ->
                val file = tableFile(stage, table)
                entries.put(
                    JSONObject().put("entry", "tables/$table.jsonl").put("size", file.length())
                        .put("sha256", digest(file)).put("rows", counts.getInt(table))
                )
            }
            val manifest = JSONObject().put("format", FORMAT).put("version", 1).put("schema", 12)
                .put("createdAt", System.currentTimeMillis()).put("tables", entries)
                .put("assets", assets).put("settings", settings).put("warnings", JSONArray(warnings))
            require(
                manifest.toString().toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES &&
                    assets.length() + TABLES.size + 1 <= MAX_ENTRIES
            ) {
                "备份清单过大"
            }
            val totalBytes = (0 until assets.length()).sumOf { assets.getJSONObject(it).getLong("size") } +
                TABLES.sumOf { tableFile(stage, it).length() }
            require(totalBytes <= MAX_BYTES) { "完整备份超过 8GB 上限" }
            validateDatabase(stage, manifest, originalFiles = true)
            ZipOutputStream(output.buffered()).use { zip ->
                writeEntry(zip, "manifest.json", manifest.toString().byteInputStream())
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    writeChecked(zip, entry, File(stage, entry.getString("entry")))
                }
                for (i in 0 until assets.length()) {
                    currentCoroutineContext().ensureActive()
                    val entry = assets.getJSONObject(i)
                    writeChecked(zip, entry, File(entry.getString("path")))
                }
            }
        } finally {
            stage.deleteRecursively()
        }
    }

    suspend fun prepare(input: InputStream, legacy: suspend (File, String) -> Unit): BackupPreview {
        val stage = newStage()
        try {
            val source = File(stage, "source")
            source.outputStream().use { copyCapped(input, it, MAX_BYTES) }
            val zip = source.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
            if (zip) {
                extract(source, stage)
            } else {
                require(source.length() <= MAX_LEGACY_BYTES) { "旧 JSON 备份超过 200MB 上限" }
                legacy(stage, source.readText(Charsets.UTF_8))
            }
            val manifest = manifest(stage)
            validateManifest(manifest, stage)
            validateDatabase(stage, manifest)
            val duplicates = duplicateMap(stage, manifest).size
            val counts = counts(manifest)
            return BackupPreview(
                stage.name, manifest.optLong("createdAt"), counts["books"] ?: 0,
                counts["bookmarks"] ?: 0, counts["annotations"] ?: 0, counts["categories"] ?: 0,
                manifest.getJSONArray("assets").length(), duplicates,
                manifest.getJSONObject("settings").length() > 0, manifest.optBoolean("legacy"),
                manifest.getJSONArray("warnings").strings()
            )
        } catch (error: Exception) {
            stage.deleteRecursively()
            throw error
        }
    }

    suspend fun legacyManifest(stage: File, legacyDb: AppDatabase, warnings: List<String>) {
        val count = snapshot(legacyDb, stage)
        val tables = JSONArray()
        TABLES.forEach { table ->
            val file = tableFile(stage, table)
            tables.put(
                JSONObject().put("entry", "tables/$table.jsonl").put("size", file.length())
                    .put("sha256", digest(file)).put("rows", count.getInt(table))
            )
        }
        File(stage, "manifest.json").writeText(
            JSONObject().put("format", FORMAT).put("version", 1)
                .put("schema", 12).put("legacy", true).put("createdAt", 0).put("tables", tables)
                .put("assets", JSONArray()).put("settings", JSONObject()).put("warnings", JSONArray(warnings))
                .toString(),
            Charsets.UTF_8
        )
    }

    suspend fun restore(token: String, mode: RestoreMode, restoreSettings: Boolean): ImportResult {
        val stage = stageFor(token)
        val manifest = manifest(stage)
        // 预览到确认之间仍重新验证。损坏、旧预览令牌或被替换的文件都不能写库。
        validateManifest(manifest, stage)
        validateDatabase(stage, manifest)
        require(mode != RestoreMode.REPLACE || !manifest.optBoolean("legacy")) { "旧 JSON 缺少正文文件，仅支持合并恢复" }
        val duplicates = if (mode == RestoreMode.MERGE) duplicateMap(stage, manifest) else emptyMap()
        val existingPublications = database.openHelper.readableDatabase.query(
            "SELECT publicationId FROM books WHERE publicationId IS NOT NULL"
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val pubMap = mutableMapOf<String, String>()
        val bookRows = mutableMapOf<Long, JSONObject>()
        rows(stage, "books") { book ->
            bookRows[book.getLong("id")] = book
            book.nullableString("publicationId")?.let { pub ->
                pubMap[pub] = duplicates[book.getLong("id")]?.nullableString("publicationId") ?: pub.takeIf {
                    it !in existingPublications && !File(context.filesDir, "books/$it").exists()
                } ?: UUID.randomUUID().toString()
            }
        }
        val batch = UUID.randomUUID().toString()
        val restoreRoot = File(context.filesDir, "restored/$batch")
        val allocated = mutableSetOf<File>()
        fun allocate(root: File) {
            if (root in allocated) return
            root.parentFile!!.mkdirs()
            require(root.mkdir()) { "恢复文件位置已被占用，请重新预览" }
            allocated += root
        }
        val pathMap = mutableMapOf<String, String>()
        val assets = manifest.getJSONArray("assets")
        val oldSettings = BackupPreferences.snapshot(context)
        var settingsApplied = false
        var committed = false
        var added = 0
        var marks = 0
        var notes = 0
        val neededPaths = mutableSetOf<String>()
        bookRows.filterKeys { it !in duplicates }.values.forEach { row ->
            neededPaths += row.getString("filePath")
            row.nullableString("coverPath")?.let { neededPaths += it }
        }
        rows(stage, "chapters") { row ->
            if (row.getLong("bookId") !in duplicates) {
                Regex("\\[IMG:([^\\]]+)]").findAll(row.getString("content")).forEach {
                    neededPaths += it.groupValues[1]
                }
            }
        }
        if (restoreSettings) {
            listOf("custom_font_path", "reader_background_path").forEach { key ->
                manifest.getJSONObject("settings").optJSONObject(key)?.getString("value")?.let { neededPaths += it }
            }
        }
        try {
            for (i in 0 until assets.length()) {
                currentCoroutineContext().ensureActive()
                val asset = assets.getJSONObject(i)
                val pub = asset.nullableString("publication")
                val target = if (pub == null) {
                    if (asset.getString("path") !in neededPaths) continue
                    allocate(restoreRoot)
                    File(
                        restoreRoot,
                        "$i.${File(asset.getString("path")).extension.filter { it.isLetterOrDigit() }.take(12).ifEmpty { "bin" }}"
                    )
                } else {
                    val mapped = pubMap.getValue(pub)
                    val root = File(context.filesDir, "books/$mapped")
                    if (mapped in duplicates.values.mapNotNull { it.nullableString("publicationId") }) {
                        pathMap[asset.getString("path")] = File(root, asset.getString("relative")).absolutePath
                        continue
                    }
                    allocate(root)
                    File(root, asset.getString("relative"))
                }
                require(!target.exists()) { "恢复文件位置冲突，请重新预览" }
                target.parentFile!!.mkdirs()
                File(stage, asset.getString("entry")).copyTo(target)
                pathMap[asset.getString("path")] = target.absolutePath
            }
            val replacementPaths = pathMap.entries.sortedByDescending { it.key.length }
            fun rewrite(value: String): String {
                var text = value
                replacementPaths.forEach { (old, new) -> text = text.replace(old, new) }
                pubMap.forEach { (old, new) ->
                    if (text.startsWith(
                            "books/$old/revisions/"
                        )
                    ) {
                        text = "books/$new/revisions/" + text.removePrefix("books/$old/revisions/")
                    }
                }
                // 仅修改 JSON 的 publicationId 字段；不能全局替换正文中的普通字符串。
                if (text.startsWith("{") || text.startsWith("[")) {
                    fun remap(node: Any) {
                        when (node) {
                            is JSONObject -> node.keys().asSequence().toList().forEach { key ->
                                val child = node.get(key)
                                if (key == "publicationId" && child is String && child in pubMap) {
                                    node.put(
                                        key,
                                        pubMap.getValue(child)
                                    )
                                } else {
                                    remap(child)
                                }
                            }
                            is JSONArray -> for (index in 0 until node.length()) remap(node.get(index))
                        }
                    }
                    val node = runCatching { if (text.startsWith("{")) JSONObject(text) else JSONArray(text) }.getOrNull()
                    if (node != null) {
                        remap(node)
                        text = node.toString()
                    }
                }
                return pubMap[text] ?: text
            }
            // 正文包 manifest 里的身份也必须一致；文件相对路径保持不变。
            allocated.filter { it.parentFile?.name == "books" }.forEach { root ->
                root.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
                    require(file.length() <= MAX_ROW_BYTES) { "内容 JSON 过大" }
                    file.writeText(rewrite(file.readText(Charsets.UTF_8)), Charsets.UTF_8)
                }
            }
            currentCoroutineContext().ensureActive()
            // 一旦进入提交阶段，先完整结束事务；不能让取消发生在提交返回与文件归属标记之间。
            withContext(NonCancellable) {
                database.withTransaction {
                    val db = database.openHelper.writableDatabase
                    if (mode == RestoreMode.REPLACE) TABLES.asReversed().forEach { db.execSQL("DELETE FROM `$it`") }
                    val categories = mergeNamed(stage, db, "categories", mode)
                    val lists = mergeNamed(stage, db, "reading_lists", mode)
                    val bookMap = mutableMapOf<Long, Long>()
                    bookRows.forEach { (oldId, source) ->
                        val existing = duplicates[oldId]?.let { candidate ->
                            queryOne(db, "SELECT * FROM books WHERE id = ?", arrayOf(candidate.getLong("id")))
                        }
                        if (existing != null) {
                            // 合并不倒退现有进度、正文或元数据，收藏取并集。
                            bookMap[oldId] = existing.getLong("id")
                            if (existing.nullableString("seriesName") == null && source.nullableString("seriesName") != null) {
                                db.execSQL("UPDATE books SET seriesName=?, seriesOrder=? WHERE id=?", arrayOf(
                                    source.getString("seriesName"), source.optInt("seriesOrder", 0), existing.getLong("id")))
                            }
                            if (source.optInt("isFavorite") != 0) {
                                db.execSQL(
                                    "UPDATE books SET isFavorite=1 WHERE id=?",
                                    arrayOf(existing.getLong("id"))
                                )
                            }
                        } else {
                            val row = transform(source, ::rewrite)
                            if (!manifest.optBoolean("legacy") && source.nullableString("coverPath")?.let {
                                    it !in pathMap
                                } == true
                            ) {
                                row.put("coverPath", JSONObject.NULL)
                            }
                            if (mode == RestoreMode.MERGE) row.remove("id")
                            row.put("categoryId", source.nullableLong("categoryId")?.let { categories[it] } ?: JSONObject.NULL)
                            bookMap[oldId] = insert(db, "books", row)
                            added++
                        }
                    }
                    TABLES.filter { it !in setOf("categories", "reading_lists", "books") }.forEach { table ->
                        rows(stage, table) { source ->
                            val oldBook = source.getLong("bookId")
                            val row = transform(source, ::rewrite).put("bookId", bookMap.getValue(oldBook))
                            if (table == "reading_list_items") row.put("listId", lists.getValue(source.getLong("listId")))
                            val duplicate = oldBook in duplicates
                            if (duplicate && table in CONTENT_TABLES) return@rows
                            if (mode == RestoreMode.MERGE && row.has("id")) row.remove("id")
                            if (table == "bookmarks" || table == "annotations") {
                                val equivalent = if (table == "bookmarks") {
                                    queryOne(
                                        db,
                                        "SELECT id FROM bookmarks WHERE bookId=? AND chapterIndex=? " +
                                            "AND position=? AND text=? AND createdTime=?",
                                        arrayOf(
                                            row.getLong("bookId"),
                                            row.getInt("chapterIndex"),
                                            row.getInt("position"),
                                            row.getString("text"),
                                            row.getLong("createdTime")
                                        )
                                    )
                                } else {
                                    queryOne(
                                        db,
                                        "SELECT * FROM annotations WHERE bookId=? AND chapterIndex=? AND startPosition=? " +
                                            "AND endPosition=? AND selectedText=? AND createdTime=?",
                                        arrayOf(
                                            row.getLong("bookId"),
                                            row.getInt("chapterIndex"),
                                            row.getInt("startPosition"),
                                            row.getInt("endPosition"),
                                            row.getString("selectedText"),
                                            row.getLong("createdTime")
                                        )
                                    )
                                }
                                if (equivalent != null) {
                                    if (table == "annotations" && row.getLong("modifiedTime") > equivalent.getLong("modifiedTime")) {
                                        row.put("id", equivalent.getLong("id"))
                                        insert(db, table, row, SQLiteDatabase.CONFLICT_REPLACE)
                                        notes++
                                    }
                                    return@rows
                                }
                            }
                            if (table == "reading_stats" && duplicate) {
                                val current =
                                    queryOne(
                                        db,
                                        "SELECT * FROM reading_stats WHERE bookId=? AND date=?",
                                        arrayOf(row.getLong("bookId"), row.getString("date"))
                                    )
                                if (current != null) {
                                    row.put("id", current.getLong("id"))
                                    row.keys().asSequence().filter { it !in setOf("id", "bookId", "date") }.toList().forEach { key ->
                                        row.put(key, maxOf(row.getLong(key), current.getLong(key)))
                                    }
                                    insert(db, table, row, SQLiteDatabase.CONFLICT_REPLACE)
                                    return@rows
                                }
                            }
                            val conflict = if (table in setOf("reading_list_items", "reading_session_deltas")) {
                                SQLiteDatabase.CONFLICT_IGNORE
                            } else {
                                SQLiteDatabase.CONFLICT_ABORT
                            }
                            insert(db, table, row, conflict)
                            if (table == "bookmarks") marks++
                            if (table == "annotations") notes++
                        }
                    }
                    checkReferences(db)
                    if (restoreSettings && manifest.getJSONObject("settings").length() > 0) {
                        settingsApplied = true
                        BackupPreferences.apply(context, manifest.getJSONObject("settings"), ::rewrite)
                    }
                }
                committed = true
            }
            database.invalidationTracker.refreshVersionsAsync()
            discard(token)
            return ImportResult(added, marks, notes, duplicates.size, manifest.getJSONArray("warnings").strings())
        } finally {
            if (!committed) {
                withContext(NonCancellable) {
                    try {
                        if (settingsApplied) BackupPreferences.apply(context, oldSettings)
                    } finally {
                        allocated.forEach { it.deleteRecursively() }
                    }
                }
            }
        }
    }

    fun discard(token: String) {
        stageFor(token).deleteRecursively()
    }

    private suspend fun duplicateMap(stage: File, manifest: JSONObject): Map<Long, JSONObject> {
        val existing = mutableListOf<JSONObject>()
        database.withTransaction {
            database.openHelper.readableDatabase.query("SELECT * FROM books").use { c -> while (c.moveToNext()) existing += cursorRow(c) }
        }
        val result = mutableMapOf<Long, JSONObject>()
        val byPath = (0 until manifest.getJSONArray("assets").length()).associate { index ->
            val asset = manifest.getJSONArray("assets").getJSONObject(index)
            asset.getString("path") to asset.getString("sha256")
        }
        val hashes = mutableMapOf<String, String>()
        rows(stage, "books") { source ->
            val found = existing.firstOrNull { current ->
                val sourcePublication = source.nullableString("publicationId")
                val samePub = sourcePublication != null && sourcePublication == current.nullableString("publicationId")
                val sameRevision = source.nullableString("activeRevision") == current.nullableString("activeRevision")
                if (!sameRevision) {
                    false
                } else if (samePub) {
                    true
                } else {
                    val hash = byPath[source.getString("filePath")]
                    val file = File(current.getString("filePath"))
                    if (hash != null && file.isFile) {
                        hash == hashes.getOrPut(file.absolutePath) { digest(file) }
                    } else {
                        manifest.optBoolean("legacy") &&
                            source.getString("filePath") == current.getString("filePath") &&
                            source.getString("title") == current.getString("title")
                    }
                }
            }
            if (found != null) result[source.getLong("id")] = found
        }
        return result
    }

    private suspend fun snapshot(db: AppDatabase, stage: File): JSONObject {
        val counts = JSONObject()
        db.withTransaction {
            TABLES.forEach { table ->
                var count = 0
                val file = tableFile(stage, table).apply { parentFile!!.mkdirs() }
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    db.openHelper.readableDatabase.query("SELECT * FROM `$table`").use { cursor ->
                        while (cursor.moveToNext()) {
                            currentCoroutineContext().ensureActive()
                            val line = cursorRow(cursor).toString()
                            require(line.length <= MAX_ROW_BYTES) { "单条 $table 记录过大" }
                            writer.appendLine(line)
                            count++
                        }
                    }
                }
                require(file.length() <= MAX_TABLE_BYTES) { "备份索引过大：$table" }
                counts.put(table, count)
            }
        }
        return counts
    }

    private suspend fun extract(source: File, stage: File) {
        // ZipInputStream 只验证局部条目；中央目录缺失的截断文件也必须拒绝。
        java.util.zip.ZipFile(source).use { require(it.size() in TABLES.size + 1..MAX_ENTRIES) { "备份包不完整" } }
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(
                    name == "manifest.json" || name in TABLES.map { "tables/$it.jsonl" } || name.matches(Regex("assets/[0-9]+"))
                ) { "非法备份条目：$name" }
                require(!entry.isDirectory && seen.add(name) && seen.size <= MAX_ENTRIES) { "重复或过多备份条目" }
                val limit = when {
                    name == "manifest.json" -> MAX_MANIFEST_BYTES
                    name.startsWith("tables/") -> MAX_TABLE_BYTES
                    else -> MAX_BYTES
                }
                val target = File(stage, name).apply { parentFile!!.mkdirs() }
                val size = target.outputStream().use { copyCapped(zip, it, minOf(limit, MAX_BYTES - total)) }
                total += size
                zip.closeEntry()
            }
        }
    }

    private fun validateManifest(manifest: JSONObject, stage: File) {
        require(
            manifest.getString("format") == FORMAT && manifest.getInt("version") == 1 && manifest.getInt("schema") in 11..12
        ) { "不支持的备份格式或数据库版本" }
        BackupPreferences.validate(manifest.getJSONObject("settings"))
        val entries = mutableSetOf<String>()
        val tables = manifest.getJSONArray("tables")
        require(tables.length() == TABLES.size)
        val assets = manifest.getJSONArray("assets")
        val sourcePaths = mutableSetOf<String>()
        val publications = mutableSetOf<Pair<String, String>>()
        for (array in listOf(tables, assets)) for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val name = item.getString("entry")
            val table = array === tables
            require(if (table) name in TABLES.map { "tables/$it.jsonl" } else name.matches(Regex("assets/[0-9]+")))
            require(entries.add(name))
            val file = File(stage, name)
            require(file.isFile && file.length() == item.getLong("size") && digest(file) == item.getString("sha256")) { "备份文件校验失败：$name" }
            if (!table) {
                require(sourcePaths.add(item.getString("path")))
                item.nullableString("publication")?.let { pub ->
                    safeComponent(pub)
                    val relative = item.getString("relative")
                    safeRelative(relative)
                    require(relative.startsWith("revisions/") && publications.add(pub to relative))
                }
            }
        }
        require(
            stage.walkTopDown().filter {
                it.isFile && (it.parentFile?.name == "assets" || it.parentFile?.name == "tables")
            }.all { it.relativeTo(stage).invariantSeparatorsPath in entries }
        ) { "备份含未声明的文件" }
    }

    private suspend fun validateDatabase(stage: File, manifest: JSONObject, originalFiles: Boolean = false) {
        val tempFile = File(stage, "validation.db")
        // Room 建独立临时库，仅用于验证 schema、约束与关系。
        val temp = Room.databaseBuilder(context, AppDatabase::class.java, tempFile.absolutePath).build()
        try {
            temp.withTransaction {
                val db = temp.openHelper.writableDatabase
                TABLES.asReversed().forEach { db.execSQL("DELETE FROM `$it`") }
                TABLES.forEach { table ->
                    val columns = db.query("PRAGMA table_info(`$table`)").use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                    }
                    var count = 0
                    rows(stage, table) { row ->
                        require(row.keys().asSequence().toSet() == columns) { "$table 备份字段不完整" }
                        if (table == "books") {
                            row.nullableString("seriesName")?.let { require(com.flowreader.app.domain.model.BookSeries.requireName(it) == it) }
                            require(row.optInt("seriesOrder", -1) >= 0) { "系列卷序无效" }
                        }
                        insert(db, table, row)
                        count++
                    }
                    require(count == counts(manifest).getValue(table)) { "$table 记录数量不一致" }
                }
                checkReferences(db)
            }
            if (!manifest.optBoolean("legacy")) validateFiles(stage, temp, manifest, originalFiles)
        } finally {
            temp.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(tempFile.absolutePath + it).delete() }
        }
    }

    private suspend fun validateFiles(stage: File, temp: AppDatabase, manifest: JSONObject, originalFiles: Boolean) {
        val assets = manifest.getJSONArray("assets")
        val paths = mutableSetOf<String>()
        val content = mutableMapOf<Pair<String, String>, File>()
        for (i in 0 until assets.length()) {
            val entry = assets.getJSONObject(i)
            paths += entry.getString("path")
            entry.nullableString(
                "publication"
            )?.let { pub ->
                content[pub to entry.getString("relative")] = if (originalFiles) {
                    File(entry.getString("path"))
                } else {
                    File(stage, entry.getString("entry"))
                }
            }
        }
        listOf("custom_font_path", "reader_background_path").forEach { key ->
            manifest.getJSONObject("settings").optJSONObject(key)?.getString("value")?.let { require(it in paths) { "设置引用的文件缺失：$key" } }
        }
        rows(stage, "books") { book ->
            require(book.getString("filePath") in paths) { "缺少书籍正文文件：${book.getString("title")}" }
            val pub = book.nullableString("publicationId")
            val revision = book.nullableString("activeRevision")
            if (revision != null) {
                require(pub != null)
                safeComponent(revision)
                val prefix = "revisions/$revision/"
                val file = content[pub to "${prefix}manifest.json"] ?: error("缺少正文索引：${book.getString("title")}")
                require(file.length() <= MAX_ROW_BYTES)
                val packageJson = JSONObject(file.readText(Charsets.UTF_8))
                require(packageJson.getString("publicationId") == pub && packageJson.getString("revision") == revision)
                fun need(relative: String) {
                    safeRelative(relative)
                    require(content.containsKey(pub to "$prefix$relative")) { "正文包文件缺失：$relative" }
                }
                packageJson.optJSONArray("documents")?.let { docs ->
                    for (i in 0 until docs.length()) {
                        val doc = docs.getJSONObject(i)
                        need(doc.getString("jsonRelativePath"))
                        need(doc.getString("textRelativePath"))
                    }
                }
                packageJson.optJSONArray("resources")?.let { resources ->
                    for (i in 0 until resources.length()) need(resources.getJSONObject(i).getString("relativePath"))
                }
                require(
                    temp.openHelper.readableDatabase.query(
                        "SELECT 1 FROM publication_revisions WHERE bookId=? AND revision=?",
                        arrayOf<Any>(book.getLong("id"), revision)
                    ).use {
                        it.moveToFirst()
                    }
                )
            }
        }
    }

    private fun mergeNamed(stage: File, db: SupportSQLiteDatabase, table: String, mode: RestoreMode): Map<Long, Long> {
        val mapping = mutableMapOf<Long, Long>()
        rows(stage, table) { source ->
            val current = if (mode == RestoreMode.MERGE) {
                queryOne(
                    db,
                    "SELECT id FROM `$table` WHERE name=?",
                    arrayOf(source.getString("name"))
                )
            } else {
                null
            }
            val row = JSONObject(source.toString())
            if (mode == RestoreMode.MERGE) row.remove("id")
            mapping[source.getLong("id")] = current?.getLong("id") ?: insert(db, table, row)
        }
        return mapping
    }

    private fun checkReferences(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "备份存在无效关联" } }
        db.query("SELECT 1 FROM books WHERE categoryId IS NOT NULL AND categoryId NOT IN (SELECT id FROM categories) LIMIT 1").use {
            require(!it.moveToFirst()) { "书籍分组缺失" }
        }
        db.query(
            "SELECT 1 FROM reading_stats WHERE bookId NOT IN (SELECT id FROM books) LIMIT 1"
        ).use { require(!it.moveToFirst()) { "统计对应书籍缺失" } }
    }

    private fun insert(db: SupportSQLiteDatabase, table: String, json: JSONObject, conflict: Int = SQLiteDatabase.CONFLICT_ABORT): Long {
        require(table in TABLES)
        val values = ContentValues()
        json.keys().forEach { key ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            when (val value = json.get(key)) {
                JSONObject.NULL -> values.putNull("`$key`")
                is String -> values.put("`$key`", value)
                is Int -> values.put("`$key`", value)
                is Long -> values.put("`$key`", value)
                is Number -> values.put("`$key`", value.toDouble())
                else -> error("不支持的数据库值：$table.$key")
            }
        }
        return db.insert(table, conflict, values)
    }

    private fun transform(source: JSONObject, rewrite: (String) -> String): JSONObject = JSONObject().apply {
        source.keys().forEach { key ->
            val value = source.get(key)
            put(key, if (value is String) rewrite(value) else value)
        }
    }

    private fun cursorRow(cursor: Cursor): JSONObject = JSONObject().apply {
        cursor.columnNames.forEachIndexed { index, column ->
            put(
                column,
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    else -> error("不支持的数据库列类型：$column")
                }
            )
        }
    }

    private fun queryOne(db: SupportSQLiteDatabase, sql: String, args: Array<out Any>): JSONObject? = db.query(sql, args).use {
        if (it.moveToFirst()) cursorRow(it) else null
    }
    private fun counts(manifest: JSONObject): Map<String, Int> = manifest.getJSONArray("tables").let { array ->
        (0 until array.length()).associate { i ->
            val item = array.getJSONObject(i)
            item.getString("entry").removePrefix("tables/").removeSuffix(".jsonl") to item.getInt("rows")
        }
    }
    private fun manifest(stage: File): JSONObject {
        val file = File(stage, "manifest.json")
        require(file.isFile && file.length() <= MAX_MANIFEST_BYTES) { "备份清单缺失或过大" }
        return JSONObject(file.readText(Charsets.UTF_8))
    }
    private fun stageFor(token: String): File {
        require(token.matches(Regex("[a-f0-9-]{36}"))) { "无效恢复预览" }
        return File(stagingRoot, token).also { require(it.isDirectory) { "恢复预览已失效，请重新选择备份" } }
    }
    private fun newStage() = File(stagingRoot, UUID.randomUUID().toString()).apply { mkdirs() }
    private fun tableFile(stage: File, table: String) = File(stage, "tables/$table.jsonl")
    private fun safeComponent(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "非法内容身份" }
    }
    private fun safeRelative(value: String) {
        require(
            value.length in 1..1024 && !value.startsWith("/") && !value.contains('\\') && !value.contains(':') && value.split('/').all {
                it.isNotEmpty() && it != "." && it != ".."
            }
        ) { "非法正文包路径" }
    }
    private suspend fun writeChecked(zip: ZipOutputStream, metadata: JSONObject, file: File) {
        require(file.length() == metadata.getLong("size")) { "备份期间文件发生变化" }
        val hash = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(metadata.getString("entry")))
        var bytes = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                bytes += read
                hash.update(buffer, 0, read)
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
        require(bytes == metadata.getLong("size") && hex(hash.digest()) == metadata.getString("sha256")) { "备份期间文件发生变化，请重试" }
    }
    private fun writeEntry(zip: ZipOutputStream, name: String, input: InputStream) {
        zip.putNextEntry(ZipEntry(name))
        input.use { it.copyTo(zip) }
        zip.closeEntry()
    }
    private suspend fun copyCapped(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "备份超过大小上限或可用空间不足" }
            output.write(buffer, 0, read)
        }
        return total
    }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_LEGACY_BYTES = 200L * 1024 * 1024
        private const val MAX_TABLE_BYTES = 512L * 1024 * 1024
        private const val MAX_ROW_BYTES = 16L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
        private const val MAX_ENTRIES = 100_000
        private const val FORMAT = "yejian-library"
        val TABLES =
            listOf(
                "categories",
                "reading_lists",
                "books",
                "chapters",
                "publication_revisions",
                "publication_documents",
                "reading_sections",
                "toc_nodes",
                "reader_states",
                "content_migration_jobs",
                "bookmarks",
                "annotations",
                "reading_stats",
                "reading_list_items",
                "reading_session_deltas"
            )
        private val CONTENT_TABLES =
            setOf(
                "chapters",
                "publication_revisions",
                "publication_documents",
                "reading_sections",
                "toc_nodes",
                "reader_states",
                "content_migration_jobs"
            )

        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hex(hash.digest())
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun rows(stage: File, table: String, consume: (JSONObject) -> Unit) {
            val manifestFile = File(stage, "manifest.json")
            val oldBooks = table == "books" && manifestFile.isFile &&
                JSONObject(manifestFile.readText(Charsets.UTF_8)).optInt("schema") == 11
            File(stage, "tables/$table.jsonl").bufferedReader(Charsets.UTF_8).use { reader ->
                // 限制单行长度，不能直接 readLine() 后才检查（恶意单行会先耗尽内存）。
                val line = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    for (i in 0 until count) {
                        val char = buffer[i]
                        if (char == '\n') {
                            require(line.isNotEmpty())
                            val row = JSONObject(line.toString())
                            if (oldBooks) {
                                if (!row.has("seriesName")) row.put("seriesName", JSONObject.NULL)
                                if (!row.has("seriesOrder")) row.put("seriesOrder", 0)
                            }
                            consume(row)
                            line.setLength(0)
                        } else {
                            line.append(char)
                            require(line.length <= MAX_ROW_BYTES) { "单条备份记录过大" }
                        }
                    }
                }
                require(line.isEmpty()) { "备份记录不完整" }
            }
        }
        private fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
        private fun JSONObject.nullableLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
