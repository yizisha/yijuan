# FlowReader 内容引擎改造 — S3 阶段报告（第 4 批：结构化正文与规范源文本 v1）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 **3.2 规范源文本 v1**、**4.5 结构化正文合同**、
**8.1 锚点索引**、**3.1 字数口径**、**11.2 输入上限**、Gate G3。
上一批：`S3-report-part3-zipcontainer.md`。

**S3 仍为 PARTIAL。** 本批交付计划里最大的一个缺口：XHTML → `StructuredDocument`，
以及全系统唯一的"一个字是多少"口径。

---

## 1. 本批交付

| 文件 | 类型 | 职责 |
|---|---|---|
| `domain/.../model/StructuredDocument.kt` | 新增 336 行 | 结构化正文合同：块联合、行内 span、资源引用、锚点、诊断 |
| `domain/.../model/TextUnits.kt` | 新增 84 行 | **唯一**字数口径（10.2 / 3.2 末段） |
| `app/.../util/epub/EpubDocumentParser.kt` | 新增 1287 行 | XHTML → 结构块 + 规范源文本 v1 + 锚点索引 |
| `domain/.../model/ContentLocator.kt` | 修改 | 上一批已加 `CONTAINER_INVALID` |
| `app/src/test/.../EpubDocumentParserTest.kt` | 新增 34 项测试 | Gate G3 证据 |

### 1.1 规范源文本 v1 十条规则 → 代码落点（可逐条核对）

| 规则 | 落点 |
|---|---|
| 1 保留原始字节、按声明解码、失败点名文档 | `EpubZipContainer.decodeText`（上一批）；本解析器只接收已解码文本 |
| 2 实体解码、可折叠空白归一为一个 U+0020、`pre` 保留空白、CRLF/CR→LF、**NBSP 不折叠** | `TextEmitter.emitText` + `normalizeLineEndings` + `COLLAPSIBLE_WHITESPACE`（NBSP 明确不在列内） |
| 3 不用 NFC/NFKC、不做简繁转换、**不删"重复标题"** | 全程原文直通；`headingLevelsArePreservedAndTitlesAreNotDeduplicated` 钉住 |
| 4 叶块后追加 LF（最后一个也追加）、容器不额外加分隔符、空块不生成空行、`br` 生成 LF、列表项文字参与/自动编号不参与、表格单元格间 TAB 行末 LF | `TextEmitter.endLeaf`/`beginLeaf`/`emitTab`/`endTableRow`/`rollbackEmptyLeaf` |
| 5 图片/无文本原子对象占一个 U+FFFC，alt 进元数据**不进**字符计数 | `TextEmitter.emitObject` + `ImageBlock.objectOffsetUtf16` |
| 6 `ruby` 基字进主文本、`rt` 进注音语义、不重复计算 | `emitRuby`：基字写入主文本；`rt` 挂在 `InlineSpan(kind=RUBY_TEXT).annotations` 上，主坐标仍是基字 |
| 7 脚注保留源坐标 + AUXILIARY role；引用标记保留坐标但不计权重 | `collectNoterefTargets`（两遍扫描）+ `BlockRole.AUXILIARY` + `TextUnits.countMainUnits` 只算 MAIN |
| 8 `script`/`style` 不进文本流，CSS 不参与 | `SKIPPED_TAGS` |
| 9 每个文本片段/块/元素 id 记录规范源范围与 DOM 路径 | `SourceSpan` + `InlineSpan.range` + `ContentBlock.domPath` |
| 10 TXT 换行统一 | 属 TXT 路径（本批未做，见未完成项） |

### 1.2 块模型（4.5 逐行对应）

`ParagraphBlock` / `HeadingBlock(level 1..6)` / `InlineSpan(strong,em,code,link,noteref,ruby,sup,sub,strike,underline,mark)`
/ `ImageBlock` / `FigureBlock(image+caption)` / `ListBlock(+ListItem.ordered/start/value/嵌套层级/子块)`
/ `QuoteBlock(level+children)` / `TableBlock(+rowSpan/colSpan/单元格子内容)` / `RubyBlock` /
`PreformattedBlock` / `UnsupportedBlock(原标签+替代文字+原引用)`。

**块 ID 确定性**：`"b:" + SHA256(documentKey + DOM 结构路径).take(24)` ——
不用随机 UUID、不用分页序号；同一输入两次解析 ID 完全一致（INV02，有测试钉住）；
同名不同位置的块必然不同 ID。

### 1.3 字数口径集中化

`TextUnits` 是**唯一**实现：Unicode **code point** 计数，排除空白与 `U+FFFC`，
只对 `BlockRole.MAIN` 的块求和（图片**不**换算成"几百字"）。
`EpubDocumentParser` 解析完结构后调用 `TextUnits.countMainUnits(document)` 回填，
不存在第二处字数计算。

---

