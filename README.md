# 一卷

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/yejian_icon_art.png" width="96" alt="一卷应用图标">
</p>

<p align="center">
  <b>整理自己的书库，按自己的习惯阅读。</b><br>
  Android 本地电子书阅读器 · Kotlin / Jetpack Compose · GPL-3.0
</p>

<p align="center">
  <b>简体中文</b> · <a href="README_EN.md">English</a> ·
  <a href="https://github.com/yizisha/yijuan/releases">下载安装</a> ·
  <a href="https://github.com/yizisha/yijuan/issues">问题反馈</a>
</p>

一卷围绕本地书库、连续阅读与个人笔记开发，主导航为 **书架／统计／笔记／设置**。书籍从本地导入，无需注册账号。

本项目基于 FlowReader 的开源代码继续开发，由本仓库独立维护，使用自己的名称、版本和发布渠道。两个项目的来源关系与致谢见文末。

## 下载与安装

当前发布版本：**0.3.13**（versionCode **5710**）。

| 文件 | 用途 |
| --- | --- |
| [Android APK](https://github.com/yizisha/yijuan/releases/download/v0.3.13/yijuan-0.3.13-arm64.apk) | ARM64 安装包，约 29.36 MiB，最低 Android 8.0 |
| [对应版本源码](https://github.com/yizisha/yijuan/releases/download/v0.3.13/yijuan-0.3.13-source.zip) | 0.3.13 发布提交的源码，包含构建所需的本地语音引擎依赖 |
| [SHA-256 校验文件](https://github.com/yizisha/yijuan/releases/download/v0.3.13/SHA256SUMS.txt) | 核对 APK 与源码压缩包 |

完整发布说明见 [Releases](https://github.com/yizisha/yijuan/releases)。安装包不附带电子书或语音模型。

## 主要功能

| 功能 | 当前实现 |
| --- | --- |
| 书架整理 | 网格／列表、分组、未读／正在读／已读筛选、排序与阅读历史；收藏独立于分组显示 |
| 系列书 | 同系列合并展示、手动排列卷序、卷末打开下一卷提示 |
| 本地阅读 | 主要支持 EPUB、TXT、PDF；支持横向分页、上下滑动、目录跳转、进度保存与书签 |
| 排版与外观 | 字体导入、字号、行距、段距、边距、手势与翻页设置；清简蓝／暖纸墨韵两套界面风格，支持深色模式 |
| 笔记与搜索 | 长按选择、复制、划线／柔和高亮、写笔记、回到原文；书内及全库文字搜索 |
| 阅读统计 | 周／月／年周期查看、趋势、阅读指标、热力图、目标与提醒 |
| 文件与数据 | 本地文件导入、目录扫描、ZIP 批量导入、局域网传输；详情查看文件大小与来源位置；备份、恢复、导出与应用锁 |
| 可选听书 | 默认关闭，可在“设置 → 应用”开启；支持系统语音及按需下载或本地导入的离线语音模型 |

## 使用说明

- **分组与收藏**：点击书架“全部”切换分组，阅读状态筛选跟随当前分组；收藏栏始终展示整个书库的收藏。
- **系列书**：在书籍菜单中设置系列，并在系列管理中调整成员与卷序。
- **阅读与笔记**：阅读时点按调出工具栏；底部“设置”调整字体、排版、主题和翻页，长按正文可复制或添加笔记。
- **听书**：在应用设置中开启后，通过阅读器的耳机入口使用。离线模型下载、导入、选择与试听均在应用内完成；模型单独占用存储空间。
- **文件位置**：书籍详情可查看、复制来源。能否跳转到原目录取决于来源应用提供的授权；应用私有副本可另存到自行选择的目录。

## 当前版本与验证范围

0.3.13 修复切章过程中旧页面回调覆盖新目标的问题，并增加手动切章的目标确认保护。

本版已通过 **42 项定向回归**、正式构建与签名校验。问题 EPUB 的原书真机复验仍待完成；本地测试通过不代表所有 EPUB、手机和语音模型均已完成真机验收。

- [一卷更新日志](CHANGELOG.md)
- [0.3.13 修复与验证记录](docs/content-engine/reports/0.3.13-report-navigation-generation.md)
- [设置结构与使用入口](docs/SETTINGS_TREE.md)

反馈问题时，可在[本仓库 Issues](https://github.com/yizisha/yijuan/issues)说明版本、手机型号、文件格式和复现步骤，无需公开整本书或个人备份。

## 从源码构建

本地已验证的环境为 **JDK 21、Android SDK 35**。Gradle Wrapper 与 sherpa-onnx 1.13.8 AAR 已包含在仓库中，语音模型无需参与应用构建。

```bash
git clone https://github.com/yizisha/yijuan.git
cd yijuan
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Windows 使用 `./gradlew.bat` 执行相同任务。首次构建需要联网下载 Gradle 与其他依赖，SDK 路径由本机环境或 `local.properties` 配置。当前构建脚本沿用 debug signingConfig，开发者自行构建的包不保证与 Releases 中的安装包签名相同。

| 模块 | 职责 |
| --- | --- |
| `app` | 应用组装、界面、仓库实现、导入与播放接入 |
| `core` | 共用组件、主题、排版样式与工具 |
| `domain` | 领域模型、内容定位与仓库接口 |
| `data` | Room 数据库、DAO、实体与增量迁移 |
| `feature/reader` | 分页、阅读定位、导航与计时规则 |
| `plugin-api`、`plugin/tts` | 语音接口与随应用内置的系统 TTS 实现 |

## 项目来源与许可

一卷以 [HuZaiGong / FlowReader](https://github.com/HuZaiGong/flowreader) 的开源代码为基础继续开发。感谢原作者及上游贡献者提供的基础实现。

**一卷与 FlowReader 是分别维护的项目。** 本仓库的名称、界面调整、功能演进与 `0.x` 版本属于一卷；原项目的 `v56.x` 历史与贡献在更新日志中单独标注，不作为一卷的发布记录。

项目继续采用 [GNU GPL-3.0](LICENSE)，保留上游版权与许可信息。语音引擎、字体及其他第三方资源遵循各自许可；语音组件来源见 [SOURCES.txt](app/src/main/assets/speech-licenses/SOURCES.txt)，字体许可见 [font_licenses](core/src/main/assets/font_licenses)。下载的语音模型另有各自许可与使用条件。
