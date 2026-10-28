# FlowReader 内容引擎改造 — S3 阶段报告（第 3 批：ZIP 容器 IO 适配层）

执行者：DSH Agent。日期：2026-09-10。依据：主计划 4.1 第 1 步、4.2 第 7 条、11.2 输入上限、Gate G3。
上一批：`S3-report-part2.md`（`EpubPackageParser`）。

**S3 仍为 PARTIAL。** 本批新增 `EpubZipContainer`，并把它与已有的 `EpubPackageParser`、
`EpubNavigationParser`、`EpubResourceResolver` 做了端到端接线验证（container.xml → OPF → nav）。

---

## 1. 本批交付

| 文件 | 类型 | 职责 |
|---|---|---|
| `app/src/main/java/com/flowreader/app/util/epub/EpubZipContainer.kt` | 新增 498 行 | ZIP 条目枚举、输入上限、重复条目检测、严格文本解码 |
| `app/src/test/java/com/flowreader/app/util/epub/EpubFixtureBuilder.kt` | 新增（测试夹具） | 按 ZIP 规范手写本地头 + 中央目录的 EPUB 构建器 |
| `app/src/test/java/com/flowreader/app/util/epub/EpubZipContainerTest.kt` | 新增 26 项测试 | Gate G3 证据 |
| `domain/.../model/ContentLocator.kt` | 修改（+9 行枚举） | 新增 `ContentErrorCode.CONTAINER_INVALID` |

### 1.1 能力与对应计划条款

| 能力 | 计划条款 |
|---|---|
| `open(file, limits)`：文件存在 → 条目数上限 → ZIP64 拒绝 → 声明解压总量上限 → 重复条目，任一步失败不返回半开的容器 | 11.2；4.2 第 7 条 |
| 直接读 ZIP 中央目录（EOCD + central directory entries），**不依赖 `ZipFile`** 完成上限与重复判定 | 4.2 第 7 条 |
| `Entry(path, declaredSize, compressionMethod, isDirectory)`、`entries`、`find()` —— 资源键与 ZIP 条目名逐字一致、**大小写敏感、无 basename 兜底** | 4.2 第 3/6 条 |
| 条目不存在的读取返回 `EntryMissingException(path)`，不返回空成功 | INV10 |
| 读取时按 **声明大小为硬界** 解压，声明 1KiB 实解 1GiB 立即失败 | 11.2（ZIP 炸弹） |
| 读取期的剩余额度按**已计数条目**扣减；已计过的条目重读不再占额度 | 11.2 |
| 分类上限：文档 16MiB / 图片 24MiB / 其它 64MiB（沿用现有 `BookParser` 常量） | 11.2 |
| `decodeText()`：BOM（UTF-8/UTF-16LE/BE）→ 无 BOM 时按 ASCII 超集扫 XML 声明的 `encoding` → 默认 UTF-8；解码器一律 `REPORT` | 3.2 第 1 条 |
| 非法 UTF-8 / 未声明 UTF-16 / 不支持的声明编码 → `TextDecodingException`，**错误信息点名具体 documentKey** | 3.2 第 1 条「失败报告具体文档，不静默换编码」 |
| ZIP 中的同名条目 → `DUPLICATE_ZIP_ENTRY` 诊断 + 在枚举里如实出现两次，**不 firstOrNull 任选** | 4.2 第 7 条 |
| `declaredEntryCount` 报告中央目录的真实规模（与 `ZipFile` 折叠后的 `entryCount` 区分） | 4.2 第 7 条 |

### 1.2 上限数值为什么做成可注入

`ContainerLimits` 是 data class，**生产路径只用 `EpubZipContainer.DEFAULT_LIMITS`**（512MiB / 20,000 / 16MiB / 24MiB / 64MiB）。
理由必须说清楚：单测不可能为 512MiB 分配真实内存，但**执行这些上限的代码路径必须被真的跑到**，否则「写了上限」与「上限真的生效」无法区分。
因此测试用小数值驱动同一段实现；同时另有一个用例真的造出 **20,001 个条目**的真实 ZIP 结构来验证 20,000 这条线本身。
`DEFAULT_LIMITS` 的四个数值另有断言钉住，防止实现被悄悄放宽（0.3「不能私自放宽」）。

---

## 2. 测试抓到的两个真实缺陷（都发生在实现侧，不是夹具问题）

### 2.1 ZIP 字段字节序：`ByteBuffer` 默认大端，会把**每一个合法 EPUB** 判成「不是有效的 ZIP 容器」

