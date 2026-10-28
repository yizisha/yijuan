# FlowReader 内容引擎改造 — S8b 报告（EPUB 新书走真实目录树）

执行日期：2026-09-11。依据：主计划 **4.3 目录树与阅读顺序**、**7.1 展示模型**、**7.2 第 13 条**、**F05 / F06 / F07**。

Gate：**PASS**（全工程 **790 项单测全绿 / 0 失败 / 95 套件**，代码风格 0 违规，APK 构建成功）。
版本：**0.2.2**（versionCode 5675）。

---

## 1. 这一步修的是什么

S8 把目录 UI 改成了 `TocTree` 驱动，但 **`ReaderScreen` 仍在用 `LegacyTocAdapter` 从扁平章节建树**。
也就是说：**真实目录层级根本没有进入阅读器**。

后果（对**新导入**的 EPUB 同样成立）：

| 问题 | 具体表现 |
|---|---|
| 层级丢失 | `EpubNavigationParser` 已经正确解析出三层树（F05/F06 都在 S3 修过），但阅读器看不见它 |
| 无链接卷头丢失 | EPUB nav 里的 `<span>` 卷头（真实存在、且是卷范围的唯一依据）不在章节列表里，于是目录里根本没有这一层 |
| 多锚点别名丢失 | 同一 XHTML 的多个 `#fragment` 目标是不同目录项（F05 的修复点），但章节投影只能给出一个标题 |
| 卷归属靠猜 | 卷来自 `chapter.volume` 字段或标题正则，而不是出版物声明的结构 |
| 计划 7.2 第 13 条不成立 | 「卷数量与范围排除纯结构标题、性能分片」对新书无法真正判定 |

本质：**S8 只是把旧书的「猜结构」做得可测可控；目录仍然不反映出版物的真实结构。**

---

## 2. 关键设计

### 2.1 数据通路：toc_nodes + reading_sections

```text
EPUB nav (EpubNavigationParser.Node 树)
   ↓  flattenToc（S3 已写）        nodeId / parentId / siblingOrder / kind / role / targetJson
toc_nodes 表
   ↓  TocRepositoryImpl（本批）     entity → TocNodeRow
   ↓  TocTreeBuilder（本批）        扁平行 → TocTree（还原层级）
   ↓  TocOutline（S8）              折叠 / 搜索 / 覆盖范围
ReaderTocSheet（S8 重写的 UI）
```

**为什么必须同时读 `reading_sections`**：`targetJson.sectionId` 存的是**清单 sectionId**
（由结构锚点哈希而来），而阅读器的工作单位是「章节下标 = section.ordinal」。
两者只能通过 `reading_sections` 对上；只读 `toc_nodes` 无法把目录项跳到正确的章。
这是本批最容易踩空的一处——不读 section 表，目录能显示但点了跳错章。

### 2.2 定位坐标必须「按树本身」选择

真实树用清单 sectionId，兼容树用 `section_<ordinal>`。UI 与 ViewModel 都按
**当前这棵树自己的映射**反查当前章，不混用。混用会让「当前章高亮」永远匹配不上。

### 2.3 坏数据策略（全部不静默丢弃）

`TocTreeBuilder` 处理四类边界，全部有测试：

| 情况 | 策略 | 理由 |
|---|---|---|
| 孤儿（parentId 不存在） | **提升为根** + 诊断 | 直接丢弃会让整段目录凭空消失；提升后子树仍可见 |
| 成环 | 环上节点提升为根 + 诊断 | 必须有确定结果且不死循环 |
| 重复 nodeId | 保留第一个 + 诊断 | 同一 id 无法同时挂两处 |
| 未知 kind | 按 ENTRY 处理 | 不让异常标签把章节变得点不开 |
| 坏 targetJson | 该节点退化为不可读分组 | 一条坏记录不影响其它条目，更不能让整本书打不开目录 |

`TocTargetJson` 的解析**永不抛异常**，全部失败路径返回 null。

---

## 3. 交付清单

| 文件 | 性质 | 职责 |
|---|---|---|
| `domain/.../TocTreeBuilder.kt` | **新增** | 扁平行 → `TocTree`；孤儿/环/重复/未知 kind 的处理与诊断 |
| `domain/.../TocTargetJson.kt` | **新增** | `targetJson` 字段解析；放 domain 是为了复用 internal 的 `MiniJson` 并可单测 |
| `domain/.../repository/TocRepository.kt` | **新增** | 读取接口；无节点返回 null 表示「旧书，请回退」 |
| `app/.../data/repository/TocRepositoryImpl.kt` | **新增** | `toc_nodes` + `reading_sections` → `TocTree` |
| `app/.../di/AppModule.kt` | 接线 | `@Binds TocRepositoryImpl` |
| `app/.../ReaderViewModel.kt` | 接线 | 加载真实树入 UI 状态；`goToTocNode` 经 `sectionOrdinalById` 解析；自动展开用该树的坐标 |
| `app/.../ReaderScreen.kt` | 接线 | **优先真实树**，无节点才回退兼容适配器 |
| `TocTreeBuilderTest.kt` | **新增 13 项** | 层级还原、排序、卷语义、孤儿/环/重复/未知 kind、覆盖范围联动 |
| `TocTargetJsonTest.kt` | **新增 8 项** | 字段解析、JSON null、缺字段、坏 JSON、Unicode、转义 |
| `TocRepositoryImplTest.kt` | **新增 7 项** | 无节点返回 null、层级还原、section 映射、坏 JSON 降级、无链接卷头、卷首+子节点 |

