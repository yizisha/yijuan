# FlowReader 内容引擎改造 — 项目交接说明（HANDOFF）

> 本文件为历史阶段副本。当前 0.2.20 交接与验证状态请读取根目录 [HANDOFF.md](../HANDOFF.md)，下方旧门禁数字和继续开发入口不代表本版结论。

用途：统一工作区交接文档。开新对话时**只读本文件即可无缝开工**，无需翻看历史会话记录。

---

## 0. 一句话启动提示词（开新对话直接粘贴）

> 请以当前统一工作区 `C:\Users\lenovo\Desktop\心流阅读器` 为基准。请先阅读工程根目录下的 `HANDOFF.md`、`FLOWREADER_CONTENT_ENGINE_IMPLEMENTATION_PLAN.md` 以及 `docs/content-engine/reports/` 下各阶段报告。当前 S0—S3 以及 S4（包含第 1 批导航状态机与第 2 批六级容错恢复、结构化分页逆查、ViewModel/DAO 接线）已全部通过门禁（全工程 570 项单测全绿、代码风格 0 违规、APK 构建成功），请紧接着按 `HANDOFF.md` 中的「下一步入口点」推进 **Gate G4 真机交互验收 与 S5 滚动模式内容引擎接线（视口映射与双向恢复）**。每完成一个阶段请给出包含 PASS/FAIL/BLOCKED/NOT_RUN 的矩阵报告。所有必要授权已就绪。

---

## 1. 工程位置与当前环境状态

| 项 | 值 | 说明 |
|---|---|---|
| **统一工作区路径** | `C:\Users\lenovo\Desktop\心流阅读器` | **所有工程源码、文档、测试、工具统一收拢于此** |
| 备份源路径 | `C:\Users\lenovo\.dsh\agy-link\media\flowreader` | 原分散目录（已完整镜像同步，可作为冷备） |
| 分支 / HEAD | `main` / `077ea56d63070d2b93caa58eb06ca03c5ffa55c3` | **工作区含大量未提交改动，工作区文件即当前唯一权威代码** |
| 数据库版本 | Room **v10** | 7 张新内容引擎表 + 4 表新增字段，已完成平滑迁移与真机验证 |
| 工具链 | JDK 21.0.10、Gradle 9.6.1 Wrapper（`gradlew.bat`） | Android SDK: `C:/Users/lenovo/AppData/Local/Android/Sdk` |
| **关键环境约束** | **`subst R:` 虚拟盘驱动** | Windows 中文工作区（`心流阅读器`）会导致 Gradle worker 参数文件以 ANSI/GBK 写入后被 JVM 误以 UTF-8 读取产生乱码，从而导致所有测试类报 `ClassNotFoundException`。**必须使用 `subst R: 'C:\Users\lenovo\Desktop\心流阅读器'` 并从 `R:\` 驱动器运行构建**。`tools\verify.ps1` 具备自动检测与挂载机制。 |
| **门禁状态** | **全绿（ALL PASS）** | `verifyKotlinStyle` 0 违规；`testDebugUnitTest` **570 项全绿 / 0 失败 / 76 套**；`:app:assembleDebug` 成功 |
| 最新 APK | `app\build\outputs\apk\debug\app-debug.apk` | 29,142,486 字节，已同步复制到 `01_最新安装包_APK\` 供直接安装 |
| 关联测试设备 | 小米 PD2408 / V2408A，`adb` 序列号 `10AF641GRU007U7` | 本机 adb 已联通，用户真机库内保留 25 本书 / 3274 章真实数据 |

---

## 2. 核心文档与报告索引

所有核心文档已归入统一工作区内：

```
C:\Users\lenovo\Desktop\心流阅读器/ (R:\)
├── HANDOFF.md                                      <-- 本交接文件（项目顶层）
├── FLOWREADER_CONTENT_ENGINE_IMPLEMENTATION_PLAN.md <-- 内容引擎全阶段主执行计划书
├── 00_【快速导航与文件夹指引】.md                     <-- 文件夹与操作指引
├── 01_最新安装包_APK/                                <-- 最新编译打包的测试 APK
├── 02_项目计划书/                                    <-- 计划书与交接说明镜像
└── docs/
    └── content-engine/
        ├── FLOWREADER_CONTENT_ENGINE_IMPLEMENTATION_PLAN.md
        ├── AI_EXECUTION_PROMPT.md                  <-- 执行提示词模板
        └── reports/                                <-- S0~S4 已完成报告
            ├── S0-baseline-report.md               <-- S0 基线审计与 G0 缺陷复现
            ├── S1-report-and-matrix.md             <-- S1 定位模型、进度索引与会话策略
            ├── S2-report.md                        <-- S2 数据库 v9->v10 迁移与原子激活
            ├── S3-report-partial.md                <-- S3 第 1 批 (resolver)
            ├── S3-report-part2.md                  <-- S3 第 2 批 (nav / OPF)
            ├── S3-report-part3-zipcontainer.md     <-- S3 第 3 批 (Zip 容器)
            ├── S3-report-part4-documentparser.md   <-- S3 第 4 批 (结构化正文与规范源文本 v1)
            ├── S3-report-part5-manifest.md         <-- S3 第 5 批 (阅读清单与主线计量)
            ├── S3-report-part6-publicationstore.md <-- S3 第 6 批 (分片存储与落盘协议)
            ├── S3-report-part7-import-switch-and-gate-g3.md <-- S3 第 7 批 (导入链路全面切换, Gate G3)
            ├── FIX-crash-migration-index.md        <-- 真机启动闪退根因分析与彻底修复报告
            ├── S4-report-part1-navigation-coordinator.md <-- S4 第 1 批 (纯逻辑导航状态机, 17 项测试)
            ├── S4-report-part2-real-reader-and-locator-recovery.md <-- S4 第 2 批 (六级恢复、结构化分页逆查、ViewModel/DAO 接线)
            └── STATUS-S9-wiring.md                 <-- S9 统计接线现状