中央目录解析最初写成 `java.nio.ByteBuffer.wrap(bytes)`，而 `ByteBuffer.wrap` **默认 `BIG_ENDIAN`**，
ZIP 的所有多字节字段却是**小端**。后果：EOCD 签名比较 `0x06054B50` 永远不成立，
`readCentralDirectoryEntries` 返回 null，`open()` 报「不是有效的 ZIP 容器：找不到中央目录结束记录」——
**所有正常 EPUB 都导入失败**。已在 `littleEndian()` 包装函数里统一修正，并保留该函数（不在各处手写 `ByteBuffer.wrap`）以免复发。

这个缺陷只有在「用真实 ZIP 字节结构跑一遍」时才会暴露：夹具的字节序错误曾**掩盖**了它（两边都错 → 内部自洽），
直到用 `java.util.zip.ZipFile` 作为第三方对照才同时暴露了两个方向的错误。

### 2.2 累计额度把已计过的条目重复扣费

`readEntryBytes` 最初对**每个**条目都做 `declared > (总量上限 - 已计字节)` 判定。
于是「读过的文档再读一遍」会凭空再占一份额度，一本正常书只要重复读同一 XHTML（nav 与 spine 都引用同一文档是常态）
就会被判成超限。已改为：剩余额度只对**新条目**扣减，已计过的条目重读不占额度；实测字节仍按声明大小逐次校验。

### 2.3 夹具自身的缺陷（记录，因为它是「测试可信度」问题）

`EpubFixtureBuilder` 初版把 ZIP 多字节字段写成**大端**，且 `writeTo()` 不清空缓冲区（同一 builder 二次写出会拼出垃圾文件）、
`position` 在扩容后失准。三者都会让测试**看起来**在验收集器，实际验的是坏字节。
已全部修正，并保留注释说明成因。

---

## 3. Gate G3 证据（本批）

测试：`app/src/test/java/com/flowreader/app/util/epub/EpubZipContainerTest.kt`，**26 项，全通过**。

| 用例 | 断言（对应测试 ID） |
|---|---|
| `entriesAreEnumeratedWithDeclaredSizesAndCount` | 5 条目按名排序枚举、声明大小来自中央目录、`find("oebps/content.opf")` 返回 null（T045/T046 大小写敏感） |
| `stepOneToThreeWiringReadsContainerAndOpfThroughTheContainer` | container.xml → `EpubPackageParser.parseRootFilePath` → OPF → `parse()` → nav 读取，**四个新组件串起来一次跑通** |
| `utf8EntryNamesAndUnicodeFragmentsSurviveTheRoundTrip` | 中文条目名可读；`a+b.png` 的 `+` 保持字面；`第一章.xhtml#%E6%B3%A81` → `注1` 且能在容器里找回（T044） |
| `entryCountLimitIsEnforced` | 注入上限 2 → 拒绝，消息含「条目数超过上限」 |
| `twentyThousandEntriesAreRejectedAndTwentyThousandAreAccepted` | **真实 20,001 条目结构被拒**（消息 `20001 > 20000`）；20,000 条目通过 |
| `defaultTotalLimitIsFiveHundredTwelveMiB` | 512MiB / 20,000 / 16MiB / 24MiB 四个默认值钉死 |
| `declaredTotalSizeOfFiveEntriesIsCheckedAgainstTheProductionLimit` | 声明总量 1500：上限 1400 通过、1399 拒绝 |
| `oversizedEntryIsRejectedByCategoryLimitWithoutReadingIt` | 分类上限正确、超限报 `CONTENT_LIMIT_EXCEEDED`、**失败不污染累计量** |
| `cumulativeBudgetStopsRunawayReads` | 5×300 字节在 1600 上限下完整可读、累计量 1500、重读不重复计 |
| `readBudgetShrinksAsEntriesAreAccounted` | 额度随累计量递减（900→500→100）；条目不存在时额度不变 |
| `missingEntryIsReportedWithItsPath` | `EntryMissingException` 带路径（INV10） |
| `duplicateZipEntriesAreDiagnosedAndNeverSilentlyPicked` | `DUPLICATE_ZIP_ENTRY` + 枚举里同名出现 2 次（T090） |
| `nonZipFileIsRejectedAsContainerInvalid` / `missingFileIsReportedAsSourceUnavailable` | 受控失败，不返回空书 |
| `corruptedCentralDirectorySignatureIsRejected` / `truncatedEndOfCentralDirectoryIsRejected` | 结构损坏受控失败（T090） |
| `utf8BomIsStripped` / `utf16LittleEndianWithBomIsDecoded` / `declaredNonUtf8EncodingIsHonouredInsteadOfGuessed` | BOM 与声明编码按规则处理（3.2 第 1 条） |
| `malformedUtf8IsReportedForTheSpecificDocumentInsteadOfReplaced` | 非法 UTF-8 点名 `OEBPS/Text/bad.xhtml`，**不产生 U+FFFD** |
| `undeclaredUtf16IsRejectedInsteadOfBecomingMojibake` / `unsupportedDeclaredEncodingIsRejectedNotIgnored` | 不猜、不静默降级 |
| `readEntryTextSurfacesDecodingFailureForThatEntry` / `rawBytesAreAvailableForBinaryEntries` | 文本路径报错、二进制路径原样返回 |
| `closedContainerRefusesFurtherReads` | 关闭后再读抛 `IllegalStateException` |

