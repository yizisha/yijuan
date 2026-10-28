# FlowReader 内容引擎改造 — S6 阶段报告（书签 / 批注锚点升级）

执行日期：2026-09-11。依据：主计划 **3.1 强类型定位模型**、**第 9 节 书签、批注、搜索、听书的接入**、**5.4 旧阅读位置迁移**、**INV03 / INV04 / INV10**。

Gate：**S6 PASS**（全工程 **630 项单测全绿 / 0 失败 / 81 套件**，代码风格 0 违规，APK 构建成功）。

---

## 1. 先说清楚这一步真正修的是什么

S2 已经把 v10 的四列加进了 schema：

```text
bookmarks.locatorJson / locatorQuality / legacyUnit
annotations.rangeLocatorJson / locatorQuality
```

**但在 S6 之前，这五列是死的**：`toDomain()` 不读、`fromDomain()` 不写。
`Bookmark` / `Annotation` 领域模型里根本没有对应字段，仓库层也不构造它们。
后果是：表结构看起来「已完成迁移」，而书签与批注**从未真正获得内容坐标**——
改字号换字体后仍然只能回到同一个页码/像素，正是 R1 要解决的第一个问题。

S6 的工作就是把这五列真正接通，并让「旧数据没有坐标」这件事**被如实表达**而不是被猜出来。

---

## 2. 交付清单

| 文件 | 性质 | 职责 |
|---|---|---|
| `domain/.../MiniJson.kt` | **新增 252 行** | 零依赖 JSON 子集实现。**为什么不用 `org.json`**：它在 JVM 单测里是 Android SDK 空壳桩（方法抛 `Stub!`），一旦编解码路径被单测覆盖就崩；引第三方库又为一个纯数据合同加依赖（违反 0.3） |
| `domain/.../RangeLocator.kt` | **新增 138 行** | 批注/书签的内容坐标：**一条或多条有序 `SourceRange`** + revision + quality；`normalized()` 合并重叠/相接区间；`RangeLocatorCodec` 编解码 |
| `domain/.../Bookmark.kt` | 扩展 | 新增 `locator` / `locatorQuality` / `legacyUnit`；新增 `LegacyPositionUnit` 枚举（PAGE_INDEX / SCROLL_PX / UTF16 / UNKNOWN） |
| `domain/.../Annotation.kt` | 扩展 | 新增 `rangeLocator` / `locatorQuality`；`legacyRangeMatchesLocator()` 迁移一致性校验 |
| `data/.../BookmarkEntity.kt` | **接线** | `toDomain/fromDomain` 真正读写 `locatorJson` / `locatorQuality` / `legacyUnit` |
| `data/.../AnnotationEntity.kt` | **接线** | `toDomain/fromDomain` 真正读写 `rangeLocatorJson` / `locatorQuality` |
| `app/.../BookmarkRepositoryImpl.kt` | 规范化 | 无 locator 时强制质量 = UNRESOLVED，不允许调用方谎报 EXACT |
| `app/.../AnnotationRepositoryImpl.kt` | 规范化 | 插入前合并重叠区间（避免重叠批注重复输出文字）；不覆写旧字段 |
| `app/.../ReaderViewModel.kt` | 接线 | 创建书签/批注时写入权威 locator；`goToBookmark` 改走六级恢复 |
| `domain/src/test/.../RangeLocatorTest.kt` | **新增 25 项** | 模型不变量、重叠合并、编解码往返、畸形 JSON 拒绝、旧数据安全 |
| `app/src/test/.../BookmarkAnnotationLocatorMappingTest.kt` | **新增 8 项** | 实体层列真正接通、损坏 JSON 降级、未知枚举回落 |

---

## 3. 关键设计决策与理由

### 3.1 为什么批注必须是「多条区间」而不是两个整数

主计划 3.3 明确：「一个显示范围可以对应多个源范围……不得只存一个整数」。具体到本仓库：

