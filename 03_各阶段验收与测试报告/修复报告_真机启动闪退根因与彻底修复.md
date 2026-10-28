# FlowReader 内容引擎改造 — 闪退修复报告（真机验证）

执行者：DSH Agent。日期：2026-09-10。触发条件：用户反馈"用 S3 完成那个版本（Gate G3 后的 APK）进去就闪退"。
设备：小米 PD2408 / V2408A（`adb` 序列号 `10AF641GRU007U7`），用户真实库（25 本书 / 3274 章）。

---

## 1. 结论

| 项 | 内容 |
|---|---|
| **症状** | 应用一启动即闪退，无法进入书架 |
| **根因** | `BookEntity` 未声明 `index_books_publicationId`，而 `MIGRATION_9_10` 建了这个**唯一索引** → Room `TableInfo` 校验失败 → 每次开库抛 `IllegalStateException` |
| **异常** | `java.lang.IllegalStateException: Migration didn't properly handle: books(com.flowreader.app.data.local.entity.BookEntity)`，`at androidx.room.RoomOpenHelper.onUpgrade(RoomOpenHelper.kt:93)` |
| **修复** | 在实体上补声明该唯一索引 |
| **状态** | **已修复并在真机验证通过**：应用正常启动、无崩溃；迁移后用户数据完整 |

---

## 2. 现场证据（设备 logcat）

```
09-10 23:16:58.007 E AndroidRuntime: FATAL EXCEPTION: main
09-10 23:16:58.007 E AndroidRuntime: Process: com.flowreader.app, PID: 20716
09-10 23:16:58.007 E AndroidRuntime: java.lang.IllegalStateException:
    Migration didn't properly handle: books(com.flowreader.app.data.local.entity.BookEntity).
09-10 23:16:58.007 E AndroidRuntime:  Expected: … indices=[index_books_lastReadTime_addedTime,
    index_books_author, index_books_categoryId, index_books_title]      ← 实体声明（4 条，没有 publicationId）
09-10 23:16:58.007 E AndroidRuntime:  Found:    … indices=[index_books_publicationId (unique),
    index_books_author, index_books_title, index_books_categoryId,
    index_books_lastReadTime_addedTime]                                 ← 迁移结果（多一条唯一索引）
09-10 23:16:58.007 E AndroidRuntime: 	at androidx.room.RoomOpenHelper.onUpgrade(RoomOpenHelper.kt:93)
09-10 23:16:58.007 E AndroidRuntime: 	at com.flowreader.app.data.local.dao.CategoryDao_Impl$9.call(CategoryDao_Impl.java:190)
```

两处 `TableInfo` 的**唯一差异就是这条索引**（列定义逐字相同）。触发点是书架打开时 `CategoryDao` 的第一个查询
—— 也就是**开库**这一步，所以表现为"一进去就闪退"。

---

## 3. 为什么本地单测没抓到（这是本报告最需要记住的部分）

`ContentEngineMigration9To10Test` 当时**全绿**，因为它的 v9 建库脚本只执行 `createSql` 建表，
并注释着「只建表，不建索引，索引对本次断言无关」：

```kotlin
// 修复前的测试夹具（错误示范）
db.execSQL(createSql)   // ← 索引被跳过了
```

于是夹具比真机"干净"：真机 v9 库带着 `index_books_author` 等 4 条索引，
迁移再补 1 条，Room 拿**实体期望**（4 条）与**实际**（5 条）比对 → 失败。
**夹具少建一条索引，迁移测试就变成了在测一个不存在的世界。**

已修两处：

1. **测试夹具加固**：`createV9Database` 现在按 `9.json` 的 `indices` 逐条建索引
   （含 `unique` 标志），夹具形状与真机一致。
2. **产品侧的正确性**：实体补上 `Index(value = ["publicationId"], unique = true)`，
   与迁移 SQL、导出 schema 三者一致。

---

## 4. 顺带修掉的第二个真缺陷：内容身份字段没有往返映射

`BookEntity.toDomain()` / `fromDomain()` 起初漏了 `publicationId` / `activeRevision`（以及 domain 侧缺 `contentState`）：

- 任何一次 load→save 往返都会把它们丢掉；
- 后果：**重新导入同一本书会拿到新的 UUID，旧内容包立刻变成孤儿引用**，迁移回填 UUID 的意义被抵消。

已补齐三处映射，并加了两条测试（`contentIdentityFieldsSurviveDomainEntityRoundTrip`、
`legacyBookWithoutContentIdentityRoundTripsAsNull`）钉住 legacy 书（`publicationId = null`）也不崩。

---

## 5. 真机验证（本次实际执行的证据）

### 5.1 启动与迁移

| 步骤 | 命令 | 结果 |
|---|---|---|
| 安装 | `adb install -r app-debug.apk` | Success |
| 启动 | `adb shell am start -n com.flowreader.app/.MainActivity` | 进程存活 `pid=9025` |
| 崩溃缓冲 | `adb logcat -d -b crash` | **空（无崩溃）** |
| 前台 Activity | `dumpsys activity activities` | `topResumedActivity=com.flowreader.app/.MainActivity` |

