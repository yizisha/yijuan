# FlowReader 内容引擎改造 — S4 阶段报告（第 1 批：导航状态机）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 **6.1 意图/命令/反馈分离**、**6.2 状态与转移**、
**6.3 手势分发**、**6.4 边界**、**6.5 重排不漂移**、**6.7 保存与生命周期**、**8.3 历史与返回**。
Gate：G4（**PARTIAL**，本批只完成纯逻辑层）。

---

## 1. 本批交付

| 文件 | 类型 | 职责 |
|---|---|---|
| `feature/reader/.../ReaderNavigationCoordinator.kt` | **新增 470 行** | 纯函数导航状态机：意图 → 状态+效果；反馈 → 提交或丢弃 |
| `feature/reader/src/test/.../ReaderNavigationCoordinatorTest.kt` | **新增 17 项测试** | T020—T025、T072；INV07、INV08 |

**纯逻辑，零依赖**：不碰 Compose、不碰数据库、不需要 Robolectric。
这正是 6.1 要求"以纯逻辑类测试规则，不为本次任务大规模搭建新的 ViewModel 测试框架"的落地方式。

### 1.1 三个不变量 → 代码落点

| 不变量 | 落点 |
|---|---|
| **INV07** 一次触摸序列至多一个 `NavigationIntent` | `noteGestureStart(gestureId, ignored)`；派发前 `acceptedGestures.add`，重复即 `GESTURE_ALREADY_ACCEPTED`；`ignoredGestures` 里的序列即使抬起时已空闲也不触发（6.3 第 4 条） |
| **INV08** 只有当前 generation 的反馈可提交 | `onViewport` 先比对 `navigationGeneration` 与 `layoutGeneration`，不匹配一律 `IgnoredStale`，不写任何状态 |
| **6.2** 非 Idle 的额外 Turn 丢弃不排队 | `if (current.state !is State.Idle) → Dropped("BUSY_DROPPED_TURN")`，**不入队** |

### 1.2 状态机（严格按 6.2 表格）

`Idle → Resolving → Loading → LayingOut → Restoring → Committing → Idle`，
失败走 `ErrorWithPreviousContent`（**保留前一章与定位**，不先把标题切过去再显示空白正文）。

`State.Resolving` 携带 `requestedTarget`：请求目标必须随状态走，否则 `onTargetResolved` 只能去读
`committedLocator`，把"用户要求去哪"和"现在在哪"混为一谈（初稿就是这个问题，已修）。

### 1.3 三个定位字段的分工（这是本批最容易写错的地方）

| 字段 | 含义 | 谁读它 |
|---|---|---|
| `committedLocator` | **权威主线定位**（INV01） | 进度、书架、统计 |
| `contentLocator` | **当前屏幕上正在显示**的位置（可能是辅助内容） | 8.3 返回栈"从哪儿跳走的" |
| `lastMainLocator` | 浏览辅助内容期间保持的主线定位 | 10.2 主线进度 |

---

## 2. 本批抓到的缺陷（都是"测试才暴露"的类型）

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | 辅助内容的历史栈去重判据用错：拿"当前主线定位是否等于原处"当重复判据 | 浏览脚注时主线定位本来就不动，于是**每一次脚注跳转都被误判为重复**：实测 60 次跳转后 `history.size == 1`，**返回链直接断掉**（R6 的核心行为） | 判据改为"**离开的原处**是否已在栈顶"（`contentLocator`），并新增独立字段区分三个定位 |
| 2 | 断言把 `Turn` 后的 `navigationGeneration` 写成常量 1 | 测试侧错误：`settleAt` 里的 `Restore` 已经推进过一代 | 改为先记录基线再比较 `+1`，并补注释说明"被丢弃的 20 次不得推进 generation" |

---

## 3. 测试证据（17 项）

| 用例 | 断言 | 对应条款 |
|---|---|---|
| `oneGestureIdCommitsAtMostOneTurn` | 同一 `gestureId` 第二次 → `GESTURE_ALREADY_ACCEPTED` | INV07、T023 |
| `gestureThatStartedDuringAnimationNeverFiresEvenIfIdleOnRelease` | 动画期间开始的手势即使抬起时空闲也 `GESTURE_IGNORED` | 6.3 第 4 条、T022 |
| `extraTurnsWhileBusyAreDroppedNotQueued` | 连续 20 次额外翻页全部 `BUSY_DROPPED_TURN`；generation 只 +1；**稳定后不补翻** | 6.2、T022 |
| `staleNavigationGenerationIsIgnored` | 旧一代快照 → `STALE_NAVIGATION_GENERATION`，权威定位不被覆盖 | INV08、T017 |
| `staleLayoutGenerationIsIgnoredAfterReflow` | 旧 layoutGeneration → `STALE_LAYOUT_GENERATION` | INV08 |
| `unsettledLayoutNeverCommitsOrCountsATurn` | 未稳定布局不提交、`settledTurns` 不变 | 10.3、6.5 第 5 条 |
| `explicitTargetCancelsThePreviousRequestAndOnlyTheLatestSurvives` | 新目标 → `CancelInFlight(旧请求)` + generation++；旧解析结果 `TARGET_RESOLVED_STALE` | 6.2、T025 |
| `loadingFailureKeepsPreviousContentAndLocator` | 失败后定位仍是旧值；`clearError` 回 Idle 且定位不变 | 6.2、T020 |
| `fullHappyPathCommitsVisibleContentAndCountsOneTurn` | Restore→Resolving→Loading 全链推进 | 6.2 |
| `reflowKeepsTheSemanticAnchorInsteadOfTheNewPageStart` | 重排后新页首 5_900，**提交的仍是语义锚点 6_000** | 6.5 第 6 条、T012 |
| `twentyConsecutiveReflowsDoNotDriftTheLocator` | **20 次重排 + 每次都报偏移的页首，权威 locator 不漂移**（12_345）；`layoutGeneration == 20` | 6.5 第 6 条、T012、G4 |
| `auxiliaryContentDoesNotPolluteTheMainLocatorAndReturnGoesBack` | 打开脚注后主线定位不动；返回回到主线原处 | 10.2、8.3、T060 |
| `historyIsCappedAtFiftyEntriesWithoutInfiniteDuplicates` | 60 次跳转后栈容量 = 50 | 8.3 第 2 条 |
| `detachCommitsOnlyTheLastConfirmedLocator` | 在途目标 7_000 **不被当成已读**；只提交 1_500 并取消在途请求 | 6.7、T016 |
| `boundaryFeedbackIsEmittedOnceAndStaysPut` | 起点后退发一次边界提示，位置不动 | 6.4、T020 |
| `backwardAtAbsoluteStartEmitsBoundaryFeedbackOnce` | 绝对起点（`sectionId=null`、offset 0）同样只提示一次 | 6.4 |

