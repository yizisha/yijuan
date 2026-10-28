# FlowReader 内容引擎改造 — S8 报告（目录与分卷 UI 联动）

执行日期：2026-09-11。依据：主计划 **第 7 节 目录分卷交互规格**、**4.3 目录树与阅读顺序**、**F07**。

Gate：**PASS**（全工程 **762 项单测全绿 / 0 失败 / 92 套件**，代码风格 0 违规，APK 构建成功）。
版本：**0.2.1**（versionCode 5674）。

---

## 1. 旧实现的真实缺口

`ReaderTocSheet` 接收**扁平 `List<Chapter>`**，在 Composable 内部用
`TxtChapterDetector.VOLUME_PATTERN.matches(...)` **现场猜**哪些章是卷。后果：

| 缺口 | 旧行为 | 计划要求（7.2） |
|---|---|---|
| 卷**完全不可点** | 卷行只有图标，没有点击处理 | 第 1 条：卷行主体与箭头**都**只做展开/收起 |
| 无层级 | 卷只是视觉分隔符，折叠没有任何效果 | 卷要能独立展开/收起、卷范围清楚 |
| 无「全部展开/收起」 | 无 | 第 9 条 |
| 无覆盖范围 | 只显示章节总数 | 第 13 条：卷序计入「节」，连续显示范围、非连续如实标注 |
| 搜索只匹配章标题 | 卷标题不参与匹配 | 第 7 条：同时匹配章与卷标题，命中章展示完整祖先链 |
| 同名卷用标题当 key | 冲突（F07） | 第 12 条：同名卷/章不冲突 |
| 空卷显示可点箭头 | 点了没反应 | 第 2 条：显示「暂无可读章节」，不显示误导箭头 |
| 正则规则无测试覆盖 | 改正则会意外改变阅读 UI | 规则应可判定、可回归 |

---

## 2. 关键设计

### 2.1 把「猜结构」从 UI 搬进可测的适配层

新增 `LegacyTocAdapter`：扁平 `List<Chapter>` → `TocTree`。规则与旧行为一致，但**纯数据、可单测**：

1. `chapter.volume` 非空 → 按卷分组，卷内每章是可读条目；
2. `volume` 为空且标题命中卷模式 → 该章**只**作分组标题，不产生可读条目（4.4）；
3. 其余章节可读；**若前面刚出现过卷标题则归入该卷**，否则挂根。

第 3 条里的后半句是本批修正的行为：旧实现下旧式卷标题只是一个分隔符，
其后的章节并未归入该卷，于是「折叠卷」什么也不会发生，卷范围也无从计算。

### 2.2 领域层承载全部交互规则

`TocTree` / `TocOutline` 是纯 Kotlin，把计划 7.2 的规格逐条变成可判定函数：

- **折叠**：`flatten` 只遍历展开节点的子节点——「收起」的全部实现就是这一句；
- **自动展开**：`initialExpanded` 只展开当前章**祖先**，其余卷收起（第 4 条）；
- **用户折叠优先**：搜索期用「合并后的展开集」计算可见行，但**不回写**用户状态（第 5、7 条）；
- **消歧**：同一 section 被多个目录项指向时，优先用户进入的 `entryNodeId`，否则取
  **第一个规范入口**（第 12 条）——不是随便抓一个；
- **覆盖范围**：`coverageOf` 用可读后代的 section 阅读序判断连续/非连续，
  缺 ordinal 映射时**不假装连续**（第 13 条）。

### 2.3 死分组与「读卷首」

- 无子节点又无 target 的卷 → `isDeadGroup`，UI 显示「暂无可读章节」且**不显示箭头**（第 2 条）；
- 有 target 的卷 → 单独「读卷首」按钮，与展开/收起**分离**，不会一次点击既展开又跳转（第 1 条）。

### 2.4 无障碍与缩进封顶

