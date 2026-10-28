# FlowReader 内容引擎改造 — S8c 报告（真机验收真实目录 + 修复目录跳转缺陷）

执行日期：2026-09-11。依据：主计划 **4.3 目录树**、**7.1 展示模型**、**7.2 第 3 条**。

环境：小米 PD2408 / V2408A，Android 16，adb 序列号 `10AF641GRU007U7`。

Gate：**PASS**（JVM **795 项单测全绿 / 96 套件**；**真机 7/7 全过**；代码风格 0 违规；APK 构建并安装成功、启动无崩溃）。
版本：**0.2.3**（versionCode 5676）。

---

## 1. 真机验收暴露了一个严重缺陷

新增的真机测试 `TocTreeOnDeviceTest`（4 项）**第一次运行就全部失败**，诊断输出直指根因：

```text
DIAG tocTargetSections={234734acfe2269dc=s:f5e1249d..., 5e7822eb44234d4e=s:2896dfb0..., ...}   ← 映射是对的
DIAG node id=234734acfe2269dc ... label=第一章 风起                                            ← nodeId 也对得上
DIAG row 234734acfe2269dc kind=GROUP role=null
     target={"documentKey":"OEBPS/Text/ch1.xhtml","fragment":"a1","sectionId":null}             ← 但写库时 sectionId 是 null
```

**根因**（`PublicationImportCoordinator.activate`，一行）：

```kotlin
tocNodes = flattenToc(toc, bookId, manifest.revision),
//        ↑ flattenToc 的第 4 个参数 tocTargetSections 有默认值 emptyMap()，这里漏传了 manifest.tocTargetSections
```

后果：**经新导入链路进来的每一本书，其目录节点的 sectionId 全是 null**。
目录能显示标签与层级，但**任何一项都跳不到对应章节**——正是我在 S8b 报告里
标记为「最容易踩空的一处」的那个风险，而它当时**已经存在**。

为什么既有测试没抓到：真机的 `PublicationImportOnDeviceTest` 只断言了
`countTocNodes >= 5`（**节点数量**），没有断言**目标是否写进去**。

---

## 2. 修复与加固

| 修复 | 说明 |
|---|---|
| **传入映射** | `flattenToc(..., manifest.tocTargetSections)` |
| **删掉危险默认值** | `tocTargetSections` 参数不再有 `emptyMap()` 默认值。漏传时**编译报错**，而不是运行时静默把所有目标写成 null——静默降级正是这个缺陷能活下来的原因 |
| **替换 `org.json`** | `flattenToc` 原用 `JSONObject`，它在 JVM 单测里是 Android SDK 空壳桩（`Method put ... not mocked`），导致「目标是否写进去」**根本无法在桌面回归**（这正是我为 domain 写 `MiniJson` 的同一原因）。改用新增的 `TocTargetJson.encode`，解析/编码对称且纯 Kotlin |
| **补回归测试** | `TocTargetWriteTest`（5 项）直接断言目标确实写入、同文档不同锚点各写各的、无链接分组不写空对象、层级与 siblingOrder 保留 |

---

## 3. 真机验收内容

`TocTreeOnDeviceTest`（4 项，全程自建 `device-e2e://` 宿主书并在 `@After` 清理）：

1. **toc_nodes 往返成真实层级**：卷 → 章 → 节三层完整还原；无链接 `<span>` 卷头是
   `GROUP`+`isVolume`+`无 target`；同一 XHTML 的不同锚点是不同 nodeId 且不同目标（F05）。
2. **目标映射到真实章节序**：每个可读目录项的 `targetSectionId` 都能经 `reading_sections`
   映射到 ordinal；同一 XHTML 的两个锚点映射到**不同** section（才能各自跳对位置）。
3. **折叠与搜索在真实树上可用**：收起只见根、展开见子、搜索「夜行」露出完整祖先链、
   搜索卷标题展示全部后代、覆盖节数正确。