```powershell
pwsh -NoProfile -File tools\verify.ps1
→ BUILD SUCCESSFUL in 1m 56s
→ verifyKotlinStyle 0 违规
→ 596 项测试 / 0 失败 / 0 错误 / 89 套（本轮 +17）
→ APK 29,373,301 字节，2026/9/10 23:10
→ SHA-256 95C01B7D8F5D1D5AD0CE68BF622C660BAFF6D879215E29EF3944C129CF493493
```

**关于 ktlint**：新增测试文件有 15 处格式违规（表达式体首行、单行参数、行长），
已用 `:feature:reader:ktlintTestSourceSetFormat` 自动修正；**修正前后逐行去空白比对确认只改了换行与折行**，
没有触碰任何断言或逻辑。

---

## 4. S4 完成矩阵

| S4 条目 | 状态 | 实现文件 | 验证 | 剩余问题 |
|---|---|---|---|---|
| 6.1 意图/命令/反馈分离 | **PASS** | `ReaderNavigationCoordinator.kt` | 17 项 | 宿主侧命令执行未接 |
| 6.2 状态与转移 | **PASS** | 同上 | 状态链 + 失败保留 | — |
| 6.3 手势分发（gestureId / ignored 序列） | **PASS** | 同上 | 3 项 | 真实手势层分配 `gestureId` 待接 |
| 6.4 跨章边界与边界提示 | **PARTIAL** | 同上 | 边界反馈 2 项 | "上一章末页/下一章开头"的 section 级判定属宿主 |
| 6.5 分页恢复算法 | **PARTIAL** | 同上（锚点不漂移） | 2 项含 20 次重排 | 真实 `TextMeasurer` 断行与页二分查找未做 |
| 6.6 滚动恢复算法 | **NOT_RUN** | — | — | 需可见行 `TextLayoutResult` 投影 |
| 6.7 有序保存 | **PARTIAL** | 同上（`Detach` 只提交已确认位置） | 1 项 | `ReaderStateRepository.save(sequence)` 落库未接 |
| 8.3 历史与返回 | **PARTIAL** | 同上（栈 + 容量 + 去重） | 2 项 | 系统返回键优先级、SavedStateHandle 恢复未接 |
| `ContentRepository`（12.2） | **NOT_RUN** | — | — | 引用数 0 |
| `ContentLocatorResolver`（3.4 六级恢复） | **NOT_RUN** | — | — | 引用数 0 |
| `DisplayMapping`（3.3） | **NOT_RUN** | — | — | 引用数 0 |
| `LayoutAdapter` | **NOT_RUN** | — | — | — |
| Reader 接线（ViewModel/Screen/PagedReader/ReaderContent） | **NOT_RUN** | — | — | **R1 仍未达成** |
| **Gate G4** | **NOT_RUN（依赖上述项）** | — | — | 需真实界面链路 + 20 次重排设备验证 |

---

## 5. 下一批（S4 剩余，按依赖顺序）

1. **`ContentLocatorResolver`**：3.4 的六级恢复优先级（EXACT → CONTEXT_MATCH → 4096 窗口 →
   文档指纹重命名 → APPROXIMATE → UNRESOLVED），纯函数、可确定性测试。
2. **`DisplayMapping`**（3.3）：边界数组长度 = `displayText.length + 1`、多源范围、synthetic 标记、
   隐藏目标归并到下一可见块并返回 `EXACT` + `HIDDEN_TARGET_ADJUSTED`。
3. **`ContentRepository` + `LayoutAdapter`**：`readWindow` / `resolveLocator` / `resolveLink` /
   `getToc` / `getProgressIndex`，窗口 ≤65,536 UTF-16 单位、不截坏字素簇。
4. **Reader 接线**：`ReaderViewModel` 用协调器 + `ReaderStateRepository.save(sequence)`；
   `PagedReader`/`ReaderContent` 上 `LayoutAdapter.captureViewport`；**停用 `Book.currentPosition` 裸 Int 写入**（R1）。
5. Gate G4 设备验证：两种模式从指定源位置进入、改字体/旋转/切模式、退出重开、20 次重排不漂移。
