# FlowReader 内容引擎改造 — S1 完成报告与整体完成矩阵

执行者：DSH Agent。日期：2026-09-10。依据：`FLOWREADER_CONTENT_ENGINE_IMPLEMENTATION_PLAN.md` 第 13 节 S1/S2 与 Gate G1，交付格式依据 15.2。

**本文件不声称计划全部完成。** 见第 3 节完成矩阵：S0 与 S1 已交付并验证，S2—S11 为 NOT_RUN。

---

## 1. S1 交付物（强类型定位模型、规范源文本口径、显示/进度映射）

### 1.1 新增生产代码

| 文件 | 内容 | 对应计划条款 |
|---|---|---|
| `domain/.../model/ContentLocator.kt` | `TextLocator`（含 `affinity`/`blockId`/`elementId`/`context`）、`TextContext`（各 ≤32 code point）、`Affinity`、`ResolutionQuality`（EXACT/CONTEXT_MATCH/APPROXIMATE/UNRESOLVED）、`SourceRange`（UTF-16 半开区间）、`LocatorResolution`、`ContentLocator` 带类型联合（`Text`/`FixedPage`）、`ContentProgressSnapshot`、`ContentErrorCode`（15 个稳定错误码） | 3.1、3.4、10.1、16 |
| `feature/reader/.../ContentProgressIndex.kt` | 权重前缀和进度索引：`progress(ordinal, withinFraction)`、`remainingUnits`、滑杆逆映射 `sectionAt`/`withinFractionAt`、零权重段处理、`isReady`；`TextUnits` 非空白 code point 计数、代理对保护、code point 索引换算 | 10.2、INV03、INV04、INV11 |
| `feature/reader/.../ReaderSessionPolicy.kt` | 集中阈值（空闲 5 分钟、曝光 2 秒、速度窗口 60 秒/300 字、合理区间 50—2000 字/分）；`ReadingSpeedEstimator`（总字/总分钟，样本不足返回 null）；`SessionDelta`（视觉/TTS/有效字/翻页四分量互斥）；`ReadingTimeStateMachine`（可注入单调时钟、空闲截断、TTS 与视觉互斥） | 10.3、10.4、10.5、INV12 |

### 1.2 与旧行为的关键差异（本次修正的缺陷）

| 旧实现 | 新实现 |
|---|---|
| `ReadingProgress.fraction()` 按章节等权（F03） | 按文本量加权；两章 100/900 字的 golden 从 **50% → 10%** 通过 |
| 滑杆逆映射按章数反推 | 按累计权重二分逆查；10% 正确落在第二章 |
| 速度用 EMA 累积**瞬时**采样（1 秒样本即成为“阅读速度”） | 仅 ≥60 秒且 ≥300 字的窗口计入，用总字/总分钟 |
| `speed.coerceAtLeast(100f)` 无样本时伪造 ETA | 样本不足返回 `null`，UI 显示「估算中」 |
| 默认墙上时钟 `System::currentTimeMillis`（F04） | 可注入单调时钟；墙上时间只用于划分自然日 |
| 时长只有 `(pages, seconds)`，且需 `pages > 0` 才落库 | `SessionDelta` 四分量，任一分量 > 0 即可落库 |

### 1.3 测试（Gate G1 证据）

`feature/reader/src/test/.../ContentProgressIndexTest.kt` — **20 项**，全部通过：

- 10.2 golden：100/900 字四段取值（0% / 10% / 50% / 100%）与剩余字量；零权重段不被滑杆选中但保留序号；空索引报 `isReady=false` 而不是假装 0%；越界与 `NaN` 夹取。
- 文本量口径：emoji 占 2 个 UTF-16 单位但计 1 字；空白与 U+FFFC 不计；代理对永不切开；code point 索引换算。
- 10.4：1 秒样本不产生速度；多窗口按总字/总分钟聚合；异常区间不用于 ETA；`null` 而非伪造；到末尾为 0。
- 10.3：同页 90 秒保存 90 秒且 `turns=0`；空闲只计到 `lastActivity+5min`；离开正文立即冻结；TTS 与视觉同时段只计一种。

**测试过程中抓到并修掉两个实现缺陷**（不是放宽断言，而是修实现）：

