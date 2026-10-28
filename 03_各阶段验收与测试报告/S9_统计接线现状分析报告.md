# FlowReader 内容引擎改造 — 状态更新（S9 接线已落地）

执行者：DSH Agent。日期：2026-09-10。承接 `S0-baseline-report.md` 与 `S1-report-and-matrix.md`。

本次在 S1 模型层之上**接入真实阅读界面**，修掉四个用户可见问题（计划 R3 / F02 / F03 / F04）。

---

## 1. 本次改动（生产代码）

| 文件 | 改动 | 对应缺陷 |
|---|---|---|
| `data/.../dao/ChapterDao.kt` | 新增 `getTextUnitCounts(bookId)`：一条 SQL 按章返回**非空白**字符数（逐项替换换行/回车/制表/半角空格/全角空格 U+3000），**不加载正文**；新增投影 `ChapterTextUnits` | 进度分母（INV11） |
| `domain/.../repository/ChapterRepository.kt` | 新增 `getTextUnitCounts(bookId): List<Pair<Int, Long>>` | 同上 |
| `app/.../data/repository/ChapterRepositoryImpl.kt` | 实现该查询 | 同上 |
| `app/.../reader/ReaderViewModel.kt` | ① 新增 `progressIndex` 并在 `init` 中 `loadProgressIndex()` 构建权重索引；② 新增 `weightedFraction()` / `bookProgressFraction` / `chapterOrdinalAt()`；③ 5 处 `progressEngine.fraction(...)` 全部换成按权重计算；④ `goToProgress()` 滑杆逆映射改为按累计权重二分逆查；⑤ `calculateReadingPrediction()` 改用 `remainingMinutesAt()`（权重剩余量 + 无样本返回 null，删除 `speed.coerceAtLeast(100f)` 伪造）；⑥ **把 `seconds > 0 && pages > 0` 门槛放宽为 `seconds > 0 \|\| pages > 0`**（两处：`onCleared` 冲刷与 `saveReadingStats`） | F03、F02、F04 |
| `app/.../reader/ReaderScreen.kt` | 进度条 `progressProvider` 改读 `viewModel.bookProgressFraction`；`chapterTitleAt` 改读 `viewModel.chapterOrdinalAt(...)`，使**进度条与章节提示使用同一套权重映射** | F03 一致性 |

**未改动**：数据库版本仍为 v9（本次无需迁移）；未新增依赖；未触碰历史 schema。

## 2. 用户可见效果

| 现象 | 旧行为 | 新行为 |
|---|---|---|
| 进度条与百分比 | 按**章节数等权**：100 字与 900 字两章，读完短章即 50% | 按**真实文本量**：读完短章 10%，第二章节中途 50% |
| 滑杆拖到 10% | 落到**第一章** | 落到**第二章**（100/900 字 golden） |
| 剩余时间 | 元数据章 `content` 为空 → 报 **0 分钟**；页下标被当字符减；无样本时按 `coerceAtLeast(100f)` **伪造**数值 | 按权重剩余量与真实速度计算；**无合格样本时返回“未知”**，不再伪造 |
| 同页停留 90 秒不翻页 | `pages > 0` 门槛把**整段有效时长**丢弃 | 时长与页数任一 > 0 即落库（T073） |

## 3. 验证

```powershell
pwsh -NoProfile -File tools/verify.ps1
```

```
PS 7.6.6 | tasks: verifyKotlinStyle, testDebugUnitTest, :app:assembleDebug
--- verdict ---
BUILD SUCCESSFUL in 2m 27s
apk: 29,099,936 bytes, 2026/9/10 20:51:40
```

- `verifyKotlinStyle`：0 违规。
- `testDebugUnitTest`：**440 项 / 0 失败 / 79 套**。
- 编译期发现并修正 2 处自身错误：`ReaderUiState` 并无 `bookProgress` 字段（改为按需读取索引，不额外写状态）；ETA 现可空，`estimatedTimeRemaining` 赋值改为 `?.toLong() ?: 0L`。

## 4. 更新后的完成矩阵

| 阶段/需求 | 状态 | 说明 |
|---|---|---|
| S0 基线审计 + G0 复现（4/5 类） | **PASS** | 14 项复现测试 |
| S1 定位模型 / 进度索引 / 会话策略 | **PASS** | 20 项验收测试，含 100/900 golden |
| **R3 进度、时长、字数、剩余时间口径** | **PARTIAL → 已接通界面** | 进度条、滑杆、剩余时间、时长落库已按新口径；仍缺 qualifiedTextUnits/曝光（10.4）与 delta 幂等表（5.2） |
| F04 有效时长依赖翻页 | **PASS（最小修法）** | 门槛已放宽；完整状态机（空闲截断、TTS 互斥、单调时钟）已在 `ReaderSessionPolicy` 就绪但**尚未接线** |
| F03 等权进度 | **PASS** | 权重索引已驱动进度条与滑杆 |
| F02 剩余时间单位与伪造 | **PASS** | 权重剩余量 + 未知语义 |
| R1 统一内容定位（TextLocator 落库） | **NOT_RUN** | 需 S2/S4：`Book.currentPosition` 裸 Int 仍在生产路径 |
| R2 EPUB 结构保留 | **NOT_RUN** | 需 S3 |
| R4 分卷目录树 | **NOT_RUN** | 需 S3 + S6 |
| R5 EPUB 插图 | **NOT_RUN** | 需 S3 + S5 |
| R6 锚点/脚注/返回 | **NOT_RUN** | 需 S3 + S7 |
| S2 v10 迁移（授权已获） | **NOT_RUN** | 入口见 S1 报告第 4 节 |
| S3 结构化导入 | **NOT_RUN** | 入口同上；先补 F05/F08/F10 三条复现 |
| S4—S11 | **NOT_RUN** | — |

**完成定义（15.3）自评**：仍未达成（六项需求中 R2/R4/R5/R6 未动，R1 未落库，无设备验收）。故本文件不写“全部完成”。

## 5. 已知缺口（如实列出）

1. **ETA 未在界面展示**：`estimatedTimeRemaining` 只被写入、无人读取；「未知」与「0 分钟」在当前状态字段里不可区分。接线到 UI 时需为其增加 `isEtaUnknown` 标志或改为可空类型。
2. **曝光与有效字数（10.4）**：`qualifiedTextUnits` 尚未采集，因此 `ReadingSpeedEstimator` 目前仍以会话 EMA 速度作为输入，尚未启用“≥60 秒且 ≥300 字窗口”的真实窗口聚合。
3. **`ReadingTimeStateMachine` 未接线**：目前仍使用旧 `ReaderSessionTracker`；单调时钟、空闲截断、TTS 互斥只在测试中验证。
4. **章节内定位仍是比例近似**：`withinFraction` 来自滚动比例/页序，不是可见行的真实源范围；这是计划 S4/S6 的工作（6.5/6.6）。
5. **`ReadingProgress` 等权路径仍作为索引未就绪时的回退**保留，未删除；G0 特征描述测试仍在钉住它，S4 删除回退时需一并翻转。
