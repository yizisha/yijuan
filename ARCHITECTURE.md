# 一卷架构

项目使用 Kotlin、Jetpack Compose、ViewModel、Room 与 DataStore，按应用组装、公共组件、领域模型和存储划分模块。

| 模块 | 当前职责 |
| --- | --- |
| `app` | 应用入口、导航、界面与 ViewModel、仓库实现、文件导入、搜索和语音接入 |
| `core` | 共用主题与组件、阅读样式、字体与封面资源、文本工具 |
| `domain` | 书籍、系列、笔记等领域模型，内容定位与仓库接口 |
| `data` | Room 数据库、DAO、实体、内容索引和增量迁移 |
| `feature/reader` | 分页、阅读位置解析、导航协调与阅读计时规则 |
| `feature/library` | 已接入构建的书库模块边界；当前书架界面仍在 `app` 中 |
| `plugin-api` | 语音实现使用的共用接口 |
| `plugin/tts` | 随应用内置的系统 TTS 实现 |

## 数据与界面

Compose 界面订阅 ViewModel 状态，通过仓库接口读写 Room 或 DataStore。内容定位与分页、滚动布局分离，书签、搜索和笔记通过定位信息回到原文。

`domain` 不依赖应用界面或 Room；`feature` 与 `data` 使用领域模型和公共组件，由 `app` 完成组装。离线语音运行时随应用构建，模型按需下载，不参与基础 APK 打包。

## 兼容性

保留 `com.flowreader.app` 应用 ID 和已有包名，用于延续安装与数据兼容。数据库变化使用增量迁移；偏好、备份与内容定位应维护向后兼容。

具体构建方式见 [贡献指南](CONTRIBUTING.md)。
