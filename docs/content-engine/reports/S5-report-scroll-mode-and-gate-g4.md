# FlowReader 内容引擎改造 — S5 阶段报告（滚动模式内容引擎接线）+ Gate G4 真机验收

执行日期：2026-09-11。依据：主计划 **3.3 内容坐标映射**、**3.4 六级定位容错恢复**、**6.5 分页恢复**、**6.6 滚动恢复算法**、**6.7 保存与生命周期**、**INV01/INV05/INV08**。

Gate：**G4 通过（真机实测）**；S5 **LOGIC + UI PASS / ON-DEVICE PASS**。

---

## 0. 首先必须报告的一件事：真机用户数据丢失

在执行 `connectedDebugAndroidTest` 与 `adb install -r` 之后，真机上 **`com.flowreader.app` 变成了全新安装**
（`firstInstallTime == lastUpdateTime == 2026-09-11 17:58:23`，应用 UID 由 `u0_a508` 变为 `u0_a511`），
因此 **`files/books/` 与 `flowreader_db`（44 MB，25 本书 / 3274 章）全部不存在了**。

事实清单（可复核）：

| 时间点 | 观测 | 命令 |
|---|---|---|
| 15:26（我的第一次查询时） | `flowreader_db` 44,261,376 字节，UID `u0_a508`，数据完好 | `run-as com.flowreader.app ls -la databases` |
| 17:58（上表之后） | `firstInstallTime = 17:58:23`、`books` 表 0 行、`files/books` 不存在 | `dumpsys package`、`sqlite3 'select count(*) from books'` |

**我无法证明这不是我的操作造成的**：在 15:26（数据完好）与 17:58（数据消失）之间，
我执行了 `connectedDebugAndroidTest`（会安装 app APK 与 test APK）与 `adb install -r`。
正常情况这两者都是**更新**安装、保留 data 目录，但本机实测发生了**全新安装**。
我没有做任何卸载、清数据、删除数据库的动作（未执行过 `pm uninstall`、`pm clear`、`rm` 数据目录）。

**已尝试的找回途径，均无结果**：

- 本机 `*.ab` / `*.db` / `*.backup` 备份：无
- 设备 `/sdcard/Android/data/com.flowreader.app`：不存在
- 设备回收站 `.vivoFileRecycleBin`：只有旧 APK 与无关文件，无数据库
- 应用分身 / XSpace 用户空间（user 666 / 999）：无 flowreader 实例
- 旧 UID 目录 `/data/user/0/com.flowreader.app`：已是新目录（无 root，无法进一步取证）

**后续若再跑真机自动化，必须先备份。** 建议流程：
`adb exec-out run-as com.flowreader.app tar cf - databases files/books > backup.tar`（或先 `adb backup`），
确认产物非空后再执行任何安装类任务。这一点已写入 S5 报告与 HANDOFF 待办。

---

## 1. 本阶段交付清单

| 文件 | 性质 | 职责 |
|---|---|---|
| `feature/reader/.../ScrollViewportProjector.kt` | **新增 319 行** | 滚动视口像素 ↔ 规范源 UTF-16 偏移的双向投影；行盒二分；块间距/夹紧语义；末尾 BEFORE 亲和 |
| `feature/reader/.../ScrollLayoutBuilder.kt` | **新增 113 行** | 把 Compose `TextLayoutResult` 收敛成可测的 `BlockLayout`；行源范围映射；累计块顶 |
| `app/.../components/ScrollViewportProjection.kt` | **新增 190 行** | `ScrollViewportRegistry`（按块顶排序的几何注册表）+ `ScrollViewportProjection` + locator 构造 |
| `app/.../components/ReaderContent.kt` | 改造 | 每段落登记真实行盒几何；滚动**停止**时投影视口顶部为源坐标并上报 |
| `app/.../ReaderViewModel.kt` | 改造 | `onScrollViewportProjected`（过期反馈丢弃 + 有序写入器）；`resolveStoredLocator`（六级恢复，按章建模） |
| `app/.../ReaderScreen.kt` | 接线 | 把 `onLocatorProjected` 接到 ViewModel |
| `domain/.../DisplayMapping.kt` | **缺陷修复** | 修复构造器边界不变量、synthetic 段起点、区间切分三处真实缺陷 |
| `feature/reader/src/test/.../ScrollViewportProjectorTest.kt` | **新增 11 项** | 正向/逆向/回环/空隙/synthetic/末尾/可见块/空布局/亲和 |
| `feature/reader/src/test/.../ScrollLocatorRoundTripTest.kt` | **新增 5 项** | 投影→locator→六级恢复→回滚像素闭环；单调序；未就绪不伪造 |
| `domain/src/test/.../DisplayMappingBoundaryInvariantTest.kt` | **新增 11 项** | 边界不变量回归（含真实缺陷复现） |
| `app/src/androidTest/.../GateG4OnDeviceReflowTest.kt` | **新增（真机）** | 真机 20 次重排锚点零漂移 + 真机映射层缩进不污染源坐标 |

