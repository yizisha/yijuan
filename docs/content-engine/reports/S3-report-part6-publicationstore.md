# FlowReader 内容引擎改造 — S3 阶段报告（第 6 批：内容包落盘与 staged 协议）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 **5.1 内容包与分片**、**5.3 原子导入与激活协议**、
**4.6 第 2 条 `resourceId`**、**12.3 第 4 条**、Gate G3。
上一批：`S3-report-part5-manifest.md`。

**S3 仍为 PARTIAL。** 本批交付 `PublicationStore`（文件侧），这是"既有路径替换"的前置条件。

---

## 1. 本批交付

| 文件 | 类型 | 职责 |
|---|---|---|
| `app/.../util/epub/PublicationStore.kt` | 新增 560 行 | 内容包落盘：分片、资源摘要命名、staged → 原子提交、GC |
| `app/src/test/.../PublicationStoreTest.kt` | 新增 13 项测试 | **真实文件 I/O**（Robolectric 提供真实 `filesDir`），非 mock 路径字符串 |

### 1.1 目标目录结构（5.1）

```
files/books/<publicationId>/revisions/<revision>/
  source/original.bin          原始输入的可验证副本（可重新解析）
  documents/<hash>.txt         规范源文本
  documents/<hash>.json        分片索引 / 块 / 锚点 / weightIndex
  resources/<resourceId>       可渲染资源（文件名就是 resourceId）
  manifest.json                revision 的全量索引摘要与校验和
```

相邻还有 `files/books/<publicationId>/staging/<revision>/`：**文件侧完成后才允许切换数据库**。

### 1.2 三条硬约束的落实

| 计划条款 | 落实方式 | 测试 |
|---|---|---|
| 4.6 第 2 条：`resourceId = SHA256(containerPath + contentHash)`，**文件名用摘要**，不用书名/timestamp/basename；不存设备绝对路径 | `resourceIdOf()`；`resources/<resourceId>`；`documentFileName` 用 documentKey 摘要（`OEBPS/Text/../../etc/passwd` → 32 位十六进制，不含 `/` 与 `..`） | `resourceIdIsSha256OfPathAndContentHash`、`documentFileNameIsADigestNotTheRawPath`、`stageWritesTheContentPackageIncludingAResourceNamedByItsDigest` |
| 5.1：分片目标 **32,768 UTF-16 单位**、优先块边界、**不切代理对**、分片**不是章节** | `chunkDocument()`：块边界优先；块内硬切走 `safeBoundary()` 后退一位避免切开代理对；超长块所有分片共享同一 `blockId`；分片只带源坐标 + checksum，文本存 `documents/<hash>.txt` 一份 | `chunkingPrefersBlockBoundaries`、`chunkingNeverSplitsSurrogatePairs`、`oversizedSingleBlockKeepsTheSameBlockIdAcrossItsChunks`、`chunksCarryDeterministicChecksums` |
| 5.3：staged → 原子重命名 → 稍后单事务激活；**失败不删旧数据** | `stage()` 只写 staging；`commitFiles()` 重命名（跨卷时回退为复制+删除 staging，仍不碰数据库）；已存在同 revision **复用不覆盖**；`discardStaging()`；`collectGarbage()` 只删未被引用的 staging | `commitRenamesStagingIntoTheImmutableRevisionDirectory`、`committingTheSameRevisionReusesItInsteadOfOverwritingActiveFiles`、`discardingStagingNeverTouchesAnExistingRevision`、`garbageCollectionRemovesOnlyUnreferencedStagingDirectories` |

### 1.3 明确**没有**做的事（本批边界）

- **没有**读取 EPUB 容器字节：`stage()` 接收 `sourceBytes` 与 `resourceBytes`，
  由调用方（即将接入的导入协调器）从 `EpubZipContainer` 读出。
  这样 `PublicationStore` 的测试不需要造 ZIP，且资源哈希的输入是明确的。
- **没有**写数据库：`ContentIndexDao.activateRevision()` 要求 `PublicationRevisionEntity`
  等实体映射，属下一批（需要 `bookId`，只能在 `BookImporter` 拿到之后）。
- **没有**切换 `BookParser`。因此用户可见行为**仍未改变**。

---

## 2. 测试证据（本批）

13 项，全通过，且都是**真实文件系统**断言（不是"调用了某个方法"）。