4. **覆盖范围排除结构性卷头**：卷头本身不可读，不计入覆盖；按 section 能定位回目录项。

真机结果：**7/7 全过**（3 项既有导入验收 + 4 项目录树验收）。

---

## 4. 门禁与设备数据

| 项目 | 结果 |
|---|---|
| 代码风格 | **PASS**（0 违规） |
| JVM 单元测试 | **PASS**（**795 项 / 0 失败 / 0 错误 / 96 套件**） |
| 真机 `connectedDebugAndroidTest` | **PASS**（V2408A / Android 16，**7/7**） |
| APK 构建 + 安装 | **PASS**（29,403,173 字节，`install -r` Success） |
| 启动冒烟 | **PASS**（crash buffer 空，进程存活 pid 20978） |

单测 790 → **795（+5，回归测试）**。

**设备数据**：操作前已建立备份 `R:\backup-device\flowreader-appdata.tar`（15,275,008 字节，
tar 头 `64 61 74 61 62 61 73 65` = "database"，8 个条目，含 databases 与 files/books）。
说明：第一次用 `pwsh >` 重定向得到的备份是 **UTF-16 损坏文件**（以 `FF FE` 开头）——
**看起来有 12MB 的假备份比没有备份更危险**，已改用字节级重定向重做并验证。

---

## 5. 阶段验收状态矩阵

| 任务 / 模块 | 状态 | 证据 |
|---|:---:|---|
| **真机真实目录树（toc_nodes 往返）** | **PASS** | 真机 4 项目录测试全过 |
| **目录项能跳到正确章节** | **PASS（已修复）** | 缺陷已定位并修；真机断言 section 映射 |
| **同一 XHTML 多锚点互不覆盖** | **PASS** | 真机 + JVM 各 1 项 |
| **无链接卷头保留为可折叠分组** | **PASS** | 真机 + JVM 各 1 项 |
| **真机安装与启动无崩溃** | **PASS** | install Success；crash buffer 空 |
| **设备数据备份** | **PASS** | 已建立并验证 tar 有效（用户表示不需要，仍保留） |
| **展开状态跨重启持久化** | **NOT_RUN** | 仍只在 ViewModel 内存 |
| **书籍详情页目录统一** | **NOT_RUN** | 计划 7.2 第 14 条 |
| **旧书自动重解析** | **NOT_RUN** | `content_migration_jobs` 未实现 |
| **手工肉眼核对真书目录** | **NOT_RUN** | 自动化已覆盖数据与层级；「这本书的目录看起来对不对」仍需人工看书 |

---

## 6. 未验证项与已知限制

1. **手工肉眼核对仍未做**：自动化验证的是「数据结构正确、跳转目标正确」，
   但**真实书目的观感**（缩进层次、长标题截断、卷范围文案）需要人看。
   当前设备上只有 1 本书（`天理协议.txt`，TXT，无 EPUB 目录），没有可核对的 EPUB。
2. **旧书不会自动进入真实树**：只有经新导入链路（写入 `toc_nodes`）的书才有真实目录；
   旧书仍走兼容适配器。`content_migration_jobs` 未实现，需要用户重新导入。
3. **展开状态不跨重启**（同 S8b）。
4. **详情页目录未统一**（同 S8b）。

---

## 7. 结论与接力

**「目录反映出版物真实结构」在数据层与跳转层已经成立，并在真机上验证通过。**

本批的价值不在新增功能，而在于：**真机验收揪出了一个桌面测试与既有设备测试都漏掉的严重缺陷**
——所有新导入书的目录都点不动。这也说明了为什么必须做真机验收，而不是只看单测数字。

下一步：
1. **导入一本真实多卷 EPUB 并人工核对目录观感**（限制 1）。
2. **展开状态持久化**（限制 3）。
3. **书籍详情页目录统一**（限制 4）。
4. **旧书重解析任务**（限制 2）。
5. **S9 切断分页路径的裸像素写入**。
