# FlowReader 内容引擎改造 — S7 报告（全局搜索升级：命中可精确定位）

执行日期：2026-09-11。依据：主计划 **第 9 节 书内/全局搜索**、**3.1 强类型定位模型**、**3.4 六级恢复**、**INV03**。

Gate：**PASS**（全工程 **716 项单测全绿 / 0 失败 / 89 套件**，代码风格 0 违规，APK 构建成功）。

---

## 1. 这一步修的是什么

主计划第 9 节对搜索的要求是：

> 书内/全局搜索 | 结果包含 revision、documentKey、UTF-16 source range | FTS 高亮索引/分词位置不能直接冒充 UTF-16；用命中文本投影验证

旧实现三项全缺：

| 缺口 | 旧行为 | 后果 |
|---|---|---|
| 无内容坐标 | `GlobalSearchResult` 只有 `bookId/chapterIndex/chapterTitle/matchedText` | 点搜索结果**永远跳到章首**，用户还得自己找那一句 |
| 位置被冒充 | `matchedText` 就是 FTS `snippet()` 原文（带 `<<` `>>`） | UI 直接显示 `<<关键词>>` 标记；且没有任何 UTF-16 位置 |
| 无 revision/documentKey | 路由只传 `chapterIndex` | 书被重新导入后无法判断结果是否过期 |

---

## 2. 关键设计：为什么必须「回到正文重新定位」

FTS5 的 `snippet()` 返回的是**带标记的片段文本**，它内部的分词位置（token offset）：
- 不是 UTF-16 偏移（分词器按 token，标点/空白处理都不同）；
- 不对应任何稳定坐标（重建索引后可能变化）。

因此新层 [SearchHitProjector] 做两件事：

**① 投影**：从片段里取出标记**之间**的文字（不是查询词——FTS 用 `"...*"` 做前缀匹配，
查询「潮湿」可能命中「潮湿的气息」），回到规范正文里用 `indexOf` 找真实 UTF-16 位置。

**② 消歧**：同一个词在正文里可能重复。片段自带**命中处的上下文**，用它挑出正确的那一次。
只取「第一次出现」会把用户带到错误的段落——这是本层最容易被忽略的价值。

消歧失败时不假装精确：返回 `Confidence.APPROXIMATE`，UI 显示「约略定位」。

---

## 3. 交付清单

| 文件 | 性质 | 职责 |
|---|---|---|
| `domain/.../SearchHitProjector.kt` | **新增 219 行** | 片段标记解析、上下文提取、正文定位、消歧、多词合并；`Confidence.EXACT/APPROXIMATE` |
| `domain/.../GlobalSearchResult.kt` | 扩展 | 新增 `documentKey`/`revision`/`publicationId`/`ranges`/`confidence`；`isLocatable`；`toTextLocator()`/`toRangeLocator()` |
| `app/.../SearchRepositoryImpl.kt` | 接线 | 取该章正文 → `SearchHitProjector.project` → 填 verified ranges；展示文本去标记 |
| `app/.../Navigation.kt` | 路由 | Reader 路由新增 `locatorOffset` 参数 |
| `app/.../search/SearchScreen.kt` | UI | 传递已验证偏移；近似结果显式标注「约略定位」 |
| `app/.../ReaderViewModel.kt` | 接线 | `goToSearchHit(chapterIndex, offsetUtf16)`：走六级恢复，而非裸 Int |
| `app/.../util/FullTextSearch.kt` | 扩展 | `FtsSearchResult` 新增 `locatorOffset`/`locatorConfidence` 与 `isLocatable` |
| `app/.../ReaderViewModel.kt` (书内搜索) | 接线 | `searchInBook` 对每条命中回正文投影；`goToSearchResult(chapterIndex, locatorOffset)` 精确跳转 |
| `app/.../components/Dialogs.kt` | 接线 | 书内搜索结果点击传递已验证偏移 |
| 9 个 `strings.xml` | 资源 | `search_match_approximate` |
| `SearchHitProjectorTest.kt` | **新增 20 项** | 标记解析、消歧、前缀扩展、失败路径、合并、emoji 边界、与 RangeLocator 协同 |
| `GlobalSearchResultLocatorTest.kt` | **新增 10 项** | 可定位性、拒绝伪造 locator、质量降级、默认保守 |
| `SearchRepositoryLocatorWiringTest.kt` | **新增 6 项** | 仓库层真实接线：正文定位、标记剥离、陈旧索引、缺正文、重复词消歧 |
| `FtsSearchResultLocatorTest.kt` | **新增 4 项** | 书内搜索坐标：默认保守、可定位、offset 0 是合法位置、copy 不丢坐标 |

---

## 4. 过程中发现并修复的问题

| 问题 | 表现 | 修复 |
|---|---|---|
| **matchedText 与 ranges 不自洽** | 投影器测试 `expected:<甲乙丙丁> but was:<甲乙 丙丁>` | 先合并区间再生成文本；否则返回的 `ranges` 与 `matchedText` 各算各的（同类缺陷在 S6b 已出现过一次） |
| 上下文断言算错 | `contextBeforeAndAfterExcludeEllipsisRegion` 期望丢了紧邻逗号 | 实现正确（逗号属于紧邻上下文）；修正测试期望 |

