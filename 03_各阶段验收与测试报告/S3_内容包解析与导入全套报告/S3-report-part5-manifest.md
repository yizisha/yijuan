# FlowReader 内容引擎改造 — S3 阶段报告（第 5 批：阅读清单与主线计量）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 **4.1 第 6 步**、**4.3 末段**、**10.2 主线去重与计量**、
**12.3 第 4/6 条**、**5.1 内容包**、Gate G3。
上一批：`S3-report-part4-documentparser.md`。

**S3 仍为 PARTIAL。** 本批交付 `ReadingManifestBuilder`，把"结构块"变成"可阅读的逻辑清单"。

---

## 1. 本批交付

| 文件 | 类型 | 职责 |
|---|---|---|
| `domain/.../model/PublicationManifest.kt` | 新增 300 行 | 清单合同：spine 出现、唯一文档、逻辑 section、目录目标映射、资源、诊断 |
| `app/.../util/epub/ReadingManifestBuilder.kt` | 新增 470 行 | 纯函数：结构文档 + 目录树 → 阅读清单 |
| `app/.../util/epub/EpubNavigationParser.kt` | 修改 | `Node` 增加 `role`（VOLUME / LANDMARK / PAGE_LIST），直写 `toc_nodes.role` |
| `app/src/test/.../ReadingManifestBuilderTest.kt` | 新增 17 项测试 | Gate G3 证据 |

### 1.1 本批解决的三件旧实现没做的事

| 问题 | 本批做法 | 计划条款 |
|---|---|---|
| **section ≠ spine 文档**（F13 的成因：按性能/文档切章） | 边界取主 spine 上**不同的叶级可读目录目标**，按实际源顺序排序；同一文档多锚点 → 多 section；第一目标之前的真实正文 → `SectionKind.PREFACE` | 4.3 末段 |
| **重复 spine 出现会污染分母** | 每次都写入 `spineOccurrences`（含 `occurrenceId`、顺序原样），但只有**首次**计入 `mainTextUnits`；重复出现记 `SPINE_DOCUMENT_REPEATED` 诊断 | 10.2 第 2 条 |
| **分母依赖已加载章节** | `totalMainTextUnits = sections.sumOf { mainTextUnits }`，section 权重由块级源区间与 `TextUnits` 算出 | INV11、10.2 |

### 1.2 关键设计点

- **`occurrenceId` = `SHA256(OPF 路径 + itemref 序号)` 前 16 位**：与 `documentKey` 无关，
  所以同一文档的两次出现必然不同；确定性（INV02），不用随机值（12.3 第 6 条）。
- **`sectionId` = `SHA256(documentKey + sourceAnchorIdentity)` 前 24 位**，
  前缀 `s:`；无锚点用 `@<offset>`。同一目标多次目录引用**不重复生成** section（4.3 末段）。
- **`reading_sections` 主键是 `(bookId, revision, sectionId)`**，因此构建器保证
  同一 revision 内 sectionId 唯一 —— 重复 spine 出现产生同名 section 时会破坏主键，
  本批用"只对首次出现建 section"从根上避免，并有测试钉住（`repeatedSpineOccurrenceIsRecordedButCountedOnce`）。
- **section 权重按块算**：`mainRangesOf(document)` 取所有 `BlockRole.MAIN` 块的范围，
  与 section 区间求交后交给 `TextUnits.count`。这样 ruby 注音、脚注引用标记、`U+FFFC`
  天然不参与统计（3.2 第 6/7 条），**不需要**再写一套"减法"。
- **辅助文档**：`linear=no`，或整个文档没有 MAIN 块（纯脚注文档）→ `DocumentRole.AUXILIARY`，
  文字保留源坐标但不计主线权重（10.2 第 3 条）。**仍然记录在 `documents` 里不丢**。

---

## 2. 测试抓到的缺陷

| # | 缺陷 | 归属 | 处置 |
|---|---|---|---|
| 1 | 断言写成「不可读 spine 出现的 `documentKey` 应为 null」 | **测试侧**（我的期望值错） | 实现保留 `documentKey` 是正确的：4.1 末段要求失败要**点名具体文档**。已改断言并加注释 |

本批缺陷数为 1，且是测试侧；实现侧没有发现新问题。

---

## 3. Gate G3 证据（本批）

测试：`ReadingManifestBuilderTest.kt`，**17 项，全通过**。

