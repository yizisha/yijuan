# FlowReader 内容引擎改造 — S6 剩余项报告（跨块选择 → 多区间批注打通）

执行日期：2026-09-11。依据：主计划 **第 9 节 书签、批注、搜索、听书的接入**、**3.3 显示映射**、**INV03 / INV04**。

Gate：**PASS**（全工程 **671 项单测全绿 / 0 失败 / 85 套件**，代码风格 0 违规，APK 构建成功）。

---

## 1. 这一步解决的真实问题

S6 上一批已经把模型层做好（`RangeLocator` 支持多区间），但**调用链仍是单区间**：
批注的入口是 `addAnnotation(text, start: Int, end: Int)`，且每个段落各自持有选择状态
（`ReaderParagraph` 内部 `remember(paragraphStart)`）。

后果是**跨块批注在真实交互上不成立**，具体两条：

1. **选择被物理限制在单个段落内**：用户的拖动一旦离开本段，`getOffsetForPosition` 只会返回
   本段末尾，无法选到下一段的文字。所以「跨块选择」这件事用户根本做不到。
2. **单区间表达不了断点**：即使强行给出首尾两点，中间被跳过的内容也会被一并纳入范围；
   反过来，为了让范围连续而丢掉断点，就会把同一段文字输出两次。

本批把这两条都打通，并用**生产代码路径**的端到端测试证明「不重复、不吞字」。

---

## 2. 交付清单

| 文件 | 性质 | 职责 |
|---|---|---|
| `domain/.../MultiBlockSelectionAssembler.kt` | **新增 131 行** | 把「若干块各自被选中的显示区间」装配为**一组有序、已合并的 `SourceRange`** + 自洽文本 |
| `app/.../components/CrossBlockSelectionModel.kt` | **新增 145 行** | 跨块选择状态机：锚点/焦点两个跨块坐标，按阅读顺序投影每块的选中区间，**方向无关** |
| `app/.../components/MultiBlockSelection.kt` | **新增** | UI → ViewModel 的提交载体：已投影的源范围 + 文本 + 章节号 |
| `app/.../components/ScrollViewportProjection.kt` | 扩展 | `ScrollViewportRegistry` 增加窗口坐标**命中测试**（`hitTest`）、`mappingFor`、`displayLengthFor` |
| `app/.../components/ReaderContent.kt` | 改造 | 选择状态**提升**到 ReaderContent；手柄拖动越段时用注册表命中其他块；提交多区间 |
| `app/.../ReaderViewModel.kt` | 接线 | `addAnnotationRanges` / `addBookmarkRanges` |
| `app/.../ReaderScreen.kt` | 接线 | 把多区间回调接到 ViewModel |
| `domain/src/test/.../MultiBlockSelectionAssemblerTest.kt` | **新增 14 项** | 装配、合并、synthetic 排除、跨文档、失败路径、不重复 |
| `app/src/test/.../CrossBlockSelectionModelTest.kt` | **新增 15 项** | 跨段、反向、中间块整选、夹紧、注销、零长块 |
| `app/src/test/.../CrossBlockAnnotationIntegrationTest.kt` | **新增 4 项** | **生产路径端到端**：真实 `buildParagraphContent` + 真实选择模型 + 真实装配器 |

---

## 3. 关键设计决策

### 3.1 为什么选择状态必须"提升"

旧的 `remember(paragraphStart)` 把选择绑死在段落上。要支持跨块，必须让选择状态活在
**段落之外**（`ReaderContent`），段落只从共享模型里**读取自己那一段区间**。
这样中间段落即使没被手指点过，也会因为落在锚点与焦点之间而被正确包含。

### 3.2 跨块拖动怎么实现

难点：Compose 的 `pointerInput` 只把手势发给**收到 DOWN 的那个段落**，拖到别的段落时
该段落收不到事件。做法是在段落的手柄拖动里判断指针是否已**离开本段边界**，
若是则用 `ScrollViewportRegistry.hitTest(窗口坐标)` 反查落在哪个块与哪个显示偏移，
再扩展模型的焦点。命中测试带垂直距离兜底，拖到段间空隙时归属最近块，不会断档。