```

---

## 3. 已完成阶段进度总览

| 阶段 | 状态 | 关键成果与产出 |
|---|---|---|
| **S0** 基线审计 + G0 复现 | **PASS** | F01—F14 缺陷逐条审计；4 类缺陷做成可执行测试（`G0PositionAndStatsDefectTest` 等） |
| **S1** 定位模型 / 进度索引 / 会话策略 | **PASS** | `ContentLocator.kt`、`StructuredDocument.kt`、`TextUnits.kt`、`PublicationManifest.kt`、`ContentProgressIndex.kt`、`ReaderSessionPolicy.kt` |
| **S2** 存储 + v9→v10 迁移 + 原子激活 | **PASS（G2）** | 7 张新表实体（`ContentIndexEntities.kt`）、`ContentIndexDao`、`ReaderStateDao`、`MIGRATION_9_10`、测试用 Room 9.json 校验保全 |
| **S3** 完整内容包解析导入（7 批） | **PASS（G3）** | `EpubZipContainer`、`EpubDocumentParser`、`ReadingManifestBuilder`、`PublicationStore`、`PublicationImportCoordinator`、`BookParser` 切换，FX03/FX04 golden 验证 |
| **真机闪退修复** | **PASS** | 定位并修复 `BookEntity` 漏声明 `index_books_publicationId` 导致 Room 开库崩溃缺陷；补齐 `BookEntity.toDomain/fromDomain` 字段；加固迁移夹具；真机 instrumentation 测试验证通过 |
| **S4 第 1 批** 导航状态机 | **PASS** | `ReaderNavigationCoordinator.kt`：意图/命令/反馈分离、Generation 校验、INV07（一动作至多一 Turn）、INV08（过期反馈丢弃）、非 Idle 请求丢弃、20 次连续重排锚点不漂移、历史栈（容量 50、去重修复） |
| **S4 第 2 批** 接通真实 Reader 与六级恢复 | **PASS** | `ContentLocatorResolver.kt`（六级容错恢复阶梯，11 项单测）；`ChapterPaginator.kt`（携带 `sourceRanges` 分页与 `findPageForLocator` 逆向定位）；`DisplayMapping.kt`；`ReaderViewModel` 接入 `ReaderStateDao` 与六级恢复闭环；`AppModule` 补齐 DAO 绑定；TXT 分卷及卷首语提取支持 |

---

## 4. 下一步入口点：Gate G4 真机重排验收 与 S5（滚动模式内容引擎接线）

### 核心任务清单：
1. **Gate G4 真机重排不漂移验收**：
   - 使用实机（小米 PD2408）或受控测试验证：分页阅读模式下，改变字号/行距、切换系统字体、旋转屏幕、退出并重新进入阅读器，连续 20 次重排后屏幕顶部的第一行文字与权威锚点（Semantic Anchor）精准对齐，Locator 零漂移。
2. **S5 滚动模式内容引擎接线（主计划第 6.6 节）**：
   - 改造 `app/.../components/ReaderContent.kt`（平卷与滚动视图）。
   - 建立垂直滚动像素偏移量（`ScrollState.value`）与正文行 `TextLayoutResult` 的投影关系，实现滚动停止时生成精确的 `TextLocator`。
   - 实现滚动模式进入时的六级恢复定位，精准滚动至锚点所在行。
3. **清理与停用遗留裸 Int（主计划第 6.7 节、R1、S9）**：
   - `BookEntity.currentPosition` 已标记为 `@Deprecated`，逐步切断剩余非关键读取路径，使其完全迁移至 `ReaderStateDao`。

---

## 5. 验证方式与常用命令

在工程根目录下执行（必须走 `pwsh`，即 PowerShell 7，脚本会自动映射 `R:\`）：

```powershell
# 1. 跑全量三合一主门禁（代码风格 + 570 项单测 + Debug APK 打包）
pwsh -NoProfile -File tools\verify.ps1

