# FlowReader 内容引擎改造 — S3 阶段报告（部分完成）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 4.2 / 4.3 / 13 节 S3 与其 Gate G3。

**状态：S3 部分完成（PARTIAL）。** 已交付计划中最强调的两块纯逻辑组件并通过验收；结构化正文解析、资源落盘、ReadingManifest、PublicationStore 尚未实现。

---

## 1. 本次交付

### 1.1 `EpubResourceResolver`（主计划 4.2）

文件：`app/src/main/java/com/flowreader/app/util/epub/EpubResourceResolver.kt`

计划要求「统一接口 `resolve(baseDocumentKey, rawHref)`，**只允许这一处实现路径逻辑**」。本类即该单点，纯 Kotlin 无 Android 类型。

按 4.2 逐条落实：先拆 scheme/authority/path/query/fragment 再各自解码；相对路径以引用它的文档目录为基准；路径段 percent-decode **恰好一次**并拒绝解码后含 `/`、`\`、NUL；`.`/`..` 消解且上溢返回 `PATH_ESCAPE`；query 与资源身份分离但保留；fragment 单独解码一次、不删不小写；资源键大小写敏感、**无 basename 兜底**；外部 scheme 只识别不加载；`javascript/file/content/intent/data` 一律拒绝。

**修掉的两个既有缺陷**：F05（三处各自 `substringBefore('#')` 导致同文档多锚点互相覆盖）、F11（表单式 `URLDecoder` 把 `+` 变空格、非法编码静默替换为 U+FFFD）。

### 1.2 `EpubNavigationParser`（主计划 4.3）

文件：`app/src/main/java/com/flowreader/app/util/epub/EpubNavigationParser.kt`

- EPUB3：只把 `epub:type` token 含 `toc` 的 nav 当主目录；`landmarks`/`page-list` 单独返回；多个 toc nav 报 `TOC_NAV_AMBIGUOUS` 且只取第一个。
- 按 `li` 的**直接子** `a`/`span` 读取、递归**直接子** `ol`：三层树与 `<span>` 卷头都保留（修 F06）。
- EPUB2 NCX：递归 navPoint；label **只读本节点自己的 navLabel**，不拼子节点文本。
- `nodeId = SHA-256(源类型 + 源文件 + 结构路径 + 标签) 前 16 位`：确定性、无随机 UUID、同名不同位置不同 ID。
- 提供 `flatten()` 输出（nodeId/parentId/siblingOrder），可直接写入 S2 已就绪的 `toc_nodes` 表。

---

## 2. Gate G3 证据（部分）

测试：`app/src/test/java/com/flowreader/app/util/epub/EpubResourceResolverTest.kt`（**20 项**）、`EpubNavigationParserTest.kt`（**11 项**），全部通过。夹具全部为自造 XHTML/NCX 片段，不含任何版权样书。

| 计划要求 | 覆盖用例 |
|---|---|
| 4.2 六条强制例子 | `+` 保持字面、`%20` 单次解码、`#n1` 同文档锚点、`#%E6%B3%A81`→`注1`、`../../../x`→PATH_ESCAPE、`%252e%252e`→字面 `%2e%2e` 不越界 |
| T043 不同基准目录 | 同一相对 href 在 OPF / XHTML / nav 三个基准下得到不同结果 |
| T044 `+`、空格、中文 | 已覆盖（`+` 与 `%20`） |
| T045 不按 basename 兜底 | `A.PNG` 大小写敏感、不匹配其它目录同名文件 |
| T046 单次解码/大小写 | 双重 percent、`%ZZ`、`%2`、`%FF`（非法 UTF-8）全部拒绝 |
| T047 越界与编码后分隔符 | `../` 上溢、`..%2f..%2f` 解码后含分隔符、`C:/Windows/x.png` 宿主绝对路径 |
| T062 外部与危险 scheme | `https`/`mailto` 识别为 External；`javascript/file/content/intent/data` 全部 Rejected |
| T032 span 卷头 / 三层 nav | 卷是 GROUP + VOLUME 语义，无链接卷头 target 为 null；三层 parentId/siblingOrder 正确 |
| T033 landmarks/page-list 不污染 | 主目录仅 1 条，landmarks 与 page-list 各自独立 |
| T034 NCX 嵌套/Unicode fragment | 父标题不含子文本；`#%E6%B3%A81` → `注1` |
| **T035 同文档多锚点** | 两个锚点保留为不同节点、fragment 不同、nodeId 不同（原 F05 的 G0 复现，现已转为通过断言） |
| INV02 确定性 | 同一输入两次解析 nodeId 完全一致 |
| 同名不同位置 | 「序章」出现两次 → nodeId 不同（UI key 不得用 label） |

