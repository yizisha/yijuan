# FlowReader 内容引擎改造 — S2 完成报告（存储、v10 迁移、原子激活）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 5.2 / 5.3 / 13 节 S2 与 **Gate G2**。授权：用户已全量授权（含 schema 迁移）。

---

## 1. 交付物

### 1.1 新增表（7 张，主计划 5.2 字段不得省略）

| 表 | 主键 | 关键字段 |
|---|---|---|
| `publication_revisions` | `(bookId, revision)` | normalizationVersion、parserSchemaVersion、sourceHash、manifestRelativePath、state（STAGED/READY/RETIRED/FAILED）、totalMainTextUnits(Long)、createdAtMillis |
| `publication_documents` | `(bookId, revision, documentKey)` | ordinal（未列 spine 为 -1，**不加唯一约束**）、linear、role（MAIN/AUXILIARY）、textLengthUtf16、mainTextUnits、manifestRelativePath、sourceHash |
| `reading_sections` | `(bookId, revision, sectionId)` | ordinal（book/revision 内唯一）、title、kind（NORMAL/PREFACE/VOLUME_PREFACE）、rangesJson、mainTextUnits |
| `toc_nodes` | `(bookId, revision, nodeId)` | parentId、siblingOrder、label、kind（GROUP/ENTRY）、role（VOLUME）、targetJson（可空） |
| `reader_states` | `bookId` | locatorJson、quality、legacySnapshotJson、lastMainLocatorJson、navigationSequence、updatedAtMillis |
| `content_migration_jobs` | `(bookId, targetRevision)` | stage、errorCode、attemptCount、updatedAtMillis |
| `reading_session_deltas` | `deltaId` | bookId、sessionId、sequence、localDate、zoneId、visualReadMillis、ttsReadMillis、qualifiedTextUnits、settledTurns、statsVersion；**UNIQUE(sessionId, sequence, localDate)** |

全部子表 `bookId` 外键 → `books(id)` `ON DELETE CASCADE`；bookId / parentId / siblingOrder / localDate / stage 等查询路径均建索引。

### 1.2 既有表新增列（additive，v9 的列一律保留）

| 表 | 新列 |
|---|---|
| `books` | `publicationId TEXT`（**迁移内逐行回填 UUID**）、`activeRevision TEXT`、`contentState TEXT NOT NULL DEFAULT 'LEGACY'` + `publicationId` 唯一索引 |
| `bookmarks` | `locatorJson TEXT`、`locatorQuality TEXT NOT NULL DEFAULT 'UNRESOLVED'`、`legacyUnit TEXT NOT NULL DEFAULT 'UNKNOWN'` |
| `annotations` | `rangeLocatorJson TEXT`、`locatorQuality TEXT NOT NULL DEFAULT 'UNRESOLVED'` |
| `reading_stats` | `visualReadMillis`、`ttsReadMillis`、`qualifiedTextUnits`、`settledTurns`（均 `INTEGER NOT NULL DEFAULT 0`）、`statsVersion INTEGER NOT NULL DEFAULT 1` |

`data/schemas/.../10.json`（47,268 字节）由 Room 构建生成，**未手写**；`9.json` 及更早 schema 原样保留。

### 1.3 DAO 与原子激活

- `ContentIndexDao`：修订/文档/章节/目录节点的 upsert 与**只投影 metadata 的列表查询**（不把 rangesJson 读进列表）；`activateRevision()` 单事务写 READY 修订 + 全部文档/章节/目录 + 最后切 `books.activeRevision`（主计划 5.3 第 4 步）。
- `ReaderStateDao`：
  - `saveIfNewer()`：带 `navigationSequence` 检查，**旧序列直接拒绝**（`STALE_REJECTED`），杜绝慢的旧写入覆盖新位置（§6.7）。
  - `commitDelta()`：插入 delta 与累加日统计在**同一事务**，`(sessionId, sequence, localDate)` 命中即返回 false 而**不再累加**（幂等，T080）。
  - 只累加 v2 列，历史 `readPages`/`readTimeSeconds` 原样不动，避免同一段时间在旧/新字段各算一次。

---

## 2. Gate G2 证据

测试：`app/src/test/java/com/flowreader/app/data/ContentEngineMigration9To10Test.kt`（**5 项，全部通过**）。

做法刻意“真实”，避免自证：

1. v9 建表语句**逐字取自 Room 自己导出的 `9.json` 的 `createSql`**，而不是测试里手写 DDL——手写 DDL 一旦与真实历史 schema 有偏差，测试就会假装通过。
2. 写入带特征值的旧数据（2 本书、书签、批注、分类、章节、统计各一条），`user_version = 9`。
3. 用 **Room 打开**并挂上生产用的同一个 `DatabaseModule.MIGRATION_9_10`：Room 真正执行迁移，随后用它自己生成的 v10 schema **校验结果**（列类型/非空/默认值不符即抛异常）。这就是 G2 要的 schema 校验。
4. 通过 DAO 断言：旧数据一条不少、旧列一字不改、新列按默认值就位、`publicationId` 已回填且唯一。