# 2. 统计当前单元测试总数与失败数
pwsh -NoProfile -File tools\count_tests.ps1

# 3. 运行特定模块测试（如 ContentLocatorResolver 或 Coordinator 测试）
.\gradlew.bat :feature:reader:testDebugUnitTest --tests "com.flowreader.feature.reader.ContentLocatorResolverTest" --console=plain
.\gradlew.bat :feature:reader:testDebugUnitTest --tests "com.flowreader.feature.reader.ReaderNavigationCoordinatorTest" --console=plain

# 4. 代码格式自动修正（如果 ktlint 报违规）
.\gradlew.bat ktlintFormat

# 5. 安装最新 APK 到真机
adb -s 10AF641GRU007U7 install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## 6. 硬性约束与踩坑防范（必须牢记）

1. **中文路径与 `subst R:` 环境约束**：
   - 项目物理路径 `C:\Users\lenovo\Desktop\心流阅读器` 含有中文字符。Gradle worker 在运行单元测试时生成的 classpath 参数文件是 ANSI (GBK) 编码，但 JVM 按 UTF-8 读取会导致路径乱码，所有测试类抛出 `ClassNotFoundException`。
   - **解决方案**：始终使用 `subst R: 'C:\Users\lenovo\Desktop\心流阅读器'`，并在 `R:\` 驱动器下执行 Gradle 构建。`tools\verify.ps1` 已经内置该检测与自动挂载逻辑。切勿尝试修改系统编码！
2. **PowerShell 编码陷阱**：
   - 本机默认宿主是 PowerShell 5.1（ANSI 编码），**严禁**使用 Windows 默认 PowerShell 或 `Get-Content | Set-Content` 直接回写源代码，否则会摧毁所有中文字符和注释。
   - 命令行必须使用 **PowerShell 7 (`pwsh`)**。
3. **Git 工作区保护**：
   - **绝对禁止** `git reset --hard`、`git checkout --`、`git restore`。工作区包含大量未提交及新增核心文件，工作区即唯一真本。
4. **单测统计口径**：
   - 工程目前实际物理存在 75 个测试文件、76 套测试套件，共包含 **570 项 `@Test` 单元测试**。570 项全部通过即为 100% 全绿基线。
5. **Room 实体与索引一致性**：
   - Room 的 `TableInfo` 校验在真机启动开库时执行。任何在 `@Entity` 上的修改或迁移 SQL，**必须保证实体 indices 声明与 SQLite 迁移语句创建的索引 100% 严格一致**（包括 unique 属性与命名）。
6. **设备自动化测试隔离**：
   - 真机 instrumentation 测试**严禁复用或修改用户的真实数据行**！一律使用测试私有的 `device-e2e://` 前缀自建测试书籍数据，并在 `@After` 中强制单事务清理，保证用户 25 本书的数据零污染。
7. **SQLite 3.18 限制（minSdk 26）**：
   - Android minSdk 26 的 SQLite 不支持 `ON CONFLICT DO UPDATE` (UPSERT)。聚合/状态写入必须使用「先 UPDATE，影响行数为 0 再 INSERT OR IGNORE」模式。