1. **跨块选择**：一段选中文字可能横跨段落、图片、表格单元格；
2. **中间夹 synthetic**：人工缩进/列表圆点被 `DisplayMapping.displayRangeToSourceRanges` 排除后，
   真实文字会断成两段**不相连**的源范围。若强压成一个 `[start, end)`，
   会把中间**从未被选中**的文字也纳入批注范围——这是数据正确性问题，不是显示瑕疵。

因此 `RangeLocator.ranges` 保存有序区间列表，且 `normalized()` **只合并真正相接/重叠**的区间，
中间有空洞的区间保持分离（`disjointRangesStaySeparateSoUnselectedTextIsNotIncluded` 用例守护此行为）。

### 3.2 为什么质量必须由「解析结果」决定

实体层最初写成「只要 `locatorJson` 列非空就用列里存的质量字符串」。这有一个真实漏洞：
列里可能是被截断或手改的 JSON，此时 locator 解析为 **null**，但质量仍读作 `"EXACT"`——
于是书签**声称精确却没有任何可用坐标**，UI 就无法提示「定位待恢复」。

修复方式：`toDomain()` 先解析，**质量由解析结果唯一决定**（null → UNRESOLVED）。
这条由 `corruptedLocatorJsonDegradesToUnresolvedInsteadOfCrashing` 用例守护。

### 3.3 为什么旧位置单位要单独记录

主计划 5.4 要求「迁移不能从当前全局 PageMode 反推所有历史记录单位」——同一本书的用户
可能在分页模式存过页码、在滚动模式存过像素，而模式后来被改掉了。因此
`LegacyPositionUnit` 显式区分 `PAGE_INDEX` / `SCROLL_PX` / `UTF16` / `UNKNOWN`，
未知时**不得**把旧整数送入新正文坐标。未知枚举值一律回落到 `UNKNOWN`（不猜）。

### 3.4 为什么自己写 JSON

`org.json` 在 JVM 单元测试环境是 Android SDK 的桩实现（调用抛 `RuntimeException("Stub!")`）。
本项目的编解码**必须**能被纯 JVM 单测覆盖（否则真机才会暴露的解析缺陷无法回归），
而引入 kotlinx-serialization / Gson 又要为一个纯数据合同新增依赖（主计划 0.3 禁止）。
`MiniJson` 只实现合同需要的子集，并且**严格拒绝**畸形输入：解析后必须消费完全部输入
（`{}{}` 判失败），非法范围整体判失败而非截断。

---

## 4. 门禁验证与测试数据

```powershell
pwsh -NoProfile -File R:\tools\verify.ps1
```

| 项目 | 结果 |
|---|---|
| 代码风格 | **PASS**（0 违规） |
| 单元测试 | **PASS**（**630 项 / 0 失败 / 0 错误 / 81 套件**） |
| `:app:assembleDebug` | **PASS**（29,552,429 字节） |

单测 597 → **630（+33）**：

```text
domain : suites=18 tests=87     （含 RangeLocatorTest 25 项）
app    : suites=37 tests=308    （含 BookmarkAnnotationLocatorMappingTest 8 项）
```

### 过程中发现并修复的问题

| 问题 | 表现 | 修复 |
|---|---|---|
| 损坏 JSON 仍声称 EXACT | 实体测试 `corruptedLocatorJsonDegradesToUnresolvedInsteadOfCrashing` 失败（expected UNRESOLVED but was EXACT） | 质量改由解析结果决定（见 3.2） |
| 测试断言与实现语义不符 | `normalizationSortsByDocumentThenStart` 期望 `[a,a,b]` 实得 `[a,b]` | 实现正确（相邻区间本就该合并），修正测试改用不相接区间，并补充相邻合并用例 |
| `data` 模块无 test 配置 | 测试放在 `data/src/test` 无法编译（该模块无 junit 依赖） | 迁移到 `app/src/test`，删除空的 `data/src/test` |

---

## 5. 阶段验收状态矩阵