1. `ContentProgressIndex.totalUnits` 原先取 `prefixUnits.last()`，而 `prefix[i]` 语义是“第 i 段之前的累计”，导致总量取到倒数第二段前缀（100 而非 1000）。已改为独立 `total` 字段。
2. `ReadingTimeStateMachine` 用 `lastSettledAt <= 0L` 作“未初始化”哨兵，注入时钟从 0 开始时首段有效时间被静默丢弃。已改为显式 `initialized` 标志。

另有 3 处 ktlint 风格违规（表达式体首行可同行）已按项目门禁修正。

---

## 2. 验证命令与结果（可复现）

```powershell
pwsh -NoProfile -File tools/verify.ps1
```

```
PS 7.6.6 | tasks: verifyKotlinStyle, testDebugUnitTest, :app:assembleDebug
--- verdict ---
BUILD SUCCESSFUL in 1m 56s
apk: 28,938,475 bytes, 2026/9/10 20:30:45
```

测试统计（按模块解析 `build/test-results/TEST-*.xml`）：

```
TOTAL tests=440 failures=0 errors=0 suites=79
  G0EqualWeightProgressDefectTest      3 tests, 0 failed
  G0PositionAndStatsDefectTest        11 tests, 0 failed
  ContentProgressIndexTest            20 tests, 0 failed
```

基线 406 → 现 440（新增 34 项：G0 复现 14 + S1 验收 20）。`verifyKotlinStyle` 0 违规。

> **重要说明**：S1 只新增模型与纯逻辑，**未接入阅读界面**，因此 App 的可见行为与上一版一致（进度仍是等权）。接线属于 S4/S9，见第 4 节入口点。这是计划本身的排序（S1 = 纯模型，不碰数据库、不改真实书数据）。

---

## 3. 整体完成矩阵（15.2 要求格式）

图例：**PASS** 已实现且自动化验证 / **PARTIAL** 部分实现 / **NOT_RUN** 未执行 / **BLOCKED** 缺输入或环境。

| 阶段/需求 | 状态 | 实现文件 | 验证命令 | 证据 | 剩余问题 |
|---|---|---|---|---|---|
| S0 基线审计 | **PASS** | — | `git rev-parse HEAD`、全引用 grep | `S0-baseline-report.md` | F01—F14 全部确认 |
| G0 复现（5 类中 4 类） | **PASS** | — | `verify.ps1 testDebugUnitTest` | 14 项复现测试 | 卷丢失/双锚点/混排图片三条待 S3 抽取后补（T041/T035/T048） |
| S1 定位与坐标模型 | **PASS** | `ContentLocator.kt`、`ContentProgressIndex.kt`、`ReaderSessionPolicy.kt` | `verify.ps1` | 20 项测试 + `BUILD SUCCESSFUL` | — |
| R1 统一内容定位（接通界面） | **NOT_RUN** | 模型已备 | — | — | 需 S4 接线：`ReaderViewModel.goToChapter/updatePosition`、`PagedReader`、`ReaderContent` |
| R2 EPUB 结构保留 | **NOT_RUN** | — | — | — | 需 S3：`EpubDocumentParser` 等 7 个组件 |
| R3 进度/时长/频率口径 | **PARTIAL** | 索引与规则已备 | `ContentProgressIndexTest` | 20 项 | 未接入 UI 与数据库；F02 的 `remainingMinutes` 尚未替换 |
| R4 分卷目录树 | **NOT_RUN** | — | — | — | 需 S3（目录树解析）+ S6（折叠交互、稳定 key） |
| R5 EPUB 插图（分页+滚动） | **NOT_RUN** | — | — | — | 需 S3/S5：资源对象化、`ImageLayoutSpec`、Coil 解码状态 |
| R6 锚点/脚注/返回 | **NOT_RUN** | — | — | — | 需 S3（锚点索引）+ S7（链接路由与历史栈） |
| S2 存储与 v10 迁移 | **NOT_RUN** | — | — | — | 授权已获；入口：新增 7 表 + `books` 三列 + 3 表 locator/v2 列，`MIGRATION_9_10` |
| S3 结构化导入 | **NOT_RUN** | — | — | — | 入口：从 `BookParser` 抽出 `EpubResourceResolver`/`EpubNavigationParser`/`EpubDocumentParser`；先补 F05/F08/F10 复现 |
| S4—S11 | **NOT_RUN** | — | — | — | 见第 4 节入口点 |

