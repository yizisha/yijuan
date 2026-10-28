# FlowReader 内容引擎改造 — S0 基线报告

执行者：DSH Agent。日期：2026-09-10。对应主计划 `FLOWREADER_CONTENT_ENGINE_IMPLEMENTATION_PLAN.md` 第 13 节 S0 与 Gate G0。

本文件只记录**核对到的事实**与**已跑出的证据**，不含尚未实施的能力声明。

---

## 1. 基线快照（执行前记录，未做任何源码改动前）

| 项 | 实测值 |
|---|---|
| 仓库 | `C:/Users/lenovo/.dsh/agy-link/media/flowreader` |
| HEAD | `077ea56d63070d2b93caa58eb06ca03c5ffa55c3`（与主计划 1.1 记载一致） |
| 分支 | `main`，跟踪 `origin/main` |
| 工作区改动量 | 103 项（`git status --short` 计数）；其中已跟踪文件 **61 个，+6998 / −2169** |
| 未跟踪关键文件 | `ReaderBoundaryGestures.kt`、`ReaderHeader.kt`、`ReaderFooter.kt`、`ReaderTocSheet.kt`、`BookImporter.kt`、`FolderScanner.kt`、`TxtChapterDetector.kt`、`ChapterBody.kt`、`TxtCleaner.kt`、`ChineseConverter*.kt`、`tools/`、`docs/`、`data/schemas/.../8.json`、`9.json` |
| JDK | `java 21.0.10 2026-01-20 LTS` |
| 行尾 | 工作区为 LF；`git` 报 `LF will be replaced by CRLF`（`core.autocrlf` 开启），**不是**内容改动 |
| Room 版本 | 实际 `@Database(version = 9)`，schema 目录有 `4/6/7/8/9.json` |
| 主计划记载 | 与实测一致（v9、HEAD 相同） |

**保护措施**：全程未执行 `git reset --hard` / `git checkout --` / `git restore`；未改任何依赖版本；未动 `data/schemas` 历史 json。

### 可用 Gradle 任务（实测存在）

`verifyKotlinStyle`、`performanceBaseline`、`coverageSummary`（根 `build.gradle.kts` 第 18/41/73 行注册）；`testDebugUnitTest`、`:app:assembleDebug` 已实际运行；Roborazzi 插件已应用（构建日志出现 `finalizeTestRoborazziDebug`）。

> 说明：主计划 15.1 列出的 `verifyRoborazziDebug` 本次未单独执行（S0 不涉及截图门禁），S11 再核。

---

## 2. 位置调用链审计（S0 必需产出）

`currentPosition` 共 87 处引用，按**单位**分类如下。分类依据是调用点的实际语义，不是字段名。

| 调用点 | 文件:行 | 单位 | 说明 |
|---|---|---|---|
| 领域模型 | `domain/.../Book.kt:18` | **两种** | `val currentPosition: Int`，无单位信息 |
| 实体/DAO 写入 | `data/.../BookEntity.kt:33`、`BookDao.kt:57-58` | 透传 | `updateReadingProgress(bookId, chapter, position, progress)` |
| 分页渲染读取 | `app/.../PagedReader.kt:92,234` | **PAGE_INDEX** | `currentPosition.coerceIn(0, pages.size - 1)` |
| 滚动渲染读取 | `app/.../ReaderContent.kt:115,156` | **SCROLL_PX** | `currentPosition.coerceIn(0, scrollState.maxValue)` |
| 滚动上报 | `ReaderScreen.kt:257,327` | **SCROLL_PX** | 来自 `ScrollState.value` |
| 分页上报 | `ReaderScreen.kt:284` | **PAGE_INDEX** | `onPageChanged(page, …)` |
| 会话统计 | `ReaderViewModel.kt:455-494` | 分支 | `positionIsPageIndex` → `recordPageProgress` / `recordProgress` |
| 剩余时间 | `ReaderProgressEngine.kt:38-48` | **按字符** | 被喂入上述任一单位 |
| 每章记忆 | `ReaderViewModel.kt:123` | **裸 Int** | `chapterPositions = mutableMapOf<Int, Int>()`（章下标 → 裸 Int） |
| 书签 | `Bookmark.position` + `ReaderViewModel.kt:628` | 阅读器位置 | 已确认**不是**字符偏移（AGENTS.md 第 93 行） |
| 批注 | `Annotation.startPosition/endPosition` | **字符（开区间）** | 与书签单位不同 |
| 备份 | `BackupRepository.kt:179,202,220` | 透传 | JSON 无单位标记 |
| 书架进度 | `book.readingProgress`（Float） | 章节等权 | 见 F03 |
| ContentProvider | `FlowReaderContentProvider.kt:101` | 透传 | 对外暴露裸值 |