| 任务 / 模块 | 状态 | 交付文件 | 验证依据 |
|---|:---:|---|---|
| **S6 内容坐标模型（书签）** | **PASS** | `Bookmark.kt`, `MiniJson.kt` | RangeLocatorTest 25 项 |
| **S6 内容坐标模型（批注多区间）** | **PASS** | `RangeLocator.kt`, `Annotation.kt` | 重叠合并 / 断点分离 / 编解码往返用例 |
| **S6 实体层 v10 列真正接通** | **PASS** | `BookmarkEntity.kt`, `AnnotationEntity.kt` | BookmarkAnnotationLocatorMappingTest 8 项 |
| **S6 仓库规范化（不猜、不覆写）** | **PASS** | `BookmarkRepositoryImpl.kt`, `AnnotationRepositoryImpl.kt` | 单测 + 597→630 全绿 |
| **S6 创建书签/批注写入权威 locator** | **PASS** | `ReaderViewModel.kt` | 编译 + 全量门禁 |
| **S6 `goToBookmark` 走六级恢复** | **PASS** | `ReaderViewModel.kt` (`navigateToLocator`) | `ReaderNavigationTest` 5 项 + 全量门禁 |
| **S6 真机验证（书签/批注真实交互）** | **NOT_RUN** | — | 需真机手动建书签→改字号→验证回到同一处；**且真机数据当前缺失（见 S5 报告第 0 节），必须先解决备份** |
| **S7 全局搜索升级** | **NOT_RUN** | — | 待实现 |
| **S8 目录与分卷 UI 联动** | **NOT_RUN** | — | 待实现 |
| **S9 清理遗留裸 Int `currentPosition`** | **部分** | `BookEntity.kt` | 书签/批注已迁移；分页路径仍写像素 |
| **S10 / S11** | **NOT_RUN** | — | 待运行 |

---

## 6. 未验证项与已知限制（不得视为已通过）

1. **真机交互未验证**：单测覆盖了模型与实体层，但「真机上建书签 → 改字号 → 回到同一处内容」
   这一用户可见行为尚未实测（真机数据待恢复，且需要手动操作）。
2. **跨块选择尚未产生多区间**：`addAnnotation` 目前接收单一 `[start, end)`，
   落成单区间 locator。真正的跨块选择需要选择层把多个 `SourceRange` 传上来——
   模型层（`RangeLocator` 多区间 + 合并）已就绪，**调用链尚未打通**。
   这是 S6 的明确剩余工作。
3. **搜索与听书未接入**（主计划第 9 节表中另外两行）：`SearchResult` 仍是裸 `position`，
   TTS 仍用 `state.currentPosition` 起读。属于 S7 与后续工作。
4. **备份/还原未适配新字段**：`BackupRepository` 直接搬运实体，v10 列会随之往返，
   但**未验证**旧备份（无 locator）导入后的质量标记是否正确。
5. **旧数据迁移未执行**：`content_migration_jobs` 的逐书重解析尚未实现，
   现有旧书签/批注仍为 `UNKNOWN` + `UNRESOLVED`，需用户手动定位一次才会获得 locator。

---

## 7. 新会话接力入口点

1. **先处理真机数据**：按 S5 报告第 0 节建立备份脚本，再谈真机验证。
2. **打通跨块选择 → 多区间批注**：让 `ReaderParagraph`/`ReaderContent` 的选择回调
   返回 `List<SourceRange>`（`DisplayMapping.displayRangeToSourceRanges` 已具备该能力，
   且其切分缺陷已在 S5 修复），在 ViewModel 侧改用 `addAnnotationRanges`。
3. **S7 全局搜索升级**：`SearchResult` 携带 revision + documentKey + UTF-16 范围，
   用命中文本投影验证，不得把 FTS 高亮索引直接当 UTF-16。
4. **实现旧数据迁移**（`content_migration_jobs`），把存量书签/批注从 `UNKNOWN` 升级为可用 locator。
5. **S9**：切断分页路径的裸像素写入，让分页与滚动共用同一 `TextLocator` 权威。
