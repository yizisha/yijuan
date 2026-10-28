# 下一批（S5 收尾）：列表 / 表格 / 注音 + SVG 接入 — 可执行规格

编写于 0.2.13（versionCode 5686）交接时。**本文件是给下一位执行者的规格**，
不是报告；执行完请把这批的结果写成 `0.2.14-report-*.md` 并回填 HANDOFF。

---

## 0. 用户已明确的三条决定（2026-09-12，不要再问）

1. **Coil 解码并发保持"全应用 2 并发"** —— 不要改成只限制阅读器。
2. **列表 / 表格 / 注音（ruby）：现在就做**（本批 A）。
3. **SVG：已授权新增依赖**（`io.coil-kt:coil-svg`），见第 5 节（本批 B）。

---

## 1. 已核实的事实（不必重查）

| 事实 | 含义 |
|---|---|
| 规范正文里**没有**列表标记（`emitListItem` 不写项目符号/编号） | 编号只能由**结构**推导 |
| 表格单元格之间只有 **TAB**（`TextEmitter.emitTab`） | 正文里没有表头/跨行跨列信息 |
| ruby 的 `rt/rp` 注音**不进主文本**（3.2 第 6 条） | 正文里根本没有注音内容 |
| 这些语义在解析器的 `ListBlock`/`TableBlock`/`RubyBlock` 里**齐全**，只是**没有持久化** | 与 0.2.9 修图片前一模一样的问题 |

**照抄图片那条已验证的链路即可**（0.2.9 → 0.2.13 已经跑通并踩完坑）：

```
解析器在唯一创建点收集 → 文档 JSON 落盘数组 → PublicationStore 读回
→ 纯函数把文档偏移换成章内偏移 → 只读当前 section 的文档（带缓存）
→ ViewModel 在发布章节状态**之前**取好 → 两个渲染器按偏移注入
```

### 1.1 直接参考的文件

| 关注点 | 文件 |
|---|---|
| 结构块模型 | `domain/.../StructuredDocument.kt`（ListBlock/ListItem/TableBlock/TableRow/TableCell/RubyBlock；`images` 是 v2 的先例；`CURRENT_SCHEMA_VERSION = 2`） |
| 解析器收集点 | `app/.../util/epub/EpubDocumentParser.kt`：`emitList`(532) / `emitListItem`(570) / 表格(~`emitTab` 781) / `emitRuby`；`WalkContext.images` 是收集先例 |
| 落盘/读回 | `app/.../util/epub/PublicationStore.kt`：`buildDocumentJson`（写 `images`）、`readDocumentImages`、`readPackageIndex`、`collectImages` |
| 偏移换算 | `app/.../util/epub/ChapterImageIndex.kt`（纯函数，跨文档 section 按前序 range 长度平移） |
| 只读当前 section | `app/.../util/epub/ChapterImageResolver.kt`（包索引 + 文档级 LRU） |
| 切分共用 | `app/.../ui/screens/reader/components/ReaderImageSegments.kt`（`ReaderSegment` + `splitParagraphForImages`） |
| 分页渲染 | `PagedReader.kt`：`buildChapterBlocks`、`measureImageAwareText`/`isImageMarkerFragment`、`PageContent` 的图片分支 |
| 滚动渲染 | `ReaderContent.kt`：`splitParagraphForImages(...).forEachIndexed { ... }`（含 `registerScrollBlock`/`DisplayMapping`） |
| 取数时点 | `ReaderViewModel.kt`：`chapterImages` 状态 + `loadChapterImages`（在 `_uiState.update` **之前** await） |
| 成本查询 | `ChapterDao.getChapterTextLengths`（一次聚合，不读正文） |

---

## 2. schema v3：把标注写进文档 JSON

在 `buildDocumentJson` 里与 `images` 并列增加三个数组（命名/风格与 `images` 保持一致）：

```json
"lists": [
  { "blockId": "...", "domPath": "...", "offsetUtf16": 123,
    "ordered": true, "listStart": 1, "level": 1, "itemValue": null, "role": "MAIN" }
],
"tables": [
  { "blockId": "...", "domPath": "...", "startUtf16": 0, "endUtf16Exclusive": 500,
    "rows": [ { "cells": [ { "isHeader": false, "rowSpan": 1, "colSpan": 1,
                             "startUtf16": 0, "endUtf16Exclusive": 12 } ] } ] }
],
"ruby": [
  { "blockId": "...", "domPath": "...", "baseStartUtf16": 10, "baseEndUtf16Exclusive": 12,
    "annotations": ["かん", "じ"] }
]
```

**硬性要求**

1. **列表编号必须能"续页不重置"**：编号由 `listStart`（默认 1）+ **同一列表内前面同层项的个数**推导；
   **绝对不要**把数字写进正文，也不要按页/按块重新计数 —— G5 明确要求"长列表续页编号不重置"。
2. **不得改动主文本偏移与字数口径**：ruby 只加显示层注音；U+FFFC/表格 TAB 的字数规则不动（3.2）。
3. `CURRENT_SCHEMA_VERSION` 2 → **3**。`BookParser` 已把该值算进 revision，
   所以**重新导入同一本书会生成新 revision**（旧目录按 5.3 保留、不覆盖）——
   这就是既有书拿到标注的路径。**必须**在报告与「关于」页说明"需重新导入一次"。
4. 旧格式（没有这三个数组）读回时一律返回**空列表**，**不抛异常**。

---

## 3. 改动清单（建议顺序）