**完成定义（15.3）自评**：未达成。六项需求均未接通真实界面与持久化；无设备验收；`currentPosition` 裸 Int 仍在生产路径上。因此本报告**不写“全部完成”**。

---

## 4. 下一阶段精确入口点（可直接续作）

### S2 存储与迁移（授权已获）
1. `data/.../local/entity/ContentIndexEntities.kt`：`PublicationRevisionEntity`、`PublicationDocumentEntity`、`ReadingSectionEntity`、`TocNodeEntity`、`ReaderStateEntity`、`ContentMigrationJobEntity`、`ReadingSessionDeltaEntity`。
2. `data/.../local/AppDatabase.kt`：`version = 10`，注册新实体与 DAO。
3. `app/.../di/AppModule.kt`：`MIGRATION_9_10`（additive；`books` 加 `publicationId/activeRevision/contentState` 并**逐行回填 UUID**；`bookmarks`/`annotations`/`reading_stats` 加列并保留旧列）。
4. 校验：`data/schemas/.../10.json` 由构建生成（不手写）；补 4/5/6/7/8/9→10 逐路径迁移测试。
5. Gate G2：迁移前后书籍/书签/批注/分类/阅读列表/统计记录数一致。

### S3 解析器抽取（同时补齐 G0 剩余三条复现）
1. 从 `BookParser.kt` 抽出：`EpubResourceResolver`（唯一路径解析点，替换 `substringBefore('#')` 与表单式 `URLDecoder`）、`EpubNavigationParser`（EPUB3 nav 树 + EPUB2 NCX 递归，保留 fragment 与 `<span>` 分组）、`EpubDocumentParser`（结构块 + 规范源文本 + 锚点索引 + 资源对象，替换 `[IMG:]`）。
2. 第一批测试：`appendChunkedChapter` 卷字段（T041）、同文档双锚点（T035）、图文混排（T048）—— 三条当前 NOT_RUN 的 G0 复现。
3. 修复 `BookParser.kt:1068-1075`（`Chapter` 未传 `volume`）与 `:1071`（`(N)` 伪章节标题）。

### S9 统计接线（无迁移即可先做的部分）
1. 新增 `ChapterDao` 查询：按章返回非空白字符数（`LENGTH(REPLACE(...))` 逐项替换换行/空格/U+3000），不加载正文。
2. `ChapterRepository.getContentUnitCounts(bookId)` → `ReaderViewModel` 构建 `ContentProgressIndex`。
3. 替换 `ReadingProgress.fraction/chapterAt` 的调用点（`ReaderProgressEngine.fraction`、`ReaderControls` 滑杆逆映射、页脚百分比）。
4. 替换 `ReaderProgressEngine.remainingMinutes`：改用 `remainingUnits + ReadingSpeedEstimator`，删除 `speed.coerceAtLeast(100f)`。
5. 放宽 `ReaderViewModel.kt:193,202` 的 `seconds > 0 && pages > 0` 门槛为 `SessionDelta.isPersistable`。

---

## 5. 交付物清单

| 项 | 路径 |
|---|---|
| S0 基线报告 | `C:\Users\lenovo\Desktop\DSH-image-gen-工作流\outputs-content-engine\S0-baseline-report.md` |
| S1 报告与完成矩阵（本文件） | `C:\Users\lenovo\Desktop\DSH-image-gen-工作流\outputs-content-engine\S1-report-and-matrix.md` |
| 本次新增生产代码 | `domain/src/main/java/com/flowreader/app/domain/model/ContentLocator.kt`、`feature/reader/src/main/java/com/flowreader/feature/reader/ContentProgressIndex.kt`、`feature/reader/src/main/java/com/flowreader/feature/reader/ReaderSessionPolicy.kt` |
| 本次新增测试 | `feature/reader/src/test/java/com/flowreader/feature/reader/ContentProgressIndexTest.kt`、`core/src/test/java/com/flowreader/app/core/util/G0EqualWeightProgressDefectTest.kt`、`feature/reader/src/test/java/com/flowreader/feature/reader/G0PositionAndStatsDefectTest.kt` |
| APK | `app/build/outputs/apk/debug/app-debug.apk`（28,938,475 字节，versionCode 5668 / 0.1.7） |

未提交 Git、未发布、未上传书籍。
