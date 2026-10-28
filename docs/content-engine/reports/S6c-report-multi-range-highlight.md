# FlowReader 内容引擎改造 — S6c 报告（多区间高亮渲染：只高亮被选中的文字）

执行日期：2026-09-11。依据：主计划 **第 9 节 书签、批注接入**、**3.3 显示映射**、**INV05**。

Gate：**PASS**（全工程 **676 项单测全绿 / 0 失败 / 85 套件**，代码风格 0 违规，APK 构建成功）。

---

## 1. 这一步修的是什么

S6b 让批注**数据层**支持多段不相连的源范围，但**渲染层**仍是单区间：

1. `buildParagraphContent` 只取每条批注的 `startPosition/endPosition`，用单游标 `lastEnd` 推进，
   于是同段内 `[5,10)` 与 `[20,25)` 被渲染成一个 `[5,25)` 的**连续高亮**，中间的
   `[10,20)` 也被点亮——用户会看到从未选中过的文字被高亮。
2. 段落筛选层（S6b 引入的 `clipAnnotationToParagraph`）为了适配这个限制，只能把多区间
   **折叠成首尾**，等于在上游就把断点信息丢掉了。

本批把这条链路打通到渲染层，让"只高亮被选中的文字"在**所有**场景成立。

---

## 2. 顺带发现的真实缺陷：高亮区内的 markdown 标记泄漏

写测试时立刻暴露了一个**既有缺陷**（旧实现同样存在，不是本次引入）：

```text
段落源文本: 前面**加粗**后面还有很多文字内容
高亮区间:   raw [2,6) = "**加粗"

旧渲染循环:
  cursor=0  -> appendFormatted("前面")           正常剥离
  然后       -> append(displayParagraph[2,6)) = "**加粗"   ← 直接 append，**未剥离标记**
  cursor=6  -> appendFormatted("后面...")         正常剥离

结果: 显示文本里出现 "**" 字面量，且 rawOffsets 与显示长度不再对齐
```

根因：高亮分支用 `append(源切片)` 直接输出原文，绕过了 markdown 剥离与 `rawOffsets` 记录。
**这使得"被高亮覆盖的区域"内的标记会泄漏到屏幕上，并让该段之后的选择映射整体错位。**

---

## 3. 修复设计

### 3.1 先建文本、后叠样式（消除重复 append）

旧结构把"写文本"和"画高亮"耦合在一个循环里（高亮分支自己 append 字符）。
这正是重叠批注会重复输出文字的来源。新结构分两步：

1. **一次性写完整段正文**：`appendFormatted(displayParagraph, paragraphStart)` 负责
   剥离标记、记录 `rawOffsets`；
2. **高亮以样式叠加**：用 `builder.addStyle(...)` 在原位加背景色，**不再触碰文本内容**。

这一步从根本上消除了"重叠批注重复 append 同一段字符导致文本错乱 / rawOffsets 错位"的缺陷类别。

### 3.2 源坐标 → 显示坐标必须经 rawOffsets 反查

高亮区间是**源坐标**，而 markdown 剥离让显示坐标**短于**源坐标。因此不能用减法：

```text
源:   前 面 *  *  加 粗 *  *  后 ...
       0 1 2  3  4  5 6  7  8
显示: 前 面 加 粗 后 ...
       0 1 2  3  4
```

新辅助 `sourceToDisplayRange`：找第一个 `rawOffsets[i] >= sourceStart` 作为显示起点、
最后一个 `rawOffsets[i] < sourceEnd` +1 作为终点。**被剥离的标记字符在 rawOffsets 里
本就没有条目**（它们从未被 append），因此天然被跳过；人工缩进（`rawOffsets = -1`）同样跳过。

### 3.3 多区间裁剪留在渲染层

- `Annotation.highlightSpansFor(paraStart, paraEnd)`：把一条批注**逐区间**裁剪到本段，
  产出多个 [HighlightSpan]；无 `rangeLocator` 的旧数据退回单区间。
- `Annotation.intersectsParagraph(...)`：渲染前的**廉价筛选**，不再要求批注完全落在段内。
- 删除了 S6b 的 `clipAnnotationToParagraph`（它会折叠断点）与对应的 8 项测试，
  由 `MultiRangeHighlightTest` 13 项替代。

---

## 4. 交付清单

| 文件 | 性质 | 职责 |
|---|---|---|
| `app/.../components/ReaderTextMapping.kt` | **重构** | 新增 `HighlightSpan`、`highlightSpansFor`、`intersectsParagraph`、`sourceToDisplayRange`；渲染改为「先建文本、后叠样式」 |
| `app/.../components/ReaderContent.kt` | 简化 | 筛选改用 `intersectsParagraph`；删除会折叠断点的 `clipAnnotationToParagraph` |
| `app/src/test/.../MultiRangeHighlightTest.kt` | **新增 13 项** | 相交筛选、逐区间裁剪、实际渲染 spanStyles、重叠/相接合并、markdown 协同、选择回环 |
| （删除）`AnnotationParagraphClippingTest.kt` | 移除 8 项 | 其目标函数已不存在；行为由新测试覆盖且更严格 |