### 5.2 迁移后用户数据完整性（直查设备库）

```
version=10
books=25          chapters=3274      categories=1      stats=35
bookmarks=0       annotations=0
tbl: books chapters bookmarks annotations categories reading_stats reading_lists
     reading_list_items publication_revisions publication_documents reading_sections
     toc_nodes reader_states content_migration_jobs reading_session_deltas
idx_pub=1
books: 25 行全部有 publicationId（迁移内 UUID 回填生效）
```

7 张新表与 `index_books_publicationId` 全部就位；旧数据一行未少。

### 5.3 真机端到端导入（新增 instrumentation 测试）

新增 `app/src/androidTest/.../PublicationImportOnDeviceTest.kt`：**在真机上**跑完整内容引擎链路
（真实设备 SQLite/WAL、真实 `filesDir`），而不是 Robolectric 的模拟实现。

```
adb shell am instrument -w -e class \
  com.flowreader.app.util.epub.PublicationImportOnDeviceTest \
  com.flowreader.app.test/androidx.test.runner.AndroidJUnitRunner
→ OK (1 test)
```

断言覆盖：4 个 section（同 XHTML 三锚点）→ 主线文本量 > 0 → revision 目录 / `manifest.json` /
`source/original.bin` 落盘 → 资源文件名 = `resourceId` 且 `contentHash` 与实际字节一致
（逐条读 `manifest.json` 核对）→ 单事务激活后 `publication_revisions/documents/sections/toc_nodes`
在真机库里就位 → `Σ section = revision 总量` → `books.activeRevision` / `contentState=READY` 已切换
→ legacy 章节投影 4 章、卷字段「第一卷 山河」×3、内容不含 `[IMG:`。

### 5.4 测试安全性（一次真实事故与修正）

第一版测试拿"用户库里第一本书"当外键宿主，一次清理不彻底就把用户的一行书改成了
`activeRevision = <已删除的 revision>`。已做两件事：

1. **修数据**：把该行还原（`activeRevision` 置 NULL、重新生成 `publicationId`），
   现在 `SELECT COUNT(*) FROM books WHERE activeRevision IS NOT NULL` = **0**。
2. **改测试设计**：改为**自建一行测试专用书**（`filePath` 前缀 `device-e2e://`，不可能与真实书冲突），
   用完删除。用户数据在结构上就不可能被影响。

验证：测试前后 `books=25 / e2e=0 / dirty=0`（完全无残留）。

---

## 6. 门禁与产物

```powershell
pwsh -NoProfile -File tools\verify.ps1
→ BUILD SUCCESSFUL in 29s
→ verifyKotlinStyle 0 违规
→ 599 项单元测试 / 0 失败 / 0 错误 / 90 套
→ APK 29,372,985 字节
```

| 项 | 值 |
|---|---|
| APK | `app\build\outputs\apk\debug\app-debug.apk` |
| 大小 | 29,372,985 字节 |
| 构建时间 | 2026-09-10 23:25:24 |
| SHA-256 | `EA50F5FC4232C559B99FE5DB49678F4D830BB882CF419B71C54FBEADCA097C5A` |
| 装机验证 | 已 `install -r` 覆盖安装，启动正常、无崩溃 |

另有 instrumentation 包 `app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`（仅测试用，勿分发）。

---

## 7. 本批改动文件

| 文件 | 改动 |
|---|---|
| `data/.../entity/BookEntity.kt` | **补声明** `Index(value = ["publicationId"], unique = true)`；`toDomain`/`fromDomain` 补 `publicationId`/`activeRevision`/`contentState` |
| `domain/.../model/Book.kt` | 新增 `contentState` 字段与四个状态常量 |
| `app/src/test/.../ContentEngineMigration9To10Test.kt` | v9 夹具改为**连索引一起建**；新增 2 条内容身份往返测试 |
| `app/src/androidTest/.../PublicationImportOnDeviceTest.kt` | **新增**：真机端到端导入验证（自建宿主书、用完即删） |
| `app/src/main/.../epub/EpubFixtureBuilder.kt` | 从 test 移到 **main**（单测与真机测试必须共用同一份夹具字节） |
| `app/build.gradle.kts` | 增 `androidx.test:core-ktx`、`runner`、`rules`、`room-testing`（androidTest） |
| `app/src/test/.../epub/FixtureExporter.kt` | **新增**：把自造 EPUB 导到 `app/build/device-fixtures/`，供装机手动验证 |

---

## 8. 教训（建议并入计划 15.3 的完成定义）

1. **migration 测试的夹具必须包含索引与约束**，否则 Room 的 `TableInfo` 校验类缺陷必然漏检。
   "只建表"的夹具不是简化，是失真。
2. **凡是新增 `@Entity` 索引的迁移，必须同步改实体并跑一次真机开库**。
   本地 Robolectric 的 SQLite 与 schema 校验路径不足以覆盖这一类。
3. **instrumentation 测试不得复用用户数据行**。自建宿主行是最低要求；
   "用完还原"已经证明不足够（本次就出过一次事故）。