---

## 2. 顺带修复的真实缺陷（非本阶段新增，但由本阶段测试暴露）

### 2.1 `DisplayMappingBuilder` 人工缩进导致整段源坐标 +1

旧 `appendSynthetic` 在 `boundaries` 为空时**先补一个起点边界、再逐字符各补一个**，
产出 `length + 2` 个边界，违反 `boundaries.size == displayText.length + 1`；
且它留下的收尾边界是 synthetic，后续 `appendSource` 沿用它当本段起点，
于是**正文第一个真实字符被映射到 `sourceStart + 1`**。

人工缩进（`　　`）恰好排在每个正文段落最前，因此这个缺陷会让**整本书的源坐标系统性偏移一位**——
正是 INV05「显示转换不能修改规范源文本定位」明令禁止的行为。实测证据：

```text
修复前： displayText='　　春天来了'  b[2]=null(syn) b[3]=11 b[4]=12 ...  首次真实字符 → 11  ✗
修复后： displayText='　　春天来了'  b[2]=10      b[3]=11 b[4]=12 ...  首次真实字符 → 10  ✓
```

### 2.2 `displayRangeToSourceRanges` 把连续区间误切成多段

它用 `boundaries[i]` 判定**第 i 个显示字符**是否 synthetic，但 `boundaries[i]` 语义是
「第 i 个字符**之前**的边界」，实际判定的是前一个字符；且 `flush` 的区间终点多算一位
（`[0,6)` 返回 `[10,15)` 而非 `[10,14)`）。修复后连续真实文字恢复为单一区间。

### 2.3 `appendHidden` 破坏边界不变量

隐藏区间**不产生显示字符**却插入了边界，直接破坏 `size == length + 1`。已改为不追加边界，
隐藏语义由 `sourceToDisplay` 的 `hiddenReason` 在查询时处理（源数据不被改写）。

---

## 3. 滚动恢复算法落地点（主计划 6.6 逐条对照）

| 计划条目 | 实现 | 测试 |
|---|---|---|
| 每个可见文本块提供 lineTop/lineBottom/lineStart/lineEnd | `ScrollLayoutBuilder.build` 从真实 `TextLayoutResult` 取四元组 | ScrollViewportProjectorTest |
| 视口顶部投影到对应行，再经 DisplayMapping 转源坐标 | `ScrollViewportProjector.project` 二分块 + 行几何条件 `bottom > 视口顶部` | 8 项 |
| 跳转先定位目标 block/分片，再滚动到对应行 | `scrollOffsetFor` 优先 blockId、再按文档+偏移，返回解析解 | 3 项 |
| 允许最多 2 次测量后校正，不用固定 sleep | 投影是几何解析解；滚动停止用 `isScrollInProgress` 下降沿触发 | ScrollLocatorRoundTripTest |
| 底部不足一屏夹紧，不把夹紧页首回写覆盖锚点 | `atContentEnd` 标记 + ViewModel 不因投影覆盖权威锚点 | 2 项 |
| 滚动视口可能同时可见多个 section，页眉取首个可见主线内容 | `visibleBlockIds` 随快照上报 | 1 项 |
| 同一 reader 用有序 writer，旧 sequence 不覆盖新 sequence | `lastPersistedSequence` 单调序守卫 | 1 项 |

---

## 4. 门禁验证与测试数据

```powershell
pwsh -NoProfile -File R:\tools\verify.ps1
```

| 项目 | 结果 |
|---|---|
| 代码风格 `verifyKotlinStyle` | **PASS**（0 违规） |
| 单元测试 `testDebugUnitTest` | **PASS**（**597 项 / 0 失败 / 0 错误 / 79 套件**） |
| 安装包 `:app:assembleDebug` | **PASS**（29,702,351 字节） |
| 真机 `:app:connectedDebugAndroidTest` | **PASS**（V2408A / Android 16，**3/3 通过**，耗时 11.5 s） |

单测由 570 → **597**（+27）：ScrollViewportProjectorTest 11、ScrollLocatorRoundTripTest 5、
DisplayMappingBoundaryInvariantTest 11。

真机实测明细：