**结论**：`currentPosition` 一个 `Int` 承载 PAGE_INDEX 与 SCROLL_PX 两种单位，`ReaderPositionUnit.of(pageMode, format)` 是唯一分支点；`Annotation` 使用第三种单位（字符）。三者互不转换，与主计划 INV01 直接冲突。

---

## 3. 已核实缺陷（F01—F14 逐条实测）

| ID | 结论 | 证据（文件:行） |
|---|---|---|
| F01 | **确认** | `Book.kt:18` 单一 Int；`PagedReader.kt:234` 当页下标、`ReaderContent.kt:156` 当像素；`ReaderPositionUnit.kt:16` 自述 CHARACTERS 是 "approximated by scroll pixels" |
| F02 | **确认，且更严重** | `ReaderProgressEngine.kt:43` `chapter.content.length - currentPosition`（元数据章 content 为空 → 剩余 0）；`:48` `speed.coerceAtLeast(100f)` 无样本时伪造速度 |
| F03 | **确认** | `ReadingProgress.kt:21` `((index + inside) / totalChapters)`；`:28` `(clamped * totalChapters).toInt()` 逆映射同样等权 |
| F04 | **确认** | `ReaderSessionTracker.kt:13` 默认 `System::currentTimeMillis`（墙上时钟）；`:84-87` 用 `content.substring(像素,像素)` 数字符；`ReaderViewModel.kt:193,202` 门槛 `seconds > 0 && pages > 0` |
| F05 | **确认** | `BookParser.kt:452,459,483,488` 四处 `substringBefore('#')` → 同 XHTML 多锚点互相覆盖 |
| F06 | **确认** | `BookParser.kt` nav 读取用后代选择器扁平化，分组依赖直接 `<a>`，`<span>` 卷头丢失 |
| F07 | **确认** | `ReaderTocSheet.kt:278,285` `TocVolumeRow(title, palette)` 无展开状态、无点击，只有静态 `ExpandMore` 图标；`:179` key 为 `"v_${entry.title}"` → 同名卷 key 冲突 |
| F08 | **确认** | `BookParser.kt:1050` 接收 `volume`，`1068-1075` 构造 `Chapter` 时**未传** `volume` → 静默丢弃 |
| F09 | **确认** | `htmlToFormattedText` 只产出纯文本与 `## `/`**` 标记，无 inline span / link / 列表语义；`[IMG:]` 为字符串标记（`:640`） |
| F10 | **确认** | `[IMG:绝对路径]` 字符串：`BookParser.kt:640`、`PagedReader.kt:168,204,447`、`ReaderContent.kt:247`、`ChapterBody.kt` 正则 |
| F11 | **确认** | `BookParser.kt:677` `substringBefore('#')` + 表单式 `URLDecoder` + basename 兜底 |
| F12 | **确认** | `PagedReader.kt:161` 自行用 `BitmapFactory` 测图片高度；`ReaderImage` 的 padding/约束不是同一测量结果 |
| F13 | **确认** | `BookParser.kt:1071` 分片标题 `"$title ($partNumber)"` → 目录出现伪造 `(1)(2)` 章 |
| F14 | **确认** | `ReaderTextMapping.rawRange()` 闭区间 vs `Annotation.endPosition` 开区间（已由 `selectionSpan()` 单点修正，AGENTS.md 第 91 行） |

