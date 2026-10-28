# FlowReader 内容引擎改造 — S3 阶段报告（第 7 批：导入链路切换与 Gate G3 判定）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 **4.1 第 7 步**、**5.3 激活协议**、
**第 16 节第 2 条**（"是否生成了结构化数据却仍把 UI 交给旧路径"）、**14.1 FX03/FX04**、Gate G3。
上一批：`S3-report-part6-publicationstore.md`。

**S3 判定：完成（Gate G3 PASS）。** 本批把导入链路真正切到新组件，并给出导入结果 golden。

---

## 1. 本批交付

| 文件 | 类型 | 改动 |
|---|---|---|
| `app/.../util/epub/PublicationImportCoordinator.kt` | **新增 330 行** | 七段链路串联 + 数据库侧单事务激活 + legacy 章节投影 + 目录树落库 |
| `app/.../util/BookParser.kt` | **改写** | `parseEpubStream` **删除**，改走内容引擎；清理 8 个只服务旧路径的死函数 |
| `app/.../util/BookImporter.kt` | 改写 | 拿到 `bookId` 后激活 revision；`publicationId` 复用规则 |
| `app/.../di/AppModule.kt` | 修改 | `PublicationStore` 的 Hilt provider |
| `data/.../dao/BookDao.kt` | 修改 | `updateContentState(bookId, publicationId, activeRevision, contentState)` |
| `domain/.../model/Book.kt` | 修改 | `Book.publicationId` / `Book.activeRevision` |
| `app/.../util/epub/EpubZipContainer.kt` | 修改 | `Container` **不再**实现 `OpenResult`（见 2.1） |
| `app/.../util/epub/EpubNavigationParser.kt` | 修改 | 卷判定收紧（见 2.3） |
| `app/.../util/epub/ReadingManifestBuilder.kt` | 修改 | `endOfMainContent` 不再吞掉尾部图片（见 2.2） |
| `app/src/test/.../PublicationImportGoldenTest.kt` | **新增 6 项** | FX03/FX04 导入 golden |

### 1.1 链路（4.1 七步，全部落在生产路径上）

```
BookParser.parseEpubViaContentEngine
  └─ PublicationImportCoordinator.prepareFromContainer
       ├─ EpubZipContainer.open            条目枚举/上限/重复条目
       ├─ EpubPackageParser                container.xml → OPF manifest/spine
       ├─ EpubNavigationParser             nav → HTML 目录 / NCX 回退
       ├─ EpubDocumentParser → StructuredDocument（规范源文本 v1 + 块 + 锚点）
       ├─ ReadingManifestBuilder           sections / 主线去重 / 权重 / 目录映射
       └─ PublicationStore.stage → commitFiles   分片 + 资源摘要命名 + 原子提交
  └─ PublicationImportCoordinator.projectChapters   legacy Chapter 投影（卷字段不丢）
BookImporter
  └─ PublicationImportCoordinator.activate
       └─ ContentIndexDao.activateRevision（单事务）
            ├─ publication_revisions / _documents / reading_sections / toc_nodes
            └─ activate 回调 → BookDao.updateContentState（同一事务）
```

**旧路径已删除**：`htmlToFormattedText` 不再服务 EPUB；`resolveEpubPath`、`resolveImagePath`、
`readEpubTocTitles`、`epubNavPath`、`isEpubCoverPage`、`coverImagePath`、`sanitizeForPath` 全部删除
（生产代码里对这 7 个符号的引用数为 0）。`htmlToFormattedTextForProjection` 仅保留给
**MOBI/FB2 的 HTML 投影**（计划 0.2 第 2 条：这两种格式不要求本期重写解析器），
且其中的图片分支已改为 `[IMG-UNRESOLVED:原引用]` —— 不再猜路径（F11 的 basename 兜底已消失）。

---

## 2. 本批抓到的缺陷（全部是实现侧，且都是"测试才暴露"的类型）

### 2.1 `Container` 实现 `OpenResult` 会让"失败必须先处理"在类型层面失效

`when (open(file)) { Failed -> …; Opened -> … }` 因为 `Container` 也是 `OpenResult` 而不再穷尽，
编译器要求补 `else` —— 一旦补了 `else`，"打开失败"就成了一条可以悄悄走掉的分支。
已让 `Container` 只实现 `AutoCloseable`，`open()` 的返回类型重新变成两分支穷尽。

### 2.2 `endOfMainContent` 只回退 `\n`，导致**尾部图片掉出最后一个 section**

正文以图片结尾时（`…正文丙\n\uFFFC\n`），旧实现回退全部 `\n` 后停在 `\uFFFC` **之后**？
不是 —— 它停在 `\uFFFC` **之前**，于是图片不在任何 section 的范围内：
区间层就把图吞了，渲染层自然看不到。这直接违反 4.5「不吞图」。
已改为回退**所有可折叠空白**（`\n \r \t 空格 换页`），对象占位符自然被包含。
FX03 夹具正是一条"最后一张图"的真实形态。