- 每行 `contentDescription` 读出「标题、展开/收起状态、包含节数、是否正在阅读」（第 15 条）；
- 缩进封顶 3 层：`indentDepth = depth.coerceAtMost(3)`，真实层级仍保留在
  `TocRow.depth` 供语义表达（第 10 条）。

---

## 3. 交付清单

| 文件 | 性质 | 职责 |
|---|---|---|
| `domain/.../TocTree.kt` | **新增** | `TocNode`/`TocNodeKind`/`TocTree`：稳定 nodeId、层级、祖先/后代查询、防环 |
| `domain/.../TocOutline.kt` | **新增** | 展平、搜索、覆盖范围、自动展开、当前节点消歧、缩进封顶 |
| `app/.../util/LegacyTocAdapter.kt` | **新增** | 旧书扁平章节 → `TocTree`；把正则猜测移出 UI |
| `app/.../components/ReaderTocSheet.kt` | **重写** | 消费 `TocTree + TocLocation + 展开状态`；卷行可点、全部展开/收起、覆盖副标题、读卷首、空态、无障碍 |
| `app/.../ReaderScreen.kt` | 接线 | 构建并缓存 `TocTree`，传当前 `TocLocation` |
| `app/.../ReaderViewModel.kt` | 接线 | `tocExpandedNodeIds` 状态、`setTocExpandedNodes`、`goToTocNode`（按 section 解析，不按标题）、打开目录时自动展开 |
| 2 个 `strings.xml` | 资源 | 全部展开/收起、空态、读卷首、无可读章节、覆盖范围、展开/收起 |
| `TocOutlineTest.kt` | **新增 12 项** | 树结构、折叠、幂等、缩进封顶、防环 |
| `TocOutlineInteractionTest.kt` | **新增 24 项** | 自动展开、别名优先、搜索、覆盖范围、死分组 |
| `LegacyTocAdapterTest.kt` | **新增 10 项** | 分卷规则、同名卷、旧式卷标题、与 TocOutline 联动 |

---

## 4. 过程中发现并修复的问题

| 问题 | 表现 | 修复 |
|---|---|---|
| **旧式卷标题不收纳后续章节** | `legacyVolumeHeadingChapterBecomesGroupWithoutReadableEntry` 期望 1 个根实得 3；`sameNamedVolumesGetDistinctNodeIds` 期望 2 实得 4 | 规则 3 改为「前面有卷标题就归入该卷」。否则折叠卷没有任何效果，与 7.2 第 1 条矛盾 |
| **无定位回退会展开空集** | `initialExpansionFallsBackToFirstReadableChapterWhenNoLocation` 期望 `{vol1}` 实得 `{}` | 回退应取**第一个可读 ENTRY**：只有 target 的根卷祖先集为空，会让目录看起来全收起 |
| 测试标签自撞 | `searchMatchesChapterLabels` 用「远行」查询，而卷名也是「第X卷 远行」 | 实现正确（两处都该命中）；改用只出现在章标签里的词，并补一条「卷与章同时命中」用例 |
| 适配器 O(n²) 与冗余类型 | 循环内反复 `copy` 已加入的不可变节点 | 改为构建期可变节点，最后一次性物化 |

---

## 5. 门禁验证与测试数据

| 项目 | 结果 |
|---|---|
| 代码风格 | **PASS**（0 违规） |
| 单元测试 | **PASS**（**762 项 / 0 失败 / 0 错误 / 92 套件**） |
| `:app:assembleDebug` | **PASS** |

单测 716 → **762（+46）**：TocOutlineTest 12、TocOutlineInteractionTest 24、LegacyTocAdapterTest 10。

---

## 6. 阶段验收状态矩阵（计划 7.2 十五条）