### 3.3 文本与范围必须严格自洽（本批发现的真实缺陷）

装配器最初用 `joinToString("\n")` 拼接多段文本。测试立刻暴露问题：

```text
ranges = [2,10), [12,19), [21,23)   范围长度之和 = 17
text   = "..."                       文本长度 = 19   ← 多出 2 个字符
```

多出的正是 `join` 自己插入的分隔符。这违反了我自己声明的不变量
`text.length == ranges.sumOf { lengthUtf16 }`，而这正是 F14 记录过的缺陷类型
（位置与文本各算各的）。**修复**：只取各范围自身文本并直接拼接，不插入任何字符。

### 3.4 为什么还要合并"空白间隙"

根因：阅读器按 `"\n\n"` 切分段落，每段的 `DisplayMapping` 只描述**段内**偏移，
所以「从第 1 段拖到第 2 段」会产出 `[.., 段1末)` 与 `[段2首, ..)` 两条，
中间恰好是段落分隔符。源文本上这是**连续**的一段，用户也确实从上一段拖到了下一段。

因此新增 `mergeWhitespaceGaps`：同一文档内，**仅当两区间之间的源文本全部是空白**时才合并。
**保守边界**——间隔里只要有一个可见字符，就说明用户跳过了真实内容，必须保持分离。
这条由 `syntheticCharacterBetweenDisjointSourceRunsKeepsRangesSeparate` 等用例守护。

---

## 4. 门禁验证与测试数据

```powershell
pwsh -NoProfile -File R:\tools\verify.ps1
```

| 项目 | 结果 |
|---|---|
| 代码风格 | **PASS**（0 违规） |
| 单元测试 | **PASS**（**671 项 / 0 失败 / 0 错误 / 85 套件**） |
| `:app:assembleDebug` | **PASS**（29,647,922 字节） |

单测 630 → **671（+41）**：

```text
MultiBlockSelectionAssemblerTest           14 项（domain）
CrossBlockSelectionModelTest               15 项（app）
CrossBlockAnnotationIntegrationTest         4 项（app，生产路径端到端）
AnnotationParagraphClippingTest             8 项（app，跨块高亮渲染）
```

### 过程中发现并修复的问题

| 问题 | 表现 | 修复 |
|---|---|---|
| **文本与范围不自洽** | 集成测试 `expected:<17> but was:<19>` | `joinToString("\n")` 凭空插入分隔符；改为直接拼接（见 3.3） |
| 跨段落范围被判为"有空洞" | 范围 `[2,10)`/`[12,19)` 实际只隔段落换行 | 新增 `mergeWhitespaceGaps`，仅合并纯空白间隙（见 3.4） |
| 反向选择端点取错 | 我的测试期望把「锚点/焦点」误当「区间边界」 | 实现正确（按扁平文档区间语义）；改用显式 head/tail 表达并修正测试 |
| 测试夹具越界 | `assemble` 返回 null 导致 NPE | 夹具源串长度算错（2+6+2=10，起点应为 8）；修正夹具 |
| ktlint `filename` 违规阻塞自动修复 | `ktlintFormat` 整体 FAILED | 文件重命名为 `MultiBlockSelectionAssembler.kt` 匹配对象名，其余违规随格式化修完 |

---

## 5. 阶段验收状态矩阵

