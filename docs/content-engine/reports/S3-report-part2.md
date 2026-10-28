# FlowReader 内容引擎改造 — S3 阶段报告（第 2 批：容器与 OPF）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 4.1 第 1—2 步、4.3 阅读顺序、Gate G3。

**S3 仍为 PARTIAL。** 本批新增 `EpubPackageParser`（container.xml + OPF manifest/spine）。

---

## 1. 本批交付

文件：`app/src/main/java/com/flowreader/app/util/epub/EpubPackageParser.kt`（纯 XML → 结构，不碰 ZIP I/O 与 Android，可 JVM 直接测试）

| 能力 | 对应计划条款 |
|---|---|
| `parseRootFilePath()`：从 `META-INF/container.xml` 取 `rootfile/@full-path`，缺失时报 `CONTAINER_ROOTFILE_MISSING` 而**不猜文件名** | 4.1 第 1 步 |
| `parse()`：读取 OPF 的 `version`、`unique-identifier`、`page-progression-direction` | 4.1 第 2 步 |
| manifest：每个 item 记录 `id / containerPath / media-type / properties / fallback`，href **经 `EpubResourceResolver` 按 OPF 目录解引用** | 4.2 第 2 条；4.5 `properties=nav` |
| spine：按 `itemref` 出现顺序保留**全部**出现，`linear` 省略视为 yes；`mainReadingOrder` 只取 linear，`auxiliaryReadingOrder` 取 linear=no | 4.3「主阅读顺序只按 spine 的 linear yes/省略项构建」 |
| 目录来源：EPUB3 `navItem`（properties 含 nav）与 EPUB2 `ncxItem`（`spine/@toc` 或 media-type） | 4.3 |
| 固定版式：`rendition:layout=pre-paged` 被识别（同时支持 EPUB3 的 `meta` 文本形式与 EPUB2 的 `content` 属性形式），`isFixedLayout` 供 UI 提示「统一排版兼容显示」 | 0.2 第 4 条 |
| 结构错误：`DUPLICATE_MANIFEST_ID`、`SPINE_IDREF_UNKNOWN`、`MANIFEST_EMPTY`、`SPINE_EMPTY`、`MANIFEST_HREF_REJECTED` 全部报诊断，**不 firstOrNull 静默任选** | 4.2 第 7 条 |

## 2. Gate G3 证据（本批）

测试：`app/src/test/java/com/flowreader/app/util/epub/EpubPackageParserTest.kt`（**8 项，全通过**）。夹具为自造 OPF/container XML。

| 用例 | 断言 |
|---|---|
| `containerXmlYieldsTheOpfPath` | `OEBPS/content.opf`，无诊断 |
| `missingRootfileIsDiagnosedInsteadOfGuessed` | 返回 null + `CONTAINER_ROOTFILE_MISSING` |
| `manifestHrefsResolveAgainstTheOpfDirectory` | `Text/ch1.xhtml` → `OEBPS/Text/ch1.xhtml`；`isImage`/`isXhtml`/`isNav` 判定正确 |
| `linearNoEntriesAreAuxiliaryNotPartOfTheMainReadingOrder` | spine 顺序 `[cover, c1, c2]` 完整保留；主顺序 `[c1, c2]`；辅助 `[cover]` |
| `renditionAndProgressionMetadataAreRecorded` | version 3.0、`rtl`、`pre-paged`、`isFixedLayout`、`pub-id` |
| `epub2NcxIsFoundThroughTheSpineTocAttribute` | version 2.0、`spineTocId=ncx`、`ncxItem=OEBPS/toc.ncx`、`navItem=null` |
| `duplicateManifestIdAndDanglingItemrefAreDiagnosedNotSilentlyAccepted` | 两类诊断齐全；重复 id 保留第一个且 manifest 只 1 条 |
| `emptyManifestAndSpineAreDiagnosed` | `MANIFEST_EMPTY` + `SPINE_EMPTY` |

**测试抓到的真实缺陷**：固定版式识别最初只读 `content` 属性，而 EPUB3 的标准形式是 `<meta property="rendition:layout">pre-paged</meta>`（值在**文本**里）。若不修，**固定版式 EPUB 会被静默当成普通可重排书**——正是 0.2 第 4 条明令禁止的行为。已同时支持两种形式。

```powershell
pwsh -NoProfile -File tools/verify.ps1
→ BUILD SUCCESSFUL in 1m 46s
→ verifyKotlinStyle 0 违规
→ 484 项测试 / 0 失败 / 83 套（本轮 +8）
→ APK 29,099,984 字节，2026/9/10 21:29
```

## 3. S3 完成矩阵

| S3 条目 | 状态 | 说明 |
|---|---|---|
| 4.2 `EpubResourceResolver` | **PASS** | 20 项测试，含计划六条强制例子 |
| 4.3 `EpubNavigationParser`（nav/NCX 树） | **PASS** | 11 项测试；span 卷头、三层树、landmarks 隔离、稳定 nodeId |
| 4.1 `EpubContainerReader`（container.xml） | **PASS** | 本批 |
| 4.1 `EpubPackageParser`（OPF manifest/spine） | **PASS** | 本批 |
| 4.1 ZIP 容器 IO 适配（条目枚举、大小/条目数上限 512MiB / 20,000） | **NOT_RUN** | 需 Android `ZipFile` 适配层 |
| 4.5 `EpubDocumentParser` + `StructuredDocument` 块模型 | **NOT_RUN** | 最大缺口：段落/标题/inline span/图片对象/列表/引用/表格/ruby/pre/脚注 |
| 4.6 资源对象化 + `ImageLayoutSpec` | **NOT_RUN** | resourceId 落盘、解码状态、测量统一 |
| 3.2 规范源文本 v1 + golden | **NOT_RUN** | 十条生成规则 |
| 4.4 TXT `HeadingKind` / 卷序 section | **NOT_RUN** | — |
| `ReadingManifestBuilder` / `PublicationStore` | **NOT_RUN** | S2 的 `activateRevision()` 已就绪待接 |
| **既有路径替换**：`BookParser` 改用新组件 | **NOT_RUN** | 计划第 16 节第 2 条的硬性检查项 |
| Gate G3 整体 | **PARTIAL** | 组件级通过；「导入结果 golden / 解析两次稳定 / 主线覆盖无缺失无重复」需在 DocumentParser + ManifestBuilder 完成后才能判 |

## 4. 风险与下一步

**风险（必须记录）**：新组件目前只被测试调用，生产导入链路仍是旧的 `htmlToFormattedText` + `[IMG:]`。S3 若停在这里，就正好命中计划第 16 节第 2 条「生成了结构化数据却仍把 UI 交给旧路径」。因此 **S3 的收尾动作必须是把 `BookParser.parseEpubStream` 切换到新组件**，这依赖 `EpubDocumentParser` + `ReadingManifestBuilder`。

**下一批（S3 收尾，按依赖顺序）**：
1. `EpubZipContainer`（Android 适配层）：条目枚举、`entryCount`/总解压量上限、重复条目检测。
2. `EpubDocumentParser`：XHTML → `StructuredDocument` 块（Paragraph/Heading/Image/List/Quote/Table/Ruby/Pre/Footnote）+ 规范源文本 v1 + 锚点索引。
3. `ReadingManifestBuilder`：逻辑 section 边界、主线去重、统计权重。
4. `PublicationStore`：staged 写入 → `activateRevision()` 激活 → `books.contentState=READY`。
5. `BookParser` 切换到新链路，并用 FX03/FX04 夹具做导入 golden。