1. `StructuredDocument`：加三个文档级数组字段（带默认值，保持既有构造可用）。
2. `EpubDocumentParser`：在**唯一创建点**收集（`WalkContext.lists/tables/ruby`），
   与 `images` 完全同构 —— **不要**靠遍历 blocks 反推（0.2.9 之前就是这样漏掉行内图的）。
3. `PublicationStore`：`buildDocumentJson` 写三个数组；新增读回方法（可合并为 `readDocumentMarks(jsonPath)`，
   一次文件读取同时返回 images + lists/tables/ruby-**避免重复读同一个文档**）。
4. 新 `ChapterMarkResolver`（或扩展 `ChapterImageResolver` 为 `ChapterContentResolver`）：
   - 复用 `ChapterImageIndex` 的换算算法把文档偏移 → **章内偏移**（跨文档 section 必须平移）；
   - 按 (publicationId, revision, sectionOrdinal) 缓存；只读当前 section 涉及的文档；
   - **与图片共用同一次文档读取与同一份缓存**。
5. `ReaderViewModel`：`chapterImages` 旁新增 `chapterMarks`，仍在发布章节状态**之前**取。
6. 两个渲染器按偏移注入（见第 4 节）。
7. 测试：见第 6 节。

---

## 4. 渲染建议（按风险从低到高）

**列表**：在块文本前注入显示前缀（`• ` / `1. ` / 缩进），
但前缀必须映射到**该项的源起始偏移**——参考 `ReaderTextMapping.buildParagraphContent` 里
人工缩进前缀的处理（`appendRange(prefix, paragraphStart)`），否则会重演
"人工缩进导致整段源坐标 +1"那个已修缺陷。

**表格**：先做**列对齐**（把 TAB 渲染成可见的固定间距），再做真正的表格绘制。
注意单元格文本在正文里是 TAB 分隔的**连续文本**，任何替换都必须维持
`rawOffsets`（display → raw）**1:1 或显式映射**，否则批注/选择会错位。

**注音**：小字注音在基字上方/下方。若时间不足，**至少**用可访问文本呈现"基字（注音）"，
并在报告里**明确标注这是降级**，不得声称 Gate G5 已通过。

---

## 5. SVG 接入（**已授权**，按 §4.6 第 4 条执行并留证）

1. 依赖：`io.coil-kt:coil-svg:2.7.0`（必须与已用的 `coil-compose:2.7.0` **同版本**）。
2. ⚠️ **前置事实**：本机 Gradle 缓存里**没有** `coil-svg`，也没有 `androidsvg`
   （已核对 `%USERPROFILE%\.gradle\caches\modules-2\files-2.1\io.coil-kt`）。
   若构建解析失败（离线），**停下并报 BLOCKED**，不要静默跳过、不要伪造通过。
3. 接入点：`FlowReaderApplication.newImageLoader()` 里加
   `components { add(coil.decode.SvgDecoder.Factory()) }`，
   **保留**现有的 `min(64MiB, heap/8)` 内存预算与 2 并发 dispatcher（用户已确认保持全应用）。
4. 安全边界（§4.6 第 5 条）：静态 SVG 里的**脚本与外部资源一律不得执行或自动下载**；
   需要实测（造一个含 `<script>` 与外链 `xlink:href` 的夹具，确认不发请求、不执行）。
5. 验收夹具：用 `app/.../util/epub/EpubFixtureBuilder.kt` 造含静态 SVG 的 EPUB，
   走真实导入 → 分页/滚动两模式都能看到图。
6. **spike 报告必须包含**：artifact 坐标与版本、全部传递依赖及版本、许可证、
   网络行为（是否联网）、**APK 增重**（接前/接后的字节数对比，以及 `app-debug.apk` 大小变化）。

---

## 6. 测试与门禁（本批必须新增）

| 测试 | 断言 |
|---|---|
| 标注落盘/读回 | `lists`/`tables`/`ruby` 写入后能读回，字段完整；旧格式返回空且不抛 |
| 跨文档换算 | 一个 section 跨两个 XHTML 时，标注偏移按前序 range 长度正确平移（纯函数） |
| 列表编号 | **跨页/跨块连续不重置**（本批最容易写错的一条） |
| ruby | 注音**不进**主文本偏移与字数统计 |
| 表格 | 列数/表头不变；单元格 offset → display 映射保持 1:1 |
| 成本契约 | `ReaderChapterLoadCostTest` 保持绿色：打开书与换章的**正文读取次数**与章数无关，换章恰好 1 次 |
| SVG | 夹具能渲染；含脚本/外链的 SVG 不执行、不请求（可用假 URL 断言无网络） |

**流程纪律（都是本项目踩过的坑，见 HANDOFF §6）**

- 修一个分支里的缺陷时，**先写能复现的测试并证明它在旧代码上失败**，再动手（§6.22）。
- **不得**给"漏掉就会静默产出错误结果"的参数加默认值（§6.20）。
- 改任何语言的 `strings.xml` 后**必须跑一次 `assembleDebug`**（§6.24：资源合并只有 assemble 才走）。
- 安装后**必须** `am force-stop` + `am start`（§6.23）。
- 每批结束：升 `versionCode`/`versionName` + 补「关于」页更新日志 + 写报告 + 回填 HANDOFF。

---

## 7. 交付时请一并说明

- 本批**哪些做完**、**哪些没做/BLOCKED**（含原因与证据）；
- **已导入的书是否需要重新导入**（schema v3 → 需要），以及重新导入会丢失该书书签/批注
  （要提醒用户先确认，或先做 S10 的原地升级）；
- 真机**肉眼验收**结果（截图或"未验收"），不得用自动化冒充观感结论；
- APK 字节数与 SHA256、`versionCode`/`versionName`、测试总数/失败数。