### 2.3 NCX 的"有子节点"被当成卷，凭空造出一个覆盖全书的假卷

实测（探针输出）：FX04 的 NCX 是 `第一章 → 同名` 两级 navPoint，
旧判定 `isGroup → ROLE_VOLUME` 把"第一章"标成卷，于是它的子章节被投影成
`volume = "第一章"`。真实书里 NCX 嵌套普遍表示**章 → 节**，不是卷；
EPUB2 没有可靠的卷标记。已收紧为：**只有 EPUB3 nav 中"用 `<span>` 作分组标题且自身无链接"的节点才是卷**，
其余一律不标 —— 宁可没有卷，也不伪造卷（4.4「无法判定时不要误删/误判」）。

### 2.4 顺手确认的两处"疑似缺陷"其实是误判（记录以免重复返工）

- **表格 TAB 分隔符**：探针一度显示首行两格粘连，原因是那次夹具是**单列两行**表格，
  本来就该是 `表头\n单元格\n`。用真实两列夹具复测为 `表头\t表头二\n单元格\t单元格二\n`，正确。
  顺手把单元格分隔符从"看 `nextElementSibling()`"改为"按行内位置前置 TAB" ——
  前者依赖 DOM 兄弟关系（首格曾返回 null），后者是确定性的。
- **章节字数与 manifest 字数差 1**：章节文本包含生成分隔符与 `U+FFFC`，而字数口径排除它们。
  这是**两个不同量**，不是缺陷；已把黄金测试改为两边同用 `TextUnits` 口径核对。

---

## 3. Gate G3 证据

### 3.1 导入结果 golden（14.1 FX03 / FX04）

| 用例 | 断言 |
|---|---|
| `fx03ImportProducesSectionsAnchorsResourcesAndChapters` | 同 XHTML 三锚点 → **4 个 section**（标题依次为 第一章/第一节/第二章/第三章）；section 范围**互不重叠**；`totalMainTextUnits == Σ section`；资源落盘**文件名 = SHA256(containerPath+contentHash)**且 `+` 未变空格；`documents/`、`source/original.bin`、`manifest.json` 齐全；章节 index 连续、内容非空、**卷字段为「第一卷 山河」×3 + null**；章节字数（同 `TextUnits` 口径）== manifest 主线字数 |
| `fx03ImportIsDeterministicAcrossTwoRuns` | 两次导入 revision / sectionId / 资源文件名 / 文档哈希 / 总字数**逐字一致**（INV02）；第二次复用 revision 目录（`reusedExisting`） |
| `fx03ActivationWritesOneTransactionAndSwitchesThePointer` | `activateRevision` 收到 `STATE_READY`、总数、全部 section、≥5 个目录节点且含 `ROLE_VOLUME`；**`bookDao.updateContentState(..., READY)` 恰好被调用一次** |
| `activationFailureLeavesTheBookUsableAndDoesNotThrow` | DAO 抛异常 → `Failed(CONTENT_ACTIVATION_FAILED)`；**不调用指针切换**；已提交的 revision 目录仍在（不在失败路径删数据） |
| `fx04NcxNestedAndUnicodeFragmentsBecomeIndependentSections` | NCX 嵌套 + Unicode fragment（`#%E6%B3%A81` → `注1`）→ **3 个独立 section**，标签不串子项，同名不同位置 `sectionId` 不同，偏移递增；**卷字段全 null（不伪造卷）** |
| `bothFixturesNeverProducePlaceholderImageMarkersInChapters` | F10 回归防线：章节内容**不含** `[IMG:`、不含设备绝对路径 |

### 3.2 覆盖矩阵（15.2 格式）