---

## 5. 门禁验证与测试数据

```powershell
pwsh -NoProfile -File R:\tools\verify.ps1
```

| 项目 | 结果 |
|---|---|
| 代码风格 | **PASS**（0 违规） |
| 单元测试 | **PASS**（**676 项 / 0 失败 / 0 错误 / 85 套件**） |
| `:app:assembleDebug` | **PASS**（29,647,922 字节） |

单测 671 → **676**（+13 新增，−8 移除旧裁剪测试，净 +5）。

新增测试里最关键的三项：

- `renderedParagraphHasOneStyleSpanPerDisjointRange`：同段两个不相连区间 → **恰好 2 个**
  `spanStyles`，且各覆盖 `[5,10)` 与 `[20,25)`。这是"中间不点亮"的直接证据。
- `overlappingAnnotationsMergeIntoOneContinuousSpanWithoutDuplicatingText`：重叠批注合并为
  一段且文本零重复。
- `markdownAndMultiRangeHighlightCoexistWithoutOffsetDrift`：**正是它抓到了标记泄漏缺陷**。

---

## 6. 阶段验收状态矩阵

| 任务 / 模块 | 状态 | 交付文件 | 验证依据 |
|---|:---:|---|---|
| **多区间高亮渲染（同段多断点各自着色）** | **PASS** | `ReaderTextMapping.kt` | 13 项单测，含 spanStyles 精确断言 |
| **源→显示坐标换算（markdown 剥离协同）** | **PASS** | `sourceToDisplayRange` | markdown 协同用例 + 选择回环用例 |
| **重叠/相接批注合并且文本零重复** | **PASS** | 渲染循环重构 | 2 项专项用例 |
| **跨块批注在每段各自裁剪** | **PASS** | `highlightSpansFor` | 跨段裁剪用例 |
| **markdown 标记泄漏修复** | **PASS** | 先建文本后叠样式 | markdown 协同用例（回归守护） |
| **真机视觉验证（肉眼确认高亮范围）** | **NOT_RUN** | — | 需真机导入带批注的书并肉眼核对；**真机数据仍缺失（见 S5 报告第 0 节）** |
| **S7 全局搜索升级** | **NOT_RUN** | — | 待实现 |
| **S8 目录与分卷 UI 联动** | **NOT_RUN** | — | 待实现 |
| **S9 分页裸像素** | **部分** | — | 滚动/选择/高亮已迁移；分页 `updatePosition` 仍写像素 |
| **S10 / S11** | **NOT_RUN** | — | 待运行 |

---

## 7. 未验证项与已知限制

1. **真机视觉未验证**：单测断言了 `AnnotatedString.spanStyles` 的精确区间，但"屏幕上看起来
   是否正确"需要肉眼确认。真机数据当前缺失，必须先按 S5 报告第 0 节建立备份。
2. **样式嵌套的表现**：高亮与 markdown 粗体/斜体现在是**并列**的 spanStyle（背景色 + 字重），
   而非嵌套。Compose 会正确合成，但同一字符若被多条不同颜色批注覆盖，
   **后加入的样式生效**（当前合并策略保留先出现者的颜色）。这属于产品取舍，
   未做"多色叠加"的视觉验证。
3. **同段多断点的复制行为**：跨块复制取自装配器文本（与批注范围一致，不含被跳过内容）；
   同段内多断点复制仍取**显示选区**，因此会包含中间的未选中文字——两者语义不同，
   需要 UI 说明或统一。
4. **性能**：`sourceToDisplayRange` 对每个高亮片段线性扫描 `rawOffsets`（段落级，通常 <500 字符），
   未做大数据量压测。若将来出现超长段落 + 大量批注，需改为二分。

---

## 8. 新会话接力入口点

至此，主计划第 9 节表格中「普通书签 / 选中文字建书签 / 批注」三项**代码链路已全部打通**
（真机视觉验证待做）。剩余：

1. **先处理真机数据**（S5 报告第 0 节），再做任何真机操作。
2. **S7 全局搜索升级**：`SearchResult` 携带 revision + documentKey + UTF-16 源范围；
   用命中文本投影验证，不得把 FTS 高亮索引直接当 UTF-16。
3. **S8 目录与分卷 UI 联动**（主计划第 7 节）。
4. **S9**：切断分页路径的裸像素写入，让分页与滚动共用同一 `TextLocator` 权威。
5. **听书接入**（第 9 节表中剩余项）：当前 `toggleTts` 仍用 `state.currentPosition` 起读，
   应按 `TextLocator` 对应的源偏移启动。