## 2. 测试抓到的真实缺陷（都发生在实现侧）

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | `TextEmitter.endLeaf()` 的幂等条件写反（`val appended = !(end == 0 \|\| builder[end-1] != '\n')`） | **嵌套列表每项都少一个 LF**，`甲\n乙\n丙` 变成 `甲乙\n丙` —— 两个独立叶块被粘成一段，定位与统计跟着偏 | 改为"仅当叶块自身未写过 LF 且不在行首时补 LF" |
| 2 | 行内图片被包成 `InlineSpan(CODE, image.range)` | 块级图片（`<div><img/></div>`）被误判成"有行内内容的段落"，**4.6「图片是结构对象」在顶层直接失效** | 图片不再生成 span；块级图片通过 `lastInlineImage` 提升为 `ImageBlock` |
| 3 | 容器叶块边界未跟踪（`leafStart`/`leafOpen` 缺失） | `<ul><li>甲<ol>…` 这类容器与子叶块各加一次分隔符，或反向吞掉子块 LF | 引入 `beginLeaf()`/`leafOpen`，所有叶块发射器成对调用 |

第 1 条尤其值得记录：它**只有在"嵌套容器 + 叶块收尾"组合下才暴露**，
单层段落/标题全绿。是 T093（heading/list/quote/pre 结构）这一类用例的价值所在。

---

## 3. Gate G3 证据（本批）

测试：`EpubDocumentParserTest.kt`，**34 项，全通过**。

### 3.1 计划 14.1 指定的最小源文本 golden（T002）——**PASS**

```kotlin
parse("""<p id="p1">甲<strong>乙</strong>😀<img src="../Images/a+b.png" alt="图"/>丙</p>""")
```

| 断言 | 期望 | 结果 |
|---|---|---|
| 规范文本 | `甲乙😀\uFFFC丙\n` | PASS |
| UTF-16 长度 | 7 | PASS |
| 有效字数 | 4（甲、乙、😀、丙） | PASS |
| `p1` 起点 | 0 | PASS |
| 图对象范围 | `[4,5)` | PASS |
| 丙范围 | `[5,6)` | PASS |
| 强强调范围 | `[1,2)` | PASS |
| 图片引用解引用 | `../Images/a+b.png` → `OEBPS/Images/a+b.png`，`+` 不变空格 | PASS |

### 3.2 其余覆盖

| 用例 | 对应测试 ID |
|---|---|
| NBSP 不折叠 / CRLF+CR→LF / 行内边界空白只折叠一次 | T003、T004 |
| 每个叶块一个 LF；空块不产空行；`br` 成 LF | — |
| 列表项文字计字、自动编号不计；嵌套层级与子块 | T093 |
| 表格 TAB + 行末 LF、`th` 判定、`colspan/rowspan` 保留、分隔符不计字 | T093、T094 |
| `pre` 空白保留 | T093 |
| 图文相邻不吞字；块级图片；`figure`+`figcaption`；被拒/外部引用**不静默消失** | T048、T051 |
| `ruby` 基字进主文本、注音单独保留、不重复计字 | T094 |
| 脚注 → AUXILIARY 且不计主线权重；`epub:type="footnote"` 同样辅助 | 3.2 第 7 条 |
| `script`/`style` 不进文本流 | 3.2 第 8 条 |
| 锚点索引（`id`/`xml:id`/`a[name]`）、**全部落在合法 UTF-16 边界**、重复 id 取第一个 + 诊断 | T057、T058 |
| 标题 1..6 层级保留、**不删重复标题** | T093 |
| 引用嵌套层级、不支持元素保留替代文字与原引用 | T093、T094 |
| 行内 `strong>em` 嵌套深度；链接目标经路径逻辑解析一次（含 `#%E6%B3%A81`→`注1`） | T056 |
| **同一输入两次解析**：文本/块 ID/锚点/字数完全一致（INV02） | T001 的确定性部分 |
| 块 ID 确定性摘要（长度 26、前缀 `b:`、位置不同即不同） | INV02 |
| 嵌套 200 层 → `DOCUMENT_TOO_DEEP` 且**返回空文档而不是半解析**；100 层通过 | T090、11.2 |
| 缺 `body` → 诊断而非崩溃 | — |
| 源范围不切代理对（emoji 各计 1 字） | T005 |

```powershell
pwsh -NoProfile -File tools\verify.ps1
→ BUILD SUCCESSFUL in 2m 7s
→ verifyKotlinStyle 0 违规
→ 544 项测试 / 0 失败 / 0 错误 / 85 套（本轮 +34）
→ APK 29,123,478 字节，2026/9/10 22:21
```

---

## 4. S3 完成矩阵（本批更新）