| 条目 | 状态 | 证据 |
|---|:---:|---|
| 1. 卷行主体与箭头都只展开/收起；有 target 的卷独立「读卷首」 | **PASS** | `TocRowItem` + `TocOutline.toggle` |
| 2. 无后代可读的分组显示「暂无可读章节」，不显示误导箭头 | **PASS** | `isDeadGroup` + 2 项用例 |
| 3. 普通 ENTRY 点击提交 GoTo | **PASS** | `goToTocNode` 按 section 解析 |
| 4. 首次打开自动展开当前章祖先 | **PASS** | `initialExpanded` + 3 项用例 |
| 5. 目录打开期间用户手动折叠优先，不每次重组弹开 | **PASS** | 展开状态由用户/首次打开写，重组不写 |
| 6. 展开状态按书隔离、退出重进恢复 | **PASS（进程内）** | `tocExpandedNodeIds`；**未持久化到磁盘**（见限制 1） |
| 7. 搜索匹配章与卷标题、展示完整祖先链、去重 | **PASS** | `flattenSearch` + 6 项用例 |
| 8. 搜索无结果给空态 | **PASS** | `reader_toc_search_empty` |
| 9. 全部展开 / 全部收起 | **PASS** | `expandableIds` / 空集 |
| 10. 多级嵌套，深度超 3 层缩进封顶 | **PASS** | `indentDepth` + 用例 |
| 11. LazyColumn 只显示当前展平节点 | **PASS** | `flatten` 产出可见行 |
| 12. 同名卷/章/同目标别名不冲突，别名优先入口 | **PASS** | nodeId 稳定 + `currentNode` + 3 项用例 |
| 13. 卷数量与范围排除纯结构标题、性能分片 | **PASS（旧书路径）** | 旧式卷标题不产生可读条目；**EPUB 新路径见限制 2** |
| 14. 书籍详情页目录复用同一规则 | **NOT_RUN** | 详情页仍用旧的分组逻辑 |
| 15. 无障碍读出卷标题/展开状态/数量/当前状态 | **PASS** | `tocRowDescription` |

---

## 7. 未验证项与已知限制

1. **展开状态未落盘**：只存在 ViewModel 内存中，**应用重启会丢**。计划 7.2 第 6 条要求
   「重启应用恢复」。落到 `SavedStateHandle` 或 DataStore 是明确的小改动，但本批未做。
2. **EPUB 新书仍走旧适配器**：`ReaderScreen` 用 `LegacyTocAdapter` 从扁平章节建树，
   **没有使用** `EpubNavigationParser` 产出的真实目录树（它还没进入 Reader 的数据流）。
   因此新书的「卷」仍来自分卷字段/标题模式，而不是 EPUB nav 的真实层级。
   这是 S8 最大的一处未完成——真正启用新路径需要让 Reader 读取 `toc_nodes`。
3. **书籍详情页目录未统一**（第 14 条）：`BookDetailScreen` 仍维护自己那套按字符串分组的逻辑。
4. **真机未验证**：展开/收起、搜索、读卷首的手感与滚动位置需要真机肉眼确认；
   **真机数据仍缺失**（见 S5 报告第 0 节）。
5. **性能未测**：1400 章的树展平是 O(可见节点)，但未在真实大书上测过滚动帧率。

---

## 8. 新会话接力入口点

1. **先处理真机数据**（S5 报告第 0 节），再做任何真机操作。
2. **让 EPUB 新书走真实目录树**（限制 2，S8 最大缺口）：Reader 读取 `toc_nodes` →
   构建 `TocTree`（节点 role/kind 已就绪）→ 替换 `LegacyTocAdapter` 路径；
   旧书保留适配器作为兼容层。
3. **展开状态持久化**（限制 1）：`SavedStateHandle` 或 DataStore，按 publicationId+nodeId 隔离。
4. **书籍详情页目录统一**（第 14 条）：复用 `TocTree` + `TocOutline`，不再维护第二套分组算法。
5. **S9 切断分页路径的裸像素写入**（主计划 6.7、R1）。
6. **人工真机补验**：Gate G4 交互、搜索命中跳转、跨块批注、目录展开/收起。