| 用例 | 断言要点 |
|---|---|
| `chunkingPrefersBlockBoundaries` | 分片连续覆盖全文、不重叠、不超目标长度 |
| `chunkingNeverSplitsSurrogatePairs` | 目标长度故意小于 emoji 宽度时，每个分片边界都不落在代理对中间（INV04） |
| `oversizedSingleBlockKeepsTheSameBlockIdAcrossItsChunks` | 超长块切分后 `blockId` 保持同一身份（5.1） |
| `emptyDocumentHasNoChunks` | 空文档不造分片，也不崩 |
| `chunksCarryDeterministicChecksums` | 同一输入两次分片**完全相等**（INV02） |
| `resourceIdIsSha256OfPathAndContentHash` | 同路径同内容 → 同 ID；路径/内容/大小写任一变化 → 不同 ID |
| `documentFileNameIsADigestNotTheRawPath` | 恶意 documentKey 不会把 `../` 带进文件名 |
| `stageWritesTheContentPackageIncludingAResourceNamedByItsDigest` | 目录结构齐全；资源文件名不含 `a.png`；文本按 UTF-8 落地；**同一输入两次 stage 的 manifest.json 逐字节一致** |
| `commitRenamesStagingIntoTheImmutableRevisionDirectory` | staging 消失、revision 目录出现、manifest 可读回 |
| `committingTheSameRevisionReusesItInsteadOfOverwritingActiveFiles` | 预置的 `marker.txt` 仍在 → **没有覆盖活动文件** |
| `discardingStagingNeverTouchesAnExistingRevision` | 丢弃 staging 后 revision 与其 manifest 完好（5.3 第 6 条） |
| `garbageCollectionRemovesOnlyUnreferencedStagingDirectories` | 只删孤儿 staging；被引用的 staging 与 revision 目录都不动（5.3 第 8 条） |
| `documentJsonCarriesBlocksAnchorsChunksAndWeightIndex` | schemaVersion / 文本长度 / 主字数 / 锚点偏移 / 块 / 分片 / weightIndex 齐全；**weightIndex 只含 MAIN 块**（注释不进） |

```powershell
pwsh -NoProfile -File tools\verify.ps1
→ BUILD SUCCESSFUL in 1m 20s
→ verifyKotlinStyle 0 违规
→ 574 项测试 / 0 失败 / 0 错误 / 87 套（本轮 +13）
→ APK 29,332,389 字节，2026/9/10 22:33
```

---

## 3. S3 完成矩阵（本批更新）

| S3 条目 | 状态 | 实现 | 验证 |
|---|---|---|---|
| 4.2 `EpubResourceResolver` | **PASS** | `EpubResourceResolver.kt` | 20 项 |
| 4.3 `EpubNavigationParser` | **PASS** | `EpubNavigationParser.kt` | 11 项 |
| 4.1 `EpubPackageParser` | **PASS** | `EpubPackageParser.kt` | 8 项 |
| 4.1 `EpubZipContainer` | **PASS** | `EpubZipContainer.kt` | 26 项 |
| 3.2 规范源文本 v1 | **PASS**（1 项能力限制） | `EpubDocumentParser.kt` | 34 项含 14.1 golden |
| 4.5 结构化正文合同 | **PASS** | `StructuredDocument.kt` | 34 项 |
| 8.1 锚点索引 | **PASS** | `EpubDocumentParser.kt` | 4 项 |
| 3.1 字数口径 | **PASS** | `TextUnits.kt` | — |
| `ReadingManifestBuilder` | **PASS** | `ReadingManifestBuilder.kt` | 17 项 |
| **5.1 内容包 + 分片** | **PASS** | `PublicationStore.kt` | **13 项** |
| **4.6 资源摘要命名与落盘** | **PASS**（文件侧） | `PublicationStore.kt` | 含在 13 项内 |
| 5.3 文件侧 staged 协议 | **PASS**（文件侧） | `PublicationStore.kt` | 4 项 |
| **5.3 数据库侧激活**（`activateRevision` → `books.contentState=READY`） | **NOT_RUN** | — | 需要 `bookId`，只能在 `BookImporter` 之后 |
| **导入协调器**（container → OPF → nav → document → manifest → store） | **NOT_RUN** | — | 下一批 |
| **既有路径替换**（`BookParser.parseEpubStream` 切新链路） | **NOT_RUN** | — | 计划第 16 节第 2 条硬性检查项 |
| FX03/FX04 导入 golden | **NOT_RUN** | — | 依赖导入协调器 |
| 4.4 TXT `HeadingKind` / 卷序 | **NOT_RUN** | — | — |
| Gate G3 整体 | **PARTIAL** | — | 差"导入结果 golden"这一格 |

**BLOCKED / 能力限制（延续，未变）**：`pre` 行首缩进（Jsoup XML 解析器丢空白，依赖级）、
512MiB 仅结构级验证、SVG 渲染 NOT_RUN。

---

## 4. 下一批（S3 收尾的剩余两步）

1. **`PublicationImportCoordinator`**（app/util/epub）：把七段链路串起来 ——
   `EpubZipContainer` → `EpubPackageParser` → `EpubNavigationParser` → `EpubDocumentParser`
   → `ReadingManifestBuilder` → `PublicationStore.stage/commitFiles`
   → `ContentIndexDao.activateRevision(...)` + 写 `books.activeRevision/contentState`。
   需要新增一个 `BookDao` 方法（`updateContentState(bookId, publicationId, activeRevision, contentState)`），
   与 `activateRevision` 的 `activate` 回调在**同一事务**内执行。
2. **切换 `BookParser.parseEpubStream`**：走协调器，并保留"legacy `Chapter` 投影"
   （由 manifest 的 sections 生成，**不是**再从旧文本切章），这样用户可见行为立刻切到新链路，
   同时老 UI 仍能显示。
3. **FX03/FX04 导入 golden**：内容/目录/图像/链接索引完整性 → 判定 Gate G3 整体。
4. 之后：4.4 TXT `HeadingKind`/卷序。

工期提示（交接文档第 6 节）：剩余体量仍大，建议按"一个协调器 + 一次切换 + 一组 golden"推进，
不要一次同时改 TXT 与旧书迁移路径。