断言覆盖：

| 用例 | 断言要点 |
|---|---|
| `migrationPreservesEveryLegacyRowAndColumn` | 两本旧书齐全；`currentPosition = 777` **原样保留**（不被当成字符偏移写进新字段）；title/author/currentChapter/readingProgress/wordCount/tags 逐项一致 |
| `newContentStateColumnsAreAdditiveWithDeclaredDefaults` | `publicationId` 逐行回填、互不相同、且匹配 UUID v4 正则；`activeRevision == null`、`contentState == LEGACY` |
| `legacyBookmarkAndAnnotationAndStatsSurviveUntouched` | 书签文字与 position、批注 selectedText/note/起止位置全部原值 |
| `newTablesAreUsableAndDeltaCommitIsIdempotent` | 序列检查拒绝倒写（旧 sequence 被 `STALE_REJECTED`，新值仍是 5）；同一 delta 提交两次只累加一次；命中旧统计行时 `readPages=12`/`readTimeSeconds=3600` **未被覆盖**，v2 列已就位、`statsVersion=2` |
| `migratedDatabasePassesRoomSchemaValidationWithEmptyContentIndex` | 迁移后库可被 Room 正常打开（schema 校验通过），新表初始为空 |

测试过程中发现并修正两个**真实缺陷**（不是放宽断言）：

1. `categories` 表列名凭记忆写错——改为从 `9.json` 读取，列名以导出的历史 schema 为准。
2. **`INSERT … ON CONFLICT DO UPDATE` 在 minSdk 26 上不可用**：SQLite UPSERT 需 3.24+，而 Android 8.0 自带 3.18，真机会直接语法错误（Robolectric 先一步暴露）。已改为「先 UPDATE，未命中再 `INSERT OR IGNORE`」，两步同事务，幂等且可移植。

命令与结果：

```powershell
pwsh -NoProfile -File tools/verify.ps1
→ BUILD SUCCESSFUL in 2m
→ verifyKotlinStyle 0 违规
→ testDebugUnitTest：445 项 / 0 失败 / 80 套（新增 5 项迁移测试）
→ APK 29,099,923 字节，2026/9/10 21:14
```

---

## 3. 更新后的完成矩阵

| 阶段/需求 | 状态 | 备注 |
|---|---|---|
| S0 基线审计 + G0 复现 | **PASS** | 5 类中 4 类可执行复现 |
| S1 定位模型 / 进度索引 / 会话策略 | **PASS** | 含 100/900 golden |
| **S2 存储、v10 迁移、原子激活** | **PASS（G2）** | 7 表 + 4 表加列 + 2 DAO + 迁移保全测试 5 项 |
| S9 接线（R3/F02/F03/F04） | **PARTIAL** | 进度/时长/剩余时间已接通；曝光与 delta 落库尚未接线到跟踪器 |
| F08 卷字段丢失 | **PASS** | `BookParser` 已传 volume |
| R1 内容定位接通界面（写 reader_states） | **NOT_RUN** | 表与 DAO 已就绪；需 S3 产出 locator 后接线 |
| R2 EPUB 结构保留 | **NOT_RUN** | 需 S3 |
| R4 分卷目录树 | **NOT_RUN** | 需 S3 + S6（表与 DAO 已就绪） |
| R5 EPUB 插图 | **NOT_RUN** | 需 S3 + S5 |
| R6 锚点/脚注/返回 | **NOT_RUN** | 需 S3 + S7 |
| S3—S11 | **NOT_RUN** | 入口见 S1 报告第 4 节 |

**完成定义（15.3）自评**：未达成。R2/R4/R5/R6 未动，R1 未落库，无真机验收。

## 4. 下一步（S3）

1. 从 `BookParser` 抽出 `EpubContainerReader` / `EpubPackageParser` / `EpubResourceResolver` / `EpubNavigationParser` / `EpubDocumentParser` / `ReadingManifestBuilder` / `PublicationStore`。
2. 第一批测试即补齐当前 NOT_RUN 的三条 G0 复现：卷字段（T041）、同文档双锚点（T035）、图文混排（T048）。
3. 修复 `readEpubTocTitles` 的 `substringBefore('#')` 覆盖问题与表单式 `URLDecoder`（F05/F11）。
4. `PublicationStore` 用本阶段已就绪的 `activateRevision()` 完成 staged → READY 激活。