附加事实（主计划未列、但影响方案）：

- `ROADMAP.md:47` 项目自己已承认「滚动模式把滚动像素当字符数用」。
- `ReaderPositionUnit.kt:16` 的注释已经写明 CHARACTERS 是像素近似 —— 即“字符偏移”从来不是字符。
- `ReaderSessionTracker.takeSnapshotAndReset()` 只返回 `(pages, seconds)`，**没有** textUnits / visualReadMillis，S9 需要新增字段而非改算术。

---

## 4. Gate G0：复现状态（**部分满足，如实标注**）

主计划 G0 要求复现五类失败，禁止用“只断言函数存在”的测试冒充。已提交**可执行特征描述测试 14 项**，断言当前（缺陷）行为，修复时翻转断言：

| G0 要求复现的类别 | 状态 | 证据 |
|---|---|---|
| 等权进度 | **PASS（已复现）** | `core/src/test/java/com/flowreader/app/core/util/G0EqualWeightProgressDefectTest.kt`（3 项）：第二章开头实测 **50%**，golden 要求 **10%**；滑杆 10% 实测落到第一章，应落第二章 |
| 页码重排漂移（单位混用） | **PASS（已复现）** | `G0PositionAndStatsDefectTest.theSameIntFieldCarriesTwoDifferentUnits` + `aPageIndexFedIntoTheCharacterPathCompletesNoPage`：翻 4 页读 2 分钟实测完成 **0 页**，`pages > 0` 门槛把整段会话丢弃 |
| 剩余时间口径 | **PASS（已复现，附带 3 项）** | 同文件：元数据章 → 剩余 **0 分钟**；页下标被当字符减；`speed=0` 仍报 **10 分钟**；emoji 按 UTF-16 计 |
| 有效时长依赖翻页 | **PASS（已复现，附带 2 项）** | 同文件：同页 90 秒 → `(0 页, 90 秒)`，与 T073 要求（保存 90 秒、turn=0）冲突；瞬时 1 秒样本直接成为速度 |
| 卷字段丢失 | **NOT_RUN（待 S3）** | `appendChunkedChapter` 为 **private**，`BookParser` 需 `Context`；无法在不改生产代码的前提下从测试调用。S3 抽出 `ReadingManifestBuilder` 后**第一件事**就是补这条复现（T041） |
| 同文档双锚点覆盖 | **NOT_RUN（待 S3）** | `readEpubTocTitles` 为 private，同上。S3 抽出 `EpubNavigationParser` 后补（T035） |
| 混排图片 | **NOT_RUN（待 S3/S5）** | `htmlToFormattedText` 为 private。S3 抽出 `EpubDocumentParser` 后补（T048） |

**G0 判定：PARTIAL** — 4/5 类别已成可执行复现；3 个 EPUB 解析类缺陷因目标函数当前为 private + Android 绑定而无法先行复现，这三种将在 S3 的**抽取式重构**中作为第一批测试落地（计划本身把解析器归 S3）。不谎报 G0 全绿。

测试执行证据：

```
pwsh -NoProfile -File tools/verify.ps1 testDebugUnitTest
→ BUILD SUCCESSFUL
→ TOTAL tests=420 failures=0 errors=0 suites=78
→ com.flowreader.app.core.util.G0EqualWeightProgressDefectTest: tests=3 failures=0
→ com.flowreader.feature.reader.G0PositionAndStatsDefectTest: tests=11 failures=0
```

（基线 406 项 → 现 420 项，新增 14 项均为本次 G0 复现。）

---

## 5. 夹具清单（FX01—FX12 落地计划）

主计划 14.1 要求放 `app/src/test/resources/epub-content/`，用自造可公开文本，不提交用户样书。S0 只做**清点与排期**，未创建（S1/S3 随测试一起写）：