---

## 4. 过程中发现并修复的问题

| 问题 | 表现 | 修复 |
|---|---|---|
| **target 与章节下标坐标系不同** | 设计阶段识别 | 必须同时读 `reading_sections` 建 ordinal 映射；否则目录能显示但跳错章 |
| 真实树/兼容树坐标混用 | 设计阶段识别 | `currentSectionIdFor` 按树本身反查，两处（UI + ViewModel）统一 |
| 重复具名参数 | 编译错误（`tocTree` 传了两次） | 移除重复项 |
| 测试夹具里 raw string 的转义写错 | `TocTargetJsonTest` 转义用例编译失败 / 断言失败 | 改用字符码（34/92）拼 JSON，避免测试自身的转义歧义 |

（另有一次我自己的批量编辑因模板字符串里的反引号提前终止而整批未生效——已改用不含反引号的写法。）

---

## 5. 门禁验证与测试数据

| 项目 | 结果 |
|---|---|
| 代码风格 | **PASS**（0 违规） |
| 单元测试 | **PASS**（**790 项 / 0 失败 / 0 错误 / 95 套件**） |
| `:app:assembleDebug` | **PASS**（29,262,102 字节） |

单测 762 → **790（+28）**。

---

## 6. 阶段验收状态矩阵

| 任务 / 模块 | 状态 | 证据 |
|---|:---:|---|
| **真实目录树读取（toc_nodes + reading_sections）** | **PASS** | `TocRepositoryImplTest` 7 项 |
| **扁平行 → 树还原（层级、顺序、卷语义）** | **PASS** | `TocTreeBuilderTest` 13 项 |
| **section 映射（目录项能跳到正确的章）** | **PASS** | `sectionOrdinalsAreMappedSoTocCanJumpToTheRightChapter` |
| **无链接卷头保留** | **PASS** | builder + repository 各 1 项 |
| **坏数据不影响整棵目录** | **PASS** | 孤儿/环/重复/未知 kind/坏 JSON 共 6 项 |
| **旧书回退兼容适配器** | **PASS** | 无节点返回 null |
| **UI 优先真实树** | **PASS** | `ReaderScreen` + 编译通过 |
| **计划 7.2 第 13 条（新书排除纯结构标题）** | **PASS（数据层）** | 真实树的 GROUP/ENTRY 与 role 来自出版物声明，不再靠正则 |
| **目录展开状态跨重启持久化** | **NOT_RUN** | 仍只在 ViewModel 内存（见限制 1） |
| **书籍详情页目录统一** | **NOT_RUN** | 详情页仍用自己那套分组 |
| **真机验证（真实 EPUB 目录）** | **NOT_RUN** | 需真机导入 EPUB 并肉眼核对层级；**真机数据仍缺失** |

---

## 7. 未验证项与已知限制

1. **展开状态仍未跨重启**：只在 ViewModel 内存；换书/换 revision 时会过滤掉已不存在的
   nodeId（本批已加），但进程被杀后仍会回到「自动展开当前章祖先」的初始态。
2. **真机未验证**：真实 EPUB 的目录层级、无链接卷头、多锚点别名需要在真机上导入后肉眼核对。
   单测覆盖了数据层与纯逻辑，**不能替代**「这本书的目录看起来对不对」。
3. **性能未测**：真实大书（数千目录节点）的建树与展平未压测；建树是 O(n log n)（排序），
   展平是 O(可见节点)。
4. **旧书未自动重解析**：旧书仍需重新导入才进入真实树路径；`content_migration_jobs` 的
   逐书重解析任务尚未实现。
5. **详情页目录未统一**（计划 7.2 第 14 条）：`BookDetailScreen` 仍维护第二套分组算法。

---

## 8. 新会话接力入口点

1. **先处理真机数据**（S5 报告第 0 节），再做任何真机操作。
2. **真机验证真实目录**（限制 2）：导入一本多卷 EPUB，核对层级、卷范围、无链接卷头、别名。
3. **展开状态持久化**（限制 1）：`SavedStateHandle` 或 DataStore，按 publicationId + nodeId 隔离。
4. **书籍详情页目录统一**（限制 5）：复用 `TocTree` + `TocOutline`。
5. **旧书重解析任务**（限制 4）：实现 `content_migration_jobs`，让旧书也能升到真实树。
6. **S9 切断分页路径的裸像素写入**（主计划 6.7、R1）。