命令与结果：

```powershell
pwsh -NoProfile -File tools/verify.ps1
→ BUILD SUCCESSFUL in 17s
→ verifyKotlinStyle 0 违规
→ testDebugUnitTest：476 项 / 0 失败 / 82 套（本轮新增 31 项）
→ APK 29,099,983 字节，2026/9/10 21:22
```

**测试期间修掉一个自身实现缺陷**：纯 fragment 引用 `#n1` 曾解析成「基准文档所在目录」而不是基准文档自身（`OEBPS/Text` vs `OEBPS/Text/a.xhtml`）。另有 6 处测试期望是我抄计划例子时多加 `OEBPS/` 前缀所致——已核对实现正确后修正期望，未放宽断言。

---

## 3. S3 未完成项（NOT_RUN）

| 计划条款 | 缺口 | 依赖 |
|---|---|---|
| 4.1 `EpubContainerReader` / `EpubPackageParser` | 容器校验、OPF namespace-aware manifest/spine（含 properties、fallback、linear、page-progression、rendition）未实现 | — |
| 4.5 `StructuredDocument` | 段落/标题/inline span/图片对象/列表/引用/表格/ruby/pre/脚注的块模型与源范围未实现 | — |
| 4.6 资源对象化 | `resourceId = SHA256(containerPath + contentHash)` 落盘、`ImageLayoutSpec`、解码状态未实现 | — |
| 4.4 `TXT` 卷识别 | `HeadingKind` 独立返回、卷序 section、伪卷判定未实现（volume 字段丢失本身已在 0.1.7 修） | — |
| 3.2 规范源文本 v1 | 十条生成规则与 golden 未实现 | S1 模型已就绪 |
| `ReadingManifestBuilder` / `PublicationStore` | 主线去重、section 边界、staged→READY 激活未接线 | S2 的 `activateRevision()` 已就绪 |
| 4.1 既有路径改造 | `BookParser` 仍在用旧的 `htmlToFormattedText` + `[IMG:]`，**新组件尚未被生产代码调用** | 下一步 |

**这一点必须明说**：本轮新增的两个组件目前只被测试调用，**尚未接入 `BookParser` 的导入链路**，因此用户可见行为与上一版一致。计划第 16 节第 2 条「是否生成了结构化数据却仍把 UI 交给旧路径」正是这个风险；S3 收尾时必须完成替换，不能停在“组件写好了”。

---

## 4. 更新后的完成矩阵

| 阶段/需求 | 状态 |
|---|---|
| S0 基线 + G0 复现（4/5 类） | **PASS** |
| S1 定位模型 / 进度索引 / 会话策略 | **PASS** |
| S2 存储 + v10 迁移 + 原子激活 | **PASS（G2）** |
| **S3 解析组件（resolver + navigation）** | **PARTIAL**（2/7 组件 + 31 项测试；未接入导入链路） |
| S9 接线（R3/F02/F03/F04/F08） | **PARTIAL**（进度/时长/剩余时间/卷字段已修；曝光与 delta 未接线） |
| R1 定位落库 / R2 结构保留 / R4 目录树 / R5 插图 / R6 锚点脚注 | **NOT_RUN** |
| S4—S11 | **NOT_RUN** |

**完成定义（15.3）自评**：未达成。
