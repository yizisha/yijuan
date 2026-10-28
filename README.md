# 一卷

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/yejian_icon_art.png" width="96" alt="一卷">
</p>

<p align="center"><b>整理书库，静心阅读。</b></p>
<p align="center">
  <a href="https://github.com/yizisha/yijuan/releases/latest">下载安装</a> ·
  <a href="https://github.com/yizisha/yijuan/issues">问题反馈</a> ·
  <a href="README_EN.md">English</a>
</p>

一卷是一款 Android 本地电子书阅读器。导入自己的电子书，整理分组与系列，按阅读习惯调整排版，并保存阅读中的摘录与想法。无需注册账号。

## 下载安装

前往 [Releases](https://github.com/yizisha/yijuan/releases/latest) 下载 APK。当前版本为 **0.3.13**，最低支持 **Android 8.0**，提供 **ARM64** 安装包，大小约 **29.36 MiB**。

每个版本同时提供源码压缩包与 SHA-256 校验文件。电子书与离线语音模型不包含在安装包中。

## 支持格式

- **EPUB、TXT**：文字阅读，支持字体与排版调整。
- **PDF**：保留原版式，支持缩放、跳页和阅读位置恢复。

## 功能

### 书库整理

- 网格与列表两种书架布局，支持搜索、排序与阅读历史。
- 自定义分组，按未读、正在读、已读筛选；收藏独立于分组显示。
- 同系列合并展示，手动排列卷序，读完一卷后提示打开下一卷。
- 本地文件导入、目录扫描、ZIP 批量导入与局域网传输。
- 书籍详情显示文件大小与来源，支持复制位置、关联原文件和另存副本。

### 阅读体验

- 横向分页与上下滑动，目录、书签和阅读进度跳转。
- 调整字体、字号、行距、段距、边距，以及翻页方式和手势。
- 清简蓝与暖纸墨韵两套界面风格，支持浅色、深色和跟随系统。
- 阅读背景与文字颜色可单独设置，提供夜间模式、亮度调节与护眼提醒。

### 笔记与检索

- 长按正文选择文字，复制、划线、高亮或写笔记。
- 集中查看摘录与批注，按书籍筛选、编辑、导出，或回到原文。
- 书内搜索与全库文字搜索。

### 统计与数据

- 按周、月、年查看阅读时长、趋势、热力图与阅读指标。
- 设置阅读目标与提醒。
- 本地备份、恢复、自动备份与书架导出。
- 应用锁与 PIN。

### 听书

听书默认关闭，可在 **设置 → 应用** 中开启。支持系统语音，以及单独下载或本地导入的离线模型；模型可在应用内管理、选择和试听。

## 使用提示

- 点击书架的“全部”切换分组；状态筛选跟随当前分组，收藏栏始终显示整个书库的收藏。
- 阅读时点按调出工具栏，底部“设置”可调整字体、排版、主题与翻页。
- 文件目录能否直接打开，取决于来源应用提供的访问权限；应用内的阅读副本可另存到自行选择的位置。

版本变化与已知问题见 [更新日志](CHANGELOG.md) 和 [发布说明](https://github.com/yizisha/yijuan/releases)。报告问题时，请提供版本、设备信息和复现步骤。

## 开发与贡献

使用 Kotlin 与 Jetpack Compose 开发。源码构建、模块说明和贡献方式见 [贡献指南](CONTRIBUTING.md) 与 [架构说明](ARCHITECTURE.md)。

## 来源与许可

一卷基于 [FlowReader](https://github.com/HuZaiGong/flowreader) 的开源代码开发，由本仓库独立维护与发布。感谢原作者 HuZaiGong 及上游贡献者。

项目采用 [GNU GPL-3.0](LICENSE)，保留上游版权与许可。上游版本记录单独保存在 [上游历史](docs/UPSTREAM_CHANGELOG.md)。语音组件与字体遵循各自许可，见 [语音组件说明](app/src/main/assets/speech-licenses/SOURCES.txt) 和 [字体许可](core/src/main/assets/font_licenses)。