| 用例 | 断言要点 | 对应测试 ID |
|---|---|---|
| `spineBecomesOneSectionPerDocumentWhenTocIsAbsent` | 无目录时每个 spine 文档一个缺省 section；**100/900 golden** → 1000 | T069 |
| `oneDocumentWithTwoAnchorsBecomesTwoSections` | 同一 XHTML 两个锚点 → 两个 section，范围相接且不重叠 | T035、T036 |
| `contentBeforeTheFirstTargetBecomesAPrefaceSection` | 第一目标之前的正文 → `PREFACE`「正文起始」，权重单独算 | 4.3 末段 |
| `plainTocEntryWithoutFragmentMapsToTheDocumentStart` | 无 fragment 目标 → 文档首个边界 | 8.2 |
| `nonLinearDocumentsAreAuxiliaryAndDoNotEnterTheMainOrder` | `linear=no` 不进主顺序、权重 0、**仍被记录** | T039、4.3 |
| `repeatedSpineOccurrenceIsRecordedButCountedOnce` | 3 次出现全部保留；`occurrenceId` 互不相同；只有首次 `counted=true`；**总数仍 1000 而非 1100**；section 不重复 | T041、T042、T071、10.2 |
| `unreadableSpineEntryIsDiagnosedAndKeptInOccurrences` | 不可读出现 → `SPINE_DOCUMENT_UNREADABLE` + 保留出现记录 | T090、4.1 末段 |
| `sectionUnitsExcludeAuxiliaryBlocks` | 脚注文字不计主线权重（5 字 vs 10 字） | 10.2 主线计量规则 |
| `imageOnlyBookReportsZeroUnitsAndDiagnosesInsteadOfDividingByZero` | 纯图书 0 字 + `MANIFEST_ZERO_TEXT_UNITS`，**不除零、不换算成字** | T071、10.2 |
| `totalIsTheSumOfSectionsAndNeverDependsOnLoadedChapters` | 分母 = section 之和 | INV11、T070 |
| `tocOrderDifferentFromSpineIsDiagnosedButBodyOrderFollowsSpine` | 目录倒序只诊断，**正文仍按 spine** | T037、INV06 |
| `missingAnchorIsDiagnosedAndProducesNoBoundary` | 目标不存在 → `ANCHOR_NOT_FOUND`，不随机跳 | T058 |
| `volumeGroupIsNotASectionBoundaryButKeepsItsChildrenTargets` | 卷 GROUP 无 target 不产生 section；子项各自映射 | T032、4.3 |
| `internalResourceReferencesAreAggregatedOnce` | 同一资源被两个文档引用 → 一条记录、两个归属 | 4.6 |
| `buildingTwiceYieldsIdenticalSectionIdsAndOccurrenceIds` | **两次构建**：sectionId / occurrenceId / sourceHash / 映射 / 总数全一致 | INV02、T001 |
| `emptySpineIsRejectedInsteadOfReturningAnEmptySuccess` | 空 spine → `Rejected(MANIFEST_NO_READABLE_SECTION)`，不返回"空书成功" | 4.1 末段 |
| `auxiliaryOnlyDocumentIsNotCountedTowardsTheMainOrder` | 纯注释文档 → AUXILIARY、权重 0、主线总数只含正文 | 10.2 第 3 条 |

```powershell
pwsh -NoProfile -File tools\verify.ps1
→ BUILD SUCCESSFUL in 1m 20s
→ verifyKotlinStyle 0 违规
→ 561 项测试 / 0 失败 / 0 错误 / 86 套（本轮 +17）
→ APK 29,226,159 字节，2026/9/10 22:29
```

---

## 4. S3 完成矩阵（本批更新）

| S3 条目 | 状态 | 实现文件 | 验证 |
|---|---|---|---|
| 4.2 `EpubResourceResolver` | **PASS** | `EpubResourceResolver.kt` | 20 项 |
| 4.3 `EpubNavigationParser` | **PASS** | `EpubNavigationParser.kt` | 11 项（本批补 `role`） |
| 4.1 `EpubPackageParser` | **PASS** | `EpubPackageParser.kt` | 8 项 |
| 4.1 `EpubZipContainer` | **PASS** | `EpubZipContainer.kt` | 26 项 |
| 3.2 规范源文本 v1 | **PASS**（1 项能力限制） | `EpubDocumentParser.kt` | 34 项含 14.1 golden |
| 4.5 结构化正文合同 | **PASS** | `StructuredDocument.kt` | 34 项 |
| 8.1 锚点索引 | **PASS** | `EpubDocumentParser.kt` | 4 项 |
| 3.1 字数口径 | **PASS** | `TextUnits.kt` | — |
| **`ReadingManifestBuilder`（4.1 第 6 步 / 4.3 末段 / 10.2）** | **PASS** | `ReadingManifestBuilder.kt` + `PublicationManifest.kt` | **17 项** |
| 4.6 资源落盘 + `resourceId = SHA256(containerPath + contentHash)` | **NOT_RUN** | — | 本批只汇总"有哪些引用"；字节/哈希/落盘在 `PublicationStore` |
| `PublicationStore`（staged → `activateRevision()` → READY） | **NOT_RUN** | — | S2 的 `activateRevision()` 已就绪待接 |
| 4.4 TXT `HeadingKind` / 卷序 | **NOT_RUN** | — | — |
| **既有路径替换**（`BookParser` 切新链路） | **NOT_RUN** | — | 计划第 16 节第 2 条硬性检查项 |
| Gate G3 整体 | **PARTIAL** | — | 「导入结果 golden」需 `PublicationStore` + 导入切换后才能判 |

**BLOCKED / 能力限制（延续上一批，未变）**：`pre` 行首缩进（Jsoup XML 解析器丢空白，属依赖级）、
512MiB 仅结构级验证、SVG 渲染 NOT_RUN。

---

## 5. 风险（与上批相同，继续记录）

八个新组件（resolver / navigation / package / container / document / textUnits / manifest / 现有的 dao）
目前**仍只被测试调用**。`BookParser.parseEpubStream` 依旧走旧的 `htmlToFormattedText` + `[IMG:]`，
**用户可见行为与上一版一致**。S3 的最后一步必须把导入链路切过去。

---

## 6. 下一批（S3 第 6 批 = S3 收尾，按依赖顺序）

1. **`PublicationStore`**：
   - `resourceId = SHA256(containerPath + contentHash)`，文件名用摘要，**不用书名/timestamp/basename**（4.6 第 2 条）；
   - 文档分片（目标 32,768 UTF-16 单位、优先块边界、不切代理对/字素簇）+ 分片 checksum；
   - staged 目录写入 → 原子重命名 → `ContentIndexDao.activateRevision()` → `books.contentState = READY`（5.3）；
   - 失败时数据库仍指向旧 revision，不删旧数据（5.3 第 6 条）。
2. **`BookParser.parseEpubStream` 切换**到新链路（container → OPF → nav → document → manifest → store）。
3. **FX03/FX04 导入 golden**：内容/目录/图像/链接索引完整性（G3 的最后一格）。
4. 之后：4.4 TXT `HeadingKind`/卷序 → Gate G3 整体判定。
