# FlowReader 竞品调研与迭代路线（v0.0.6 基线）

> 调研日期：2026-09 · 基线版本：`0.0.6`（versionCode 5657）
> 数据来源：各项目 GitHub 仓库 README / 官网功能页（下文链接）。掌阅、微信读书、番茄小说、Moon+ Reader、ReadEra 为闭源产品，其能力来自官方页面与产品实测认知，非源码级确认。

---

## 一、调研对象

| 类别 | 产品 | 开源 | 备注 |
|---|---|---|---|
| 国产商业阅读器 | 掌阅 iReader、微信读书、番茄小说、起点读书 | ❌ | 排版/听书/社交生态的标杆 |
| 国际商业阅读器 | Moon+ Reader、ReadEra、静读天下 | ❌ | 排版可调项、词典/翻译最全 |
| 跨平台开源 | Readest、Koodo Reader、Anx Reader | ✅ | AI、同步、听书、标注工作台 |
| 电子墨水开源 | KOReader | ✅ | 词典、重排、插件、e-ink 优化 |
| 安卓老牌开源 | Librera、CoolReader、FBReader | ✅ | 格式覆盖、TTS 通知栏、Android Auto |
| 中文网络小说开源 | Legado（阅读 3.0）、NovelReader | ✅ | 书源、换源、净化规则、朗读 |
| 精炼型开源 | Book's Story、Episteme、FlowReader | ✅ | 现代 Compose、隐私优先 |
| 漫画开源 | Mihon、Kotatsu | ✅ | 阅读器手势、双页、Webtoon 模式 |