| 任务 / 模块 | 状态 | 交付文件 | 验证依据 |
|---|:---:|---|---|
| **跨块选择纯逻辑（方向无关、中间块整选）** | **PASS** | `CrossBlockSelectionModel.kt` | CrossBlockSelectionModelTest 15 项 |
| **多区间装配（合并/断点/synthetic 排除）** | **PASS** | `MultiBlockSelectionAssembler.kt` | MultiBlockSelectionAssemblerTest 14 项 |
| **跨块命中测试（窗口坐标 → 块+偏移）** | **PASS** | `ScrollViewportProjection.kt` | 集成测试 + 编译 |
| **选择状态提升与手柄越段拖动** | **PASS** | `ReaderContent.kt` | 集成测试 4 项 + 全量门禁 |
| **多区间落库（批注 + 书签）** | **PASS** | `ReaderViewModel.kt`, `ReaderScreen.kt` | 编译 + 663 项全绿 |
| **文本与范围自洽（不重复、不吞字）** | **PASS** | 装配器 + 集成测试 | `text.length == Σ range.length` 断言逐例守护 |
| **跨块批注高亮渲染** | **PASS（跨块）** | `ReaderContent.kt` (`clipAnnotationToParagraph`) | AnnotationParagraphClippingTest 8 项；同段多断点仍受限（见限制 6） |
| **真机交互验证（手指跨段拖选）** | **NOT_RUN** | — | 需真机手动拖选跨段并保存批注；**真机数据仍缺失（见 S5 报告第 0 节），必须先建立备份** |
| **S7 全局搜索升级** | **NOT_RUN** | — | 待实现 |
| **S8 目录与分卷 UI 联动** | **NOT_RUN** | — | 待实现 |
| **S9 分页路径裸像素** | **部分** | — | 滚动/选择路径已迁移；分页 `updatePosition` 仍写像素 |
| **S10 / S11** | **NOT_RUN** | — | 待运行 |

---

## 6. 未验证项与已知限制（不得视为已通过）

1. **真机手指交互未验证**：单测覆盖了模型、装配器与生产路径的端到端装配，但
   「人在真机上从一个段落拖到另一个段落并成功建批注」尚未实测。真机数据当前缺失，
   必须先按 S5 报告第 0 节建立备份再操作。
2. **选择模型只表达"一段连续范围"**：一次手势产生一个锚点—焦点对。
   用户「先选一段、再独立选另一段」这种**不连续多选**需要多次手势累积，
   当前 UI 未提供该入口（`RangeLocator` 与装配器已支持多段输入，只差 UI 累积）。
3. ~~高亮渲染仍按单区间~~ **已在本批修复**：旧筛选要求批注完全落在段内，跨段批注会
   在任何一段都匹配不上、高亮直接消失。现改为 `clipAnnotationToParagraph` 按**相交**裁剪，
   由 `AnnotationParagraphClippingTest` 8 项守护。**但裁剪后仍以首尾区间表达**，
   同一段内若有多段不相连选区，渲染会把中间未选中的部分也点亮（见限制 6）。
4. **复制使用装配文本**：跨块复制的文本由范围拼接而来，不再包含被跳过的内容——
   这是**有意的**（与批注范围一致），但与"复制所见"的直觉可能有差异，需 UI 说明。
5. **书签的 `legacyUnit` 标为 `SCROLL_PX`**：因为位置取自源偏移，标注为兼容单位；
   权威坐标始终是 `locator`。
6. **同段内多段不相连选区的渲染**：`clipAnnotationToParagraph` 用「相交区间的最小起点/最大终点」
   表达裁剪结果。若同一段里选中 `[5,10)` 与 `[20,25)`，渲染会把 `[5,25)` 整段点亮，
   中间的 `[10,20)` 也会被高亮。**数据层是正确的**（`rangeLocator` 保存两段独立区间），
   只是 `ParagraphContent` 的高亮 API 目前只接受单一范围。要彻底解决需让
   `buildParagraphContent` 接受多个高亮范围。

---

## 7. 新会话接力入口点

1. **先处理真机数据**：按 S5 报告第 0 节建立备份脚本，再做任何真机操作。
2. **让高亮 API 支持多范围**（见限制 6）：`buildParagraphContent` 现在只接受单一高亮范围，
   因此同段内多段不相连选区会被渲染成一个连续高亮。改为接受 `List<SourceRange>`
   即可让"只高亮被选中的文字"在**所有**场景成立（跨块场景已解决）。
3. **不连续多选入口**（见限制 2）：为选择模型补「追加选区」能力。
4. **S7 全局搜索升级**：`SearchResult` 携带 revision + documentKey + UTF-16 范围。
5. **S9**：切断分页路径的裸像素写入。