| S3 条目 | 状态 | 实现文件 | 验证 | 剩余问题 |
|---|---|---|---|---|
| 4.2 `EpubResourceResolver` | **PASS** | `EpubResourceResolver.kt` | 20 项 | — |
| 4.3 `EpubNavigationParser` | **PASS** | `EpubNavigationParser.kt` | 11 项 | — |
| 4.1 `EpubPackageParser` | **PASS** | `EpubPackageParser.kt` | 8 项 | — |
| 4.1 ZIP 容器 IO（枚举/上限/重复条目） | **PASS** | `EpubZipContainer.kt` | 26 项 | 512MiB 为结构级验证（未分配真实内存） |
| 4.1 四组件接线 | **PASS** | — | `EpubZipContainerTest` | — |
| **3.2 规范源文本 v1** | **PASS（含 1 项能力限制）** | `EpubDocumentParser.kt` | 34 项含 14.1 golden | `pre` 行首缩进见下方 BLOCKED 项 |
| **4.5 结构化正文合同** | **PASS** | `StructuredDocument.kt` + 解析器 | 34 项 | `RubyBlock` 类型已定义但由 `InlineSpan(RUBY_TEXT).annotations` 承载（语义等价，未生成冗余类型实例） |
| **8.1 锚点索引** | **PASS** | `EpubDocumentParser.kt` | 4 项 | 反向「锚点 → 隐藏目标下一可见内容」映射属 S4/S7（需要导航层） |
| 3.1 字数口径 | **PASS** | `TextUnits.kt` | 含在 34 项内 | — |
| 4.6 资源对象化 + `resourceId=SHA256(...)` | **NOT_RUN** | — | — | 需要容器字节 + 落盘（`PublicationStore`）；本批只做引用解析 |
| 4.4 TXT `HeadingKind` / 卷序 section | **NOT_RUN** | — | — | — |
| `ReadingManifestBuilder` | **NOT_RUN** | — | — | — |
| `PublicationStore` | **NOT_RUN** | — | — | S2 的 `activateRevision()` 已就绪待接 |
| **既有路径替换**（`BookParser` 切新链路） | **NOT_RUN** | — | — | 计划第 16 节第 2 条硬性检查项 |
| Gate G3 整体 | **PARTIAL** | — | — | 「导入结果 golden / 主线覆盖无缺失无重复」需 ManifestBuilder 完成后才能判 |

### BLOCKED / 能力限制（必须明说，不当作已支持）

| 项 | 状态 | 事实与依据 |
|---|---|---|
| `pre` 的行首缩进保留（3.2 第 2/8 条要求"`pre` 保留空白"） | **能力限制（已登记）** | Jsoup 的 **XML 解析器**在进入本解析器之前就把"换行后的行首缩进"丢掉了：`<pre>甲  乙\n  丙</pre>` 的 TextNode 实测为 `甲  乙\n丙`（用独立探针直接读 DOM 证实，不是本解析器改写源文本）。块内空格与换行顺序保留正常，**只有行首缩进保不住**。要恢复必须换保留空白的 XML 前端；本期不换（属依赖级变更，需单独确认）。已写进测试注释与本节，避免日后被当成"已支持" |
| 512MiB 总量上限的"真实内存"验证 | **部分验证** | 20,000 条目上限已用**真实 20,001 条目 ZIP** 验证；512MiB 只做结构级验证（单测不分配该内存）。见第 3 批报告 |
| SVG 图片渲染 | **NOT_RUN** | 4.6 第 4 条要求先做 `coil-svg` 能力 spike。本批只解析出引用与 `UnsupportedBlock`，**不得**据此宣称 R5 通过 |

---

## 5. 与上一批相同的风险（继续记录）

容器 / OPF / 目录 / 路径 / 文档解析 / 字数口径六个组件目前**仍只被测试调用**，
`BookParser.parseEpubStream` 依旧走旧的 `htmlToFormattedText` + `[IMG:]`。
**用户可见行为与上一版一致。** S3 收尾必须把导入链路切过去，依赖 `ReadingManifestBuilder` + `PublicationStore`。

---

## 6. 下一批（S3 第 5 批，按依赖顺序）

1. `ReadingManifestBuilder`：逻辑 section 边界（4.3 末段：主 spine 上不同的叶级可读目录目标）、
   主线去重（10.2：重复 spine 出现只算一份 + occurrenceId 保留）、每 section 权重
   （`TextUnits`）、目录目标↔section 映射、GROUP 覆盖集合。
2. `PublicationStore`：`resourceId = SHA256(containerPath + contentHash)` 资源落盘
   （文件名用摘要，不用书名/timestamp/basename；DOM/locator 不存设备绝对路径）、
   staged 目录 → `ContentIndexDao.activateRevision()` → `books.contentState = READY`。
3. `BookParser.parseEpubStream` 切换到新链路 + FX03/FX04 导入 golden（内容/目录/图像/链接索引完整性）。
4. 之后：4.4 TXT `HeadingKind`/卷序 → Gate G3 整体判定。