---

## 5. 门禁验证与测试数据

```powershell
pwsh -NoProfile -File R:\tools\verify.ps1
```

| 项目 | 结果 |
|---|---|
| 代码风格 | **PASS**（0 违规） |
| 单元测试 | **PASS**（**716 项 / 0 失败 / 0 错误 / 89 套件**） |
| `:app:assembleDebug` | **PASS**（30,905,060 字节） |

单测 676 → **716（+40）**：SearchHitProjectorTest 20、GlobalSearchResultLocatorTest 10、
SearchRepositoryLocatorWiringTest 6、FtsSearchResultLocatorTest 4。

最关键的四项：

- `repeatedTermIsDisambiguatedBySnippetContext`（投影器 + 仓库各一份）：同词两次出现，
  上下文把定位锁到**第二次**——这是「跳到正确那一句」的证据。
- `prefixExpandedMatchUsesMarkedTermNotQuery`：前缀扩展命中时必须以标记词为准。
- `staleIndexWithoutMatchingContentYieldsNoRangeAndIsLabelled`：索引与正文不一致时**拒绝**
  伪造坐标。
- `searchResultTextLocatorIsReadyForReaderNavigation`：偏移能真的切出命中词。

---

## 6. 阶段验收状态矩阵

| 任务 / 模块 | 状态 | 交付文件 | 验证依据 |
|---|:---:|---|---|
| **片段标记解析与 HTML 安全剥离** | **PASS** | `SearchHitProjector.kt` | 20 项单测 |
| **命中位置回归正文重新定位** | **PASS** | `SearchHitProjector.project` | 唯一/重复/前缀扩展用例 |
| **重复词上下文消歧** | **PASS** | `contextBefore/After` | 投影器 + 仓库各 1 项 |
| **结果携带 documentKey/revision/ranges** | **PASS** | `GlobalSearchResult.kt` | 10 项单测 |
| **仓库层真实接线** | **PASS** | `SearchRepositoryImpl.kt` | 6 项接线测试 |
| **点击结果精确跳转（六级恢复）** | **PASS** | `Navigation.kt`, `ReaderViewModel.goToSearchHit` | 编译 + 全量门禁 |
| **近似结果显式标注** | **PASS** | `SearchScreen.kt` + 9 语言资源 | 编译通过 |
| **书内搜索升级（可精确定位）** | **PASS** | `ReaderViewModel.searchInBook`/`goToSearchResult`, `Dialogs.kt` | `FtsSearchResult` 携带 `locatorOffset`/`locatorConfidence`；4 项单测 |
| **FTS 表增加 revision 列** | **NOT_RUN** | — | 当前 revision 取自 Book 记录；未在索引层记录，无法感知「索引建立后书被更新」 |
| **真机搜索验证** | **NOT_RUN** | — | 需真机导入书、搜索、点击、肉眼确认跳到命中处；**真机数据仍缺失（见 S5 报告第 0 节）** |
| **S8 目录与分卷 UI 联动** | **NOT_RUN** | — | 待实现 |
| **S9 分页裸像素** | **部分** | — | 滚动/选择/高亮/搜索已迁移 |
| **S10 / S11** | **NOT_RUN** | — | 待运行 |

---

## 7. 未验证项与已知限制

1. ~~书内搜索未升级~~ **已在本批完成**：`searchInBook` 复用 `SearchHitProjector`，
   `FtsSearchResult` 携带已验证的 `locatorOffset`，点击走 `goToSearchHit` 精确跳转；
   无法定位时标注近似并退回章首。
2. **revision 未进 FTS 索引**：当前 `revision` 取自 Book 记录。若书在索引建立后被重新导入，
   索引内容与 `activeRevision` 会不一致；此时投影会失败（返回不可定位），行为是**安全的**
   （不会跳错），但无法主动提示「索引需重建」。
3. **消歧的边界**：片段上下文被 `snippet(..., 30)` 截断，若重复词距离很近且上下文不足，
   会降级为 APPROXIMATE 并取首个候选。单测覆盖了该降级路径，但真实语料的命中率未测。
4. **性能**：每条结果都要读一次章节正文（`getChapterContent`）。当前实现未做批量/缓存，
   100 条结果意味着 100 次查询。**尚未压测**。
5. **真机未验证**：与 S6c 同样，需要真机导入带批注/可搜索内容并肉眼核对跳转。

---

## 8. 新会话接力入口点

1. **先处理真机数据**（S5 报告第 0 节），再做任何真机操作。
2. **FTS 索引记录 revision**（限制 2）：索引行加 revision 列，搜索时比对 `activeRevision`，
   不一致就提示重建。
3. **批量取正文**（限制 4）：为搜索结果做一次性批量加载，避免 N+1 查询。
4. **真机搜索验证**（限制 5）：导入书 → 搜索 → 点击 → 肉眼确认跳到命中处。
5. **S8 目录与分卷 UI 联动**（主计划第 7 节）。
6. **听书接入**（第 9 节表中最后一项）：`toggleTts` 仍用 `state.currentPosition` 起读，
   应按 `TextLocator` 对应的源偏移启动。