主要来源：
- [anxcye/anx-reader](https://github.com/anxcye/anx-reader)（8.8k★，Flutter，MIT）
- [readest/readest](https://github.com/readest/readest)（24.2k★，AGPL-3.0，Foliate 重写）
- [koreader/koreader](https://github.com/koreader/koreader)（29.6k★，AGPL-3.0）
- [troyeguo/koodo-reader](https://github.com/troyeguo/koodo-reader)（28.1k★，AGPL-3.0）
- [foobnix/LibreraReader](https://github.com/foobnix/LibreraReader)（4.8k★）
- [johnfactotum/foliate-js](https://github.com/johnfactotum/foliate-js)
- [mihonapp/mihon](https://github.com/mihonapp/mihon)（23.5k★）、[KotatsuApp/Kotatsu](https://github.com/KotatsuApp/Kotatsu)（8.8k★）
- [gedoor/legado](https://github.com/gedoor/legado)（47.1k★）
- [Moon+ Reader 官网](https://www.moondownload.com/)、[ReadEra 官网](https://readera.org/)
- [掌阅科技官网](https://www.zhangyue.com/)（仅企业介绍；产品功能为公开资料与实测认知）

---

## 二、竞品能力矩阵（只列对 FlowReader 有参考价值的维度）

图例：✅ 有 / 🟡 部分 / ❌ 无

| 能力 | 掌阅 | 微信读书 | Moon+ | ReadEra | KOReader | Anx | Readest | Koodo | Legado | **FlowReader 0.0.6** |
|---|---|---|---|---|---|---|---|---|---|---|
| 排版可调（字号/行距/段距/首行缩进） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **页边距 / 每行字数可调** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| **对齐方式（两端/左/居中）** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌（写死 Justify） |
| 字重 / 伪粗体 / 字距 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡 | 🟡（仅字体族） |
| 内置多字体 + 外部字体导入 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（11 内置 + ttf/otf） |
| 主题/色板 + 自定义颜色 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（18 色板，WCAG AA 断言） |
| **色温 / 护眼滤蓝光** | ✅ | ✅ | ✅ | 🟡 | ❌ | ✅ | ✅ | 🟡 | 🟡 | ❌ |
| 背景纹理 | ✅ | 🟡 | ✅ | 🟡 | ❌ | ✅ | 🟡 | 🟡 | 🟡 | ✅（4 套纸质纹理） |
| 翻页模式（滚动/分页） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **翻页动画（覆盖/仿真/卷曲）** | ✅ | ✅ | ✅（5 种） | 🟡 | ❌ | 🟡 | 🟡 | 🟡 | ✅ | ❌（已移除入口） |
| **自动翻页 / 自动滚动** | ✅ | ✅ | ✅（5 模式） | ❌ | ✅ | ❌ | 🟡 | ✅ | ✅ | ❌ |
| 手势全可配 + 边缘热区 | ✅ | ✅ | ✅（24 操作） | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | ✅ | ✅ |
| 音量键翻页 | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 页脚状态（时间/电量/进度） | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | 🟡 | 🟡 | ✅ | ✅ |
| 进度拖动气泡 HUD | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 目录 / 书签 / 高亮 / 笔记 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 全库 & 书内搜索 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（FTS5） |
| **离线词典查词** | ✅ | ✅ | ✅ | ✅ | ✅（StarDict） | 🟡 | ✅（Yomitan/RDICT） | ✅（MDX） | ✅ | ❌ |
| **划词翻译 / 全文翻译** | ✅ | ✅ | ✅ | ✅ | 🟡 | ✅（并排） | ✅（DeepL/Yandex） | ✅ | ✅ | ❌ |
| **简繁转换** | ✅ | ✅ | 🟡 | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ |
| **竖排（右起）** | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | 🟡 | ❌ |
| **TTS 听书（倍速/音色/定时/后台）** | ✅ | ✅（AI 音色） | ✅ | 🟡 | ✅ | ✅ | ✅ | ✅ | ✅ | 🟡（仅播放/暂停） |
| 有声书 / 朗读对齐高亮 | ✅ | ✅ | 🟡 | ❌ | 🟡 | ✅ | ✅（EPUB3 Media Overlays） | ✅ | ❌ | ❌ |
| **WebDAV / 云同步（进度+笔记+书）** | ✅ | ✅ | ✅ | ❌ | 🟡 | ✅ | ✅ | ✅（12 种） | ✅ | ❌ |
| 阅读统计（时长/页数/热力图） | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅（热力图） | 🟡 | ✅ | 🟡 | ✅（柱状/周月报，无热力图） |
| **AI 助手（摘要/人物/脑图/翻译）** | ✅ | ✅ | ❌ | ❌ | ❌ | ✅ | 🟡（规划） | ✅ | 🟡 | ❌ |
| OPDS | 🟡 | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ | ✅（可作服务端） | ✅ | ✅（客户端，仅内网） |
| Calibre 集成 | 🟡 | ❌ | 🟡 | ❌ | ✅ | ✅（社区插件） | ✅ | ✅ | 🟡 | ❌ |
| **本地目录自动扫描 / 监听** | ✅ | ❌ | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | ✅ | ❌（仅 SAF 选择） |
| 书单 / 分组 / 系列 / 标签 | ✅ | ✅ | ✅ | ✅（收藏/待读/已读） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（分组 + 书单 + 标签） |
| **应用锁（指纹/密码）** | ✅ | ✅ | ✅ | ❌ | 🟡 | 🟡 | 🟡 | ✅ | ✅ | ❌ |
| 桌面小组件 | ✅ | ✅ | ✅ | 🟡 | ❌ | 🟡 | ❌ | 🟡 | ✅ | ✅（单个） |
| 阅读进度通知栏 / 锁屏 | ✅ | ✅ | ✅ | 🟡 | 🟡 | ✅ | 🟡 | 🟡 | ✅ | ❌ |
| **TXT 净化 / 自定义分章正则** | ✅ | 🟡 | ✅ | 🟡 | 🟡 | 🟡 | 🟡 | 🟡 | ✅ | 🟡（内置智能检测，不可自定义） |
| 漫画：双页 / Webtoon / RTL | 🟡 | 🟡 | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | ✅ | 🟡（横滑 + 纵缝） |
| PDF：裁边 / 重排 / 标注 | ✅ | 🟡 | ✅ | ✅ | ✅（K2pdfopt） | 🟡 | ✅ | ✅ | 🟡 | 🟡（缩放 + 框选标注） |
| 无障碍（TalkBack/键盘/读屏） | ✅ | ✅ | 🟡 | 🟡 | ✅ | 🟡 | ✅ | 🟡 | 🟡 | 🟡（部分 semantics） |
| 平板 / 折叠屏 / 双栏 | ✅ | ✅ | ✅ | 🟡 | ✅ | ✅ | ✅ | ✅ | 🟡 | 🟡（≥600dp 用 NavigationRail） |
| 隐私（无账号/无上报） | ❌ | ❌ | 🟡 | 🟡 | ✅ | 🟡 | ✅ | ✅ | ✅ | ✅（最强项） |

---

## 三、FlowReader 现状盘点（代码级确认）

**已经做得很好的（不要动）**
- 隐私与安全边界：单 `INTERNET` 权限、无 WebView、无账号、无上报；导入处处有大小/路径上限；ContentProvider 只读元数据；FileProvider 仅暴露 `share_cards/`。
- 排版引擎：真实分页（逐页测量）+ 滚动 + 无动画三模式；12 色板 + 自定义色，全部 WCAG AA 断言；纸质纹理；智能 TXT 分章（百千万/卷/序章/番外）；FTS5 全库搜索。
- 阅读页细节（0.0.3–0.0.6 新增）：页脚时间/电量/进度、拖动居中 HUD、单击即开合控制栏（无 300ms 双击等待）、音量键翻页、护眼提醒、自动夜间、沉浸模式。
- 书架：分组（可命名/重命名/删除/批量移入/添加已有书籍）、书单、标签、双视图 + 每行本数 + 封面大小、分组隐藏、已读直进阅读页。
- 数据：备份/恢复（原子事务）、书架 CSV/JSON 导出、局域网互传、笔记导出 MD/HTML/TXT、阅读统计与目标、Widget、决策转盘。
- 工程：ktlint + 空白门禁、71% 文件级测试广度门禁、APK 体积基线、Room 手写迁移、`verifyKotlinStyle`/`coverageSummary`/`performanceBaseline` 三件套。

**代码级确认的空白（下文 P0/P1/P2 的来源）**
- `ReaderMetrics.contentWidthDp()` 把行宽硬编码为 34 个汉字，页边距是 `FlowSpacing.lg` 常量 → **页边距/每行字数不可调**。
- `readerBodyStyle()` 里 `textAlign = TextAlign.Justify` 写死 → **对齐方式不可调**。
- 全仓 `webdav` 0 命中 → **无任何同步能力**。
- 全仓 `dictionary` 0 命中 → **无词典**；`translate` 命中全是 Compose 的 `translate()` 绘图调用 → **无翻译**。
- `TtsManager` 只有 `speak/pause/stop/shutdown`，无 `setSpeechRate`/`setPitch`/音色选择/MediaSession/通知 → **听书是最弱的环节**。
- `autoScroll` 0 命中 → **无自动翻页/滚屏**。
- 无 `simplified/traditional` 转换、无 `heatmap`、无 `biometric`。

---

## 四、迭代建议（按优先级，含实现要点）

### P0 — 立刻能做，收益/成本比最高（建议 0.1.x，1–2 周）

| # | 功能 | 为什么 | 实现要点 | 工作量 |
|---|---|---|---|---|
| P0-1 | **页边距 + 每行字数** | 竞品全都有；当前 34 字上限对手机/平板都不理想 | `ReadingSettings` 加 `horizontalMarginDp: Int`、`lineChars: Int`；`ReaderMetrics.contentWidthDp()` 接收两者；`ReaderContent`/`PagedReader` 的 `padding(horizontal=…)` 改为设置值；排版页加两个滑块（复用 `DebouncedSlider`） | 0.5d |
| P0-2 | **对齐方式** | 中文小说左对齐更常见；Justify 会让稀疏行拉开字距 | `ReadingSettings.textAlign: ReaderTextAlign`（两端/左/居中）；`readerBodyStyle()` 读它；单测断言默认值 | 0.2d |
| P0-3 | **听书增强（TTS v2）** | 掌阅/微信读书/Anx 的听书是核心留存点；现在是「播放/暂停」 | `TtsManager` 加 `setSpeechRate(0.5–2.0)`、`setPitch`、`voice` 列表、`UtteranceProgressListener` 已有；`MediaSessionCompat` + `MediaStyle` 通知（封面/进度/上一章下一章）；`sleepTimer` 用 `Job`+`delay`；朗读进度与文本高亮同步（`onRangeStart`）；锁屏/耳机键（`MediaButtonReceiver`） | 3–4d |
| P0-4 | **自动翻页 / 自动滚动** | 掌阅、Moon+（5 模式）、Legado 都有；躺读/听读场景刚需 | 阅读页加「自动翻页」开关 + 速度滑块（秒/页、像素/秒）；PAGED 用 `LaunchedEffect` + `delay` 调 `scrollToPage`；SLIDE 用 `animateScrollBy`；到章尾自动进下一章；滚动/触摸即暂停 | 1d |
| P0-5 | **简繁转换** | 中文小说资源繁体/简体混杂，Anx/Koodo/Legado 都有一键转换 | 内置 OpenCC 精简映射表（约 3–5k 字对，`assets/opencc/`），`TextConverter` 纯函数 + 单测；排版页开关；只作用于渲染层，不改原文 | 1.5d |
| P0-6 | **书架排序与筛选增强** | 竞品可按时长/进度/格式/系列排序 | `SortOrder` 加 `PROGRESS`/`FILE_SIZE`/`FINISHED`；筛选：未读/在读/已读完/有笔记；复用现有 `sortOrder` 状态与溢出菜单 | 0.5d |
| P0-7 | **重复书籍检测与清理** | 0.0.6 已让导入幂等，但历史数据可能已有重复 | `BookDao.findDuplicates()`（同 filePath 或同 title+fileSize 分组）；设置→数据里加「查找重复书」入口，展示后一键合并（保留进度最靠前的一条） | 0.5d |
| P0-8 | **TalkBack 审计 + 字号跟随系统** | 无障碍是 Readest 明确宣传的差异点 | 逐个审计阅读页/书架/设置的 semantics（翻页、控制栏、进度条 `progressBarRangeInfo`）；`ReaderFooter` 加 `contentDescription`；支持系统字体缩放（`fontScale`） | 1d |

### P1 — 中期（0.2.x，4–8 周）

| # | 功能 | 为什么 | 实现要点 | 工作量 |
|---|---|---|---|---|
| P1-1 | **WebDAV 同步（进度 + 笔记 + 书签）** | 最大的能力缺口：Anx/Readest/Koodo/Moon+/Librera 全靠它跨设备；FlowReader 的隐私立场恰好可以只支持「用户自建 WebDAV」 | 不引入账号：`WebDavClient`（OkHttp，`PROPFIND/PUT/GET/MKCOL`）；冲突策略用「最后写入胜 + 每本书一个 JSON」；同步内容只含 `books` 元数据 + `bookmarks/annotations` + 进度，**不含正文文件**（可选）；设置→数据新增「WebDAV 同步」子页（地址/用户名/应用密码/测试连接）；`WorkManager` 定时同步；端到端用 MockWebServer 单测 | 5–8d |
| P1-2 | **离线词典查词** | KOReader/ReadEra/Moon+/Readest 的标配；读英文/生僻字刚需 | StarDict `.dict/.idx` 或 MDX 解析（放 `:core`，纯 Kotlin，可单测）；划词后浮动栏加「查词」；词库由用户从设置导入（放 `filesDir/dictionaries/`）；结果面板支持复制/高亮生词 | 4–6d |
| P1-3 | **色温 / 护眼滤蓝光** | 掌阅/Moon+/Anx 都有；比换色板更实用 | 用 `Window.setAttributes` 无法做色温，改为在 `ReaderBackground` 之上叠一层可调 alpha 的暖色 `Color(0xFFFFB74D)` 覆盖层（0–40%）；或 `RenderEffect`（API 31+）着色；设置放「护眼与屏幕」 | 1d |
| P1-4 | **翻页动画（覆盖 / 平移）** | 掌阅的仿真翻页是记忆点；当前只有瞬时切换 | 先做**覆盖翻页**（新页从右滑入 + 左侧投影，`HorizontalPager` 自定义 `PageTransformer` 等价实现：`graphicsLayer{translationX}`）；再评估仿真卷曲（`Canvas` 贝塞尔 + 阴影，成本高、低端机风险大）；保持「无动画」可选 | 3–5d |
| P1-5 | **TXT 净化规则 + 自定义分章正则** | Legado 的净化规则是网文阅读体验的分水岭 | `TxtChapterDetector` 已支持内置规则；再加用户可编辑的「分章正则 + 净化规则（删除行/替换/去空行）」列表，存 DataStore，导入时可选「按规则重新分章」；提供 3 个预设（网文/出版/日轻） | 2–3d |
| P1-6 | **书签角标 + 快捷书签** | ReadEra 的「点右上角加书签」很受欢迎 | 阅读页右上角 40dp 热区单击加书签并显示角标；`ReaderControls` 增加书签角标按钮；书签列表按章排序（已有） | 0.5d |
| P1-7 | **进度通知栏 / 锁屏卡片** | 掌阅/Anx/Librera 都有；配合 P0-3 的 MediaSession 顺手做 | `MediaSessionCompat` 已因听书引入；无听书时用低优先级前台通知显示「书名 + 进度 + 继续阅读」；可关闭 | 1d |
| P1-8 | **应用锁（指纹 / PIN）** | Kotatsu、Moon+、Koodo 都有；隐私型阅读器常被要求 | `BiometricPrompt`（`androidx.biometric`）；`AppLock` 状态存 DataStore；`MainActivity` 启动时校验；设置→外观下加开关 | 1d |
| P1-9 | **本地目录自动扫描（可选目录）** | ReadEra/KOReader/Librera 的「无感导入」 | 用 `ACTION_OPEN_DOCUMENT_TREE` 拿持久化目录权限；`DocumentFile` 递归扫描支持的扩展名；`WorkManager` 定时 + 手动「立即扫描」；与幂等导入共用 `importSingle` | 2–3d |
| P1-10 | **统计热力图 + 单书统计** | Anx 的热力图很有辨识度 | 复用 `reading_stats` 表按天聚合，`Canvas` 画 7×N 格子（沿用 `ReaderFooter` 的 Canvas 风格）；书籍详情页加「这本书的阅读统计」 | 1.5d |

### P2 — 长期（0.3.x+，按需）

| # | 功能 | 说明 | 风险/取舍 |
|---|---|---|---|
| P2-1 | **划词翻译 / 全文翻译** | 走「用户自带 API Key」（DeepL/百度/腾讯/OpenAI 兼容端点），不内置任何免费服务 | 需要网络权限扩展与 Key 安全存储（`EncryptedSharedPreferences`）；无 Key 时降级为系统 `ACTION_PROCESS_TEXT` |
| P2-2 | **AI 助手（摘要/人物/脑图）** | Anx/Readest/Koodo 的方向；对本地小说价值高 | 同样 BYO key；把「章节摘要/人物关系」做成可缓存的本地笔记，避免每次调用；注意隐私声明 |
| P2-3 | **竖排（右起）** | Koodo 支持；中文古籍/日轻场景 | Compose 无原生竖排，需要 `TextLayoutResult` 自绘或 `vertical-writing` 方案，成本高，优先级低 |
| P2-4 | **平板/折叠屏双栏** | ReadEra/掌阅都支持 | `contentWidthDp` 已有列宽上限，双栏可在 `≥840dp` 时把章节拆成两列 |
| P2-5 | **笔记同步到 Readwise/Notion/Obsidian** | Koodo 有 | 需要 OAuth/API，属于「云」范畴，与隐私立场冲突，建议只做「导出到本地 Obsidian 目录」 |
| P2-6 | **KOReader / kosync 进度互通** | Readest/Koodo 支持 | 协议简单（`PUT/GET /syncs/progress`），可作为 P1-1 的补充 |
| P2-7 | **Calibre 集成** | KOReader/Readest/Anx 有 | 需要 `content server` 或 OPDS-over-Calibre；OPDS 客户端已具备，可先做「Calibre 库 OPDS 一键添加」 |
| P2-8 | **PDF 裁边 / 重排** | ReadEra/KOReader 的强项 | 裁边可用 `PdfRenderer` + 白边检测；重排要引入 k2pdfopt，体积与复杂度都大，建议只做裁边 |
| P2-9 | **漫画双页 / Webtoon 模式** | Mihon/Kotatsu 的阅读器细节 | 横屏双页、`pageGap`、RTL 阅读方向；工程量小，可与 P1-4 一起做 |
| P2-10 | **听书/有声书（音频对齐）** | Readest 的 EPUB3 Media Overlays | 需要解析 SMIL，长尾需求 |

---

## 五、UI / 交互细节清单（照着改就能提升「精致度」）

**阅读页**
1. 页脚三要素（时间/电量/进度）改为**可单独开关**，并支持「只在暂停 5s 后淡入」。
2. 控制栏顶部加**当前章节进度条**（细线，2dp），比数字更直观。
3. 目录弹窗：当前章高亮 + 顶部「搜索章节」输入框 + 分组/卷折叠（长书必备）。
4. 进度滑块拖动时，除居中 HUD 外，底部控制栏同步显示「本章第 x/y 页」。
5. 亮度面板加**左右边缘上下滑动调亮度**的手势（Moon+/ReadEra 都有），并在设置里可关。
6. 夜间模式下图片自动降亮度（避免刺眼白图）。
7. 选中文本的浮动栏增加「查词/翻译/朗读这段」入口（P1-2/P2-1 的落地位置）。
8. 书签角标 + 「本章已读」细线标记。
9. 章节首尾页显示「上一章/下一章」大按钮热区（减少误触分页）。

**书架**
10. 分组页加**拖拽排序**与「移动到…」长按菜单（现在只有移入分组）。
11. 书卡显示**阅读进度环**而非直线条（掌阅风格，辨识度更高）。
12. 顶部「继续阅读」大卡回归为**可选**（现在只在独立 tab）；书架上留一个可关闭的「继续阅读」横条。
13. 封面自定义（拍照/相册选图）——ReadEra/Moon+ 都有；现在只有程序化生成。
14. 批量操作增加「加入书单」「标记已读/未读」。
15. 搜索页支持按「书名/作者/正文」分段结果（FTS5 已支持，只差 UI 分组）。

**设置**
16. 每个二级页顶部加一句**当前值摘要**（已做）；再加「恢复默认」按钮。
17. 数据页显示**存储占用**（书籍/封面/缓存分项）与「清理缓存」。
18. 关于页加「更新日志」与「隐私说明」入口（应用商店合规也会用到）。

**动画与反馈**
19. 统一动效时长（`FlowMotion` 已有常量），控制栏 160ms、页面切换 240ms、弹窗 200ms。
20. 所有破坏性操作（删除分组/删除书）走**撤销 Snackbar**而不是确认弹窗（更快）。

---

## 六、明确不做（守住产品边界）

- ❌ 账号体系 / 社交 / 书评 / 排行榜
- ❌ DRM 破解（MOBI/AZW 一律拒绝）
- ❌ 广告、埋点、崩溃上报
- ❌ 内置书城 / 书源爬取（Legado 的定位不同，且法律风险）
- ❌ 免费云服务（只做用户自建 WebDAV / 局域网）
- ❌ 内置免费翻译/AI 服务（只做 BYO key）

---

## 七、建议的落地顺序（可直接开工）

**第 1 批（0.1.0）**：P0-1 页边距/每行字数 → P0-2 对齐 → P0-4 自动翻页 → P0-5 简繁转换 → P0-3 听书 v2
**第 2 批（0.1.1）**：P0-6 排序筛选 → P0-7 重复书清理 → P1-6 书签角标 → P1-3 色温
**第 3 批（0.2.0）**：P1-1 WebDAV 同步 → P1-2 离线词典 → P1-4 覆盖翻页
**第 4 批（0.2.x）**：P1-5 TXT 净化规则 → P1-9 目录自动扫描 → P1-8 应用锁 → P1-10 热力图
**按需（0.3+）**：翻译 / AI / 竖排 / 双栏 / 漫画双页 / PDF 裁边

每批都沿用现有门禁：`verifyKotlinStyle` + `testDebugUnitTest` + `coverageSummary`（新增领域模型必须配测试）+ `assembleDebug`，并 bump `versionCode`/`versionName`。