| 夹具 | 归属阶段 | 用途 |
|---|---|---|
| FX01 TXT 100/900 字 + emoji + NBSP | S1 | INV03/INV04 + 进度 golden |
| FX02 TXT 卷分类（同名卷/括号卷/卷序/伪卷） | S3 | T040/T041 |
| FX03 EPUB3 span 卷头 + 三层树 + 同 XHTML 三锚点 | S3 | T032/T035 |
| FX04 EPUB2 NCX 嵌套 + Unicode fragment | S3 | T034 |
| FX05 图片 PNG/JPEG/WebP/GIF/SVG + 特殊路径 | S3/S5 | T044—T050 |
| FX06 链接同页/跨页/脚注/A→B→C | S7 | T056—T059 |
| FX07 语义 粗斜/标题/列表/引用/表格/ruby/pre | S5 | T093/T094 |
| FX08 非主线注释/纯卷标题页/纯图 EPUB | S3/S6 | T039 |
| FX09 v9 数据库（混合单位/重名章/重叠批注） | S10 | T084 |
| FX10 路径逃逸/重复条目/坏图/超限 | S3 | T047/T090 |
| FX11 100 万字 + 20,000 字单章 + 10,000 目录节点 | S9/S11 | T095/T096 |
| FX12 同书修订（改路径/改正文/删锚点）+ 旧新备份 | S10 | T088/T089 |

最小源文本 golden（主计划 14.1，将作为 S1 的第一条断言）：

```
输入 <p id="p1">甲<strong>乙</strong>😀<img src="../Images/a+b.png"/>丙</p>
规范文本 "甲乙😀\uFFFC丙\n"  UTF-16 长度 7  有效主文本 4
p1 起点 0，图对象 [4,5)，丙 [5,6)，strong [1,2)
```

---

## 6. 需要用户确认的事项（按 0.3，实施前一次性确认）

主计划 0.3 要求「实际数据库 schema 迁移、新重大依赖、安全策略变更」实施前确认。以下三项会在 **S2 / S5** 落地，现一并请求：

1. **Room v9 → v10 additve 迁移**：新增 `publication_revisions`、`publication_documents`、`reading_sections`、`toc_nodes`、`reader_states`、`content_migration_jobs`、`reading_session_deltas` 七张表；`books` 加 `publicationId/activeRevision/contentState`（迁移内**逐行为旧书回填 UUID**）；`bookmarks`/`annotations`/`reading_stats` 各加 locator/v2 统计列。**旧列全部保留**，不使用 destructive migration，不删书，不清书架。
2. **新增依赖（仅 1 项，且仅在需要时）**：`io.coil-kt:coil-svg`（配合现有 Coil 2.7.0）用于 EPUB 常见 SVG 插图。若不授权，该子项标 **BLOCKED**，PNG/JPEG/WebP 照常交付，不假装 R5 全通过。
3. **安全策略**：本计划不新增网络、不引入 WebView、不执行 EPUB 内脚本；外部链接仅在用户显式确认后交系统处理（与现状一致）。**无变更**，仅备案。

未确认前，S1（纯模型 + 规范源文本 + 显示映射 + golden）与 S3 的解析器抽取可以继续，不需要授权。

---

## 7. 下一步（按计划顺序，不需再问）

**S1：强类型定位模型、规范源文本、显示映射**
- `domain/model`：`TextLocator`、`TextContext`、`Affinity`、`ResolutionQuality`、`SourceRange`、`LocatorResolution`、`ContentLocator`（Text / FixedPage 带类型联合）、`StructuredDocument` 块模型。
- `feature/reader`：`DisplayMapping`（边界数组 `displayText.length + 1`）、`ContentProgressIndex`（前缀和 + 逆映射）、`ReaderSessionPolicy`（`idleTimeoutMs=300000` 等集中配置）。
- 测试：INV01—INV05 失败测试先行；FX01 最小源文本 golden；源↔显示回环；UTF-16/字素簇边界。
- Gate G1：源/显示回环、Unicode、图片对象位置、重复节点 ID、Long 计数、非法范围全部通过。

随后 S2 需上文第 6 节的确认结果。