| 需求/阶段/测试ID | 状态 | 实现文件 | 验证命令或步骤 | 证据路径 | 剩余问题 |
|---|---|---|---|---|---|
| S3 / 4.2 路径解析（T043—T047、T062） | PASS | `EpubResourceResolver.kt` | `--tests '*EpubResourceResolverTest*'` | 20 项 | — |
| S3 / 4.3 目录树（T032—T035、T037） | PASS | `EpubNavigationParser.kt` | `--tests '*EpubNavigationParserTest*'` | 11 项 | — |
| S3 / 4.1 OPF（T037、T042） | PASS | `EpubPackageParser.kt` | `--tests '*EpubPackageParserTest*'` | 8 项 | — |
| S3 / 4.1 容器（T090、11.2） | PASS | `EpubZipContainer.kt` | `--tests '*EpubZipContainerTest*'` | 26 项 | 512MiB 仅结构级验证 |
| S3 / 3.2 规范源文本（T002—T005、T035、T048、T057、T058、T093、T094） | PASS | `EpubDocumentParser.kt` | `--tests '*EpubDocumentParserTest*'` | 34 项 | `pre` 行首缩进（依赖级，见 §5） |
| S3 / 4.5 结构化正文 | PASS | `StructuredDocument.kt` | 同上 | 34 项 | `RubyBlock` 类型由 `InlineSpan(RUBY_TEXT)` 承载 |
| S3 / 3.1 字数口径 | PASS | `TextUnits.kt` | 同上 | — | — |
| S3 / 4.1⑥ 阅读清单、10.2 主线去重（T036、T039、T041、T069、T071） | PASS | `ReadingManifestBuilder.kt` | `--tests '*ReadingManifestBuilderTest*'` | 17 项 | — |
| S3 / 5.1 内容包与分片、4.6② resourceId | PASS | `PublicationStore.kt` | `--tests '*PublicationStoreTest*'` | 13 项 | — |
| **S3 / 4.1⑦ + 5.3 导入与激活** | **PASS** | `PublicationImportCoordinator.kt`、`BookImporter.kt` | `--tests '*PublicationImportGoldenTest*'` | 6 项 | 真机验收未做 |
| **S3 / 16 节第 2 条：旧路径替换** | **PASS** | `BookParser.kt`（`parseEpubStream` 已删除） | 生产代码对 7 个旧符号引用数 = 0；`[IMG:` 仅出现在注释里 | 见 §1.1 | — |
| **Gate G3 整体**（golden / 解析两次稳定 / 主线覆盖无缺失无重复 / 未支持有诊断 / 原资源可回查） | **PASS** | — | 见 §3.1 六项 | — | 见 §5 三项能力限制 |
| 4.4 TXT `HeadingKind`/卷序 | NOT_RUN | — | — | — | 下一阶段 |
| S4—S11 | NOT_RUN | — | — | — | 见 HANDOFF |

### 3.3 门禁

```powershell
pwsh -NoProfile -File tools\verify.ps1
→ BUILD SUCCESSFUL in 1m 31s
→ verifyKotlinStyle 0 违规
→ 580 项测试 / 0 失败 / 0 错误 / 88 套（本轮 +6）
→ APK 29,373,301 字节，2026/9/10 22:59
```

---

## 4. 用户可见行为的变化（本批第一次真正改变行为）

| 方面 | 旧行为 | 新行为 |
|---|---|---|
| EPUB 正文 | 压成纯文本，粗体/斜体/链接/列表丢失 | 结构化块保存；语义与链接目标保留 |
| 图片 | `[IMG:绝对路径]` 字符串标记 | 结构对象 + `resources/<resourceId>` 落盘；正文占位 U+FFFC |
| 目录 | 扁平化、`<span>` 卷头丢失、同文档多锚点互相覆盖 | 保留层级与卷头；同文档多锚点各自成为目标与 section |
| 章节切分 | 按 8,000 字物理分片，产生 `(1)(2)` 伪章节 | 按逻辑 section；分片只是 I/O 单位 |
| 卷字段 | 组装时丢失 | 由目录树卷分组投影得到 |
| 书库元数据 | — | 新增 `publicationId` / `activeRevision` / `contentState=READY` |

**注意**：阅读器 UI 仍按 `Chapter` 渲染（本期只到 S3）。但章节内容、标题、卷、切分方式
**已经来自新链路**，不再是旧解析产物 —— 这正是第 16 节第 2 条要的状态。

---

## 5. 能力限制与未验证项（不得当作已完成）

| 项 | 状态 | 事实 |
|---|---|---|
| `pre` 的行首缩进 | **能力限制（依赖级）** | Jsoup 的 XML 解析器在进入本解析器前就丢掉"换行后的行首缩进"。块内空格与换行顺序正常，**只有行首缩进保不住**。恢复需换保留空白的 XML 前端，未擅自更换 |
| 512MiB 总量上限 | 部分验证 | 20,000 条目上限用**真实 20,001 条目 ZIP** 验证；512 MiB 仅结构级验证 |
| SVG 图片渲染 | NOT_RUN | 4.6 第 4 条要求先做 `coil-svg` spike。现在只解析出引用与 `UnsupportedBlock`，**不宣称 R5 通过** |
| 真机验收 | NOT_RUN | 全部结论为自动化测试级；设备脚本（14.3）未执行 |
| 旧书迁移 / 备份 | NOT_RUN | S10 |

---

## 6. S3 之后（按主计划顺序）

- **S4 接通真实 reader 与内容定位**（Gate G4）：navigation coordinator、分页/滚动源映射、重排恢复、
  有序保存、视口几何。**这是下一阶段的第一要务** —— 现在 `Chapter` 内容已经来自源文本，
  但 reader 仍用 `Book.currentPosition: Int`（R1 未落库）。
- S5 图片与语义呈现（`ImageLayoutSpec`、预览、SVG 能力、列表/引用/表格/ruby）
- S6 目录分卷（统一阅读器与详情页目录、折叠状态、搜索）
- S7 链接/脚注/历史；S8 书签/批注/搜索/听书；S9 统计收尾；S10 旧库与备份；S11 全量验收
- 另：**4.4 TXT `HeadingKind`/卷序**属 S3 计划项但未做，随 S4 一并处理或单独补做。