命令与结果：

```powershell
pwsh -NoProfile -File tools\verify.ps1
→ BUILD SUCCESSFUL in 1m 27s
→ verifyKotlinStyle 0 违规
→ 510 项测试 / 0 失败 / 0 错误 / 84 套（本轮 +26）
→ APK 29,100,022 字节，2026/9/10 21:47
```

---

## 4. S3 完成矩阵（本批更新）

| S3 条目 | 状态 | 实现文件 | 验证 | 剩余问题 |
|---|---|---|---|---|
| 4.2 `EpubResourceResolver` | **PASS** | `EpubResourceResolver.kt` | 20 项 | — |
| 4.3 `EpubNavigationParser` | **PASS** | `EpubNavigationParser.kt` | 11 项 | — |
| 4.1 `EpubPackageParser`（container.xml + OPF） | **PASS** | `EpubPackageParser.kt` | 8 项 | — |
| 4.1 ZIP 容器 IO 适配（枚举/上限/重复条目） | **PASS** | `EpubZipContainer.kt` | 26 项 | 20,000 与 512MiB 为**结构级**验证：真实造出 20,001 条目被拒；未在单测里分配 512MiB 内存 |
| 4.1 四组件接线（container → OPF → nav） | **PASS** | — | `stepOneToThreeWiring…` | — |
| 4.5 `EpubDocumentParser` + `StructuredDocument` | **NOT_RUN** | — | — | **最大缺口**：段落/标题/inline span/图片对象/列表/引用/表格/ruby/pre/脚注 |
| 4.6 资源对象化 + `ImageLayoutSpec` | **NOT_RUN** | — | — | resourceId 落盘、解码状态、测量统一 |
| 3.2 规范源文本 v1 + golden（含 14.1 最小源文本 golden） | **NOT_RUN** | — | — | 十条生成规则；`decodeText` 已就绪但块级规则未实现 |
| 4.4 TXT `HeadingKind` / 卷序 section | **NOT_RUN** | — | — | — |
| `ReadingManifestBuilder` | **NOT_RUN** | — | — | 依赖 DocumentParser |
| `PublicationStore` | **NOT_RUN** | — | — | S2 的 `activateRevision()` 已就绪待接 |
| **既有路径替换**：`BookParser.parseEpubStream` 改用新链路 | **NOT_RUN** | — | — | 计划第 16 节第 2 条硬性检查项 |
| Gate G3 整体 | **PARTIAL** | — | — | 「导入结果 golden / 解析两次稳定 / 主线覆盖无缺失无重复」需 DocumentParser + ManifestBuilder 完成后才能判 |

**风险（与上一批相同，必须继续记录）**：容器/OPF/目录/路径四个组件目前**仍只被测试调用**，
`BookParser.parseEpubStream` 依旧走旧的 `htmlToFormattedText` + `[IMG:]`。用户可见行为与上一版一致。
S3 的收尾动作必须是把导入链路切过去，这依赖 `EpubDocumentParser` + `ReadingManifestBuilder`。

**新增差异记录（不改需求，只登记）**：计划第 16 节的错误码清单没有容器/包结构类错误码，
本批补充了 `ContentErrorCode.CONTAINER_INVALID`（域模型枚举新增一项，无破坏性变更）。

---

## 5. 下一批（S3 第 4 批，按依赖顺序）

1. `EpubDocumentParser`：XHTML → `StructuredDocument` 块模型（Paragraph/Heading/InlineSpan/Image/Figure/List/Quote/Table/Ruby/Preformatted/Footnote/Unsupported）
   ＋ 3.2 规范源文本 v1 的十条规则 ＋ 锚点索引 ＋ `resourceId = SHA256(containerPath + contentHash)`。
   必须先落地 14.1 的**最小源文本 golden**：`<p id="p1">甲<strong>乙</strong>😀<img src="../Images/a+b.png"/>丙</p>`
   → `甲乙😀\uFFFC丙\n`，UTF-16 长度 7，有效字 4，图对象 `[4,5)`，丙 `[5,6)`，强调 `[1,2)`（T002）。
   XML 嵌套深度 128 / 元素数量上限在这一层执行并测试。
2. `ReadingManifestBuilder`：逻辑 section 边界（4.3）、主线去重（10.2）、每 section 权重。
3. `PublicationStore`：staged 写入 → `activateRevision()` → `books.contentState = READY`。
4. `BookParser` 切换 + FX03/FX04 导入 golden。