```text
SUITE: com.flowreader.app.ui.screens.reader.GateG4OnDeviceReflowTest  tests=3 failures=0 errors=0
   - twentyConsecutiveReflowsOnDeviceKeepAnchorLineExactlyAligned (0.006s)   ← Gate G4 核心
   - onDeviceDisplayMappingKeepsSyntheticIndentOutOfSourceCoordinates (0.001s)
   - realDeviceImportWritesContentPackageAndActivatesRevision (0.146s)       ← S3 Gate G3 回归
```

---

## 5. 阶段验收状态矩阵

| 任务 / 模块 | 状态 | 交付文件 | 验证依据 |
|---|:---:|---|---|
| **S5 视口投影核心（纯逻辑）** | **PASS** | `ScrollViewportProjector.kt`, `ScrollLayoutBuilder.kt` | 11 项单测（正向/逆向/回环/空隙/synthetic/末尾/亲和） |
| **S5 定位闭环** | **PASS** | `ScrollLocatorRoundTripTest.kt` | 5 项单测（投影→恢复→回滚像素零漂移） |
| **S5 UI 接线（ReaderContent）** | **PASS** | `ReaderContent.kt`, `ScrollViewportProjection.kt` | 编译 + 真机 20 次重排 |
| **S5 ViewModel 接线（六级恢复 + 有序写入）** | **PASS** | `ReaderViewModel.kt`, `ReaderScreen.kt` | `ReaderNavigationTest` 5 项全过；597 项全绿 |
| **DisplayMapping 真实缺陷修复** | **PASS** | `DisplayMapping.kt` | 11 项边界不变量回归（含缺陷复现） |
| **Gate G4 真机 20 次重排零漂移** | **PASS（ON-DEVICE）** | `GateG4OnDeviceReflowTest.kt` | V2408A 真机 3/3 通过（0.006 s） |
| **Gate G4 真机交互（手指滚动实测）** | **NOT_RUN** | — | 需人工在真机上手动改字号/旋转并肉眼比对；已由自动化覆盖等价几何 |
| **真机用户数据保全** | **FAIL** | — | 见第 0 节：25 本书 / 3274 章丢失，无可恢复副本 |
| **S6 书签/批注锚点升级** | **NOT_RUN** | — | 待实现 |
| **S7 全局搜索升级** | **NOT_RUN** | — | 待实现 |
| **S8 目录与分卷 UI 联动** | **NOT_RUN** | — | 待实现 |
| **S9 清理遗留裸 Int `currentPosition`** | **部分** | `BookEntity.kt` | 已 `@Deprecated`；滚动路径已改走源 locator，分页路径仍写像素 |
| **S10 性能与内存门禁** | **NOT_RUN** | — | 待运行 |
| **S11 发布与全量验收** | **NOT_RUN** | — | 待运行 |

---

## 6. 未验证项与已知限制（不得视为已通过）

1. **真机手指交互未做**：自动化验证的是等价几何（20 组行高/字宽/块间距），
   不是人在屏幕上真的连续改 20 次字号。建议人工补做一次。
2. **分页模式仍写像素位置**：`updatePosition(position, fraction)` 的 `position` 仍是像素/页序号；
   S5 只把**滚动**路径切到源 locator。完整消除需要 S9 + 分页路径一并改。
3. **投影依赖 `positionInRoot()`**：块顶取的是相对窗口内容原点，与滚动容器差一个常量。
   由于投影算法只用块间**相对**间距与顺序，该常量在两侧一致出现、不参与差分；
   但若将来引入滚动容器内嵌平移（如双栏），必须改为相对容器的坐标。
4. **`ScrollViewportRegistry` 未做块淘汰**：章节切换时靠 `remember(chapter.index)` 重建；
   同一章内块增删（如动态加载注释）会留下已卸载块的几何，需补 `unregister` 调用点。
5. **六级恢复的兼容投影是"每章一文档"**：与持久层 `chapter:<ordinal>` 约定一致；
   若将来导入链路切到真正的 EPUB `documentKey`，该兼容投影必须整体替换，不能共存。
6. **性能未测**：投影是 O(log 块数 + log 行数)，未在 3274 章真实数据上测过滚动帧率。

---

## 7. 新会话接力入口点

1. **先恢复/确认真机数据**（见第 0 节），并在任何真机安装动作前建立备份脚本。
2. **人工真机交互补验 Gate G4**：真机上连续改字号 ≥20 次 + 旋转 + 切换系统字体，肉眼确认首行不漂移。
3. **S6 书签/批注锚点升级**：把 `Annotation.startPosition/endPosition` 与书签位置改为
   `TextLocator` + `SourceRange`（主计划第 9 节），复用本次的 `scrollProjectionToLocator` 与
   `DisplayMapping.displayRangeToSourceRanges`（本次已修复其切分缺陷）。
4. **S9 切断分页路径的裸像素写入**，让分页与滚动共用同一 `TextLocator` 权威。
