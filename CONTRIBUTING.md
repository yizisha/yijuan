# 参与一卷

欢迎提交问题、功能建议、文档改进或代码修复。请遵守 [行为准则](CODE_OF_CONDUCT.md)。

## 问题与建议

在[本仓库 Issues](https://github.com/yizisha/yijuan/issues)中说明版本、Android 版本、设备型号、操作步骤及预期结果。涉及书籍解析时，请注明文件格式，尽量提供可分享的最小示例，不必上传整本书或个人备份。安全问题见 [SECURITY.md](SECURITY.md)。

## 构建环境

本地已验证的环境为 **JDK 21、Android SDK 35**。仓库包含 Gradle Wrapper 及 `app/libs/sherpa-onnx-1.13.8.aar`；首次构建需要下载 Gradle 和其他依赖，语音模型无需参与构建。

```bash
git clone https://github.com/yizisha/yijuan.git
cd yijuan
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Windows 使用 `./gradlew.bat` 执行同样的任务。本机 SDK 由环境变量或 `local.properties` 配置；不要提交机器路径或签名私钥。

当前 release 构建启用 R8 与资源裁剪，默认包含 ARM64 原生库。构建脚本使用 debug signingConfig，自行构建的包不保证与发布包签名一致。

## 提交修改

- 以仓库当前默认分支为目标，一次提交或 PR 聚焦一个问题。
- 说明行为变化、复现方式、验证结果和仍未覆盖的场景。
- 验证直接受影响的路径。文档修改检查链接与格式；阅读器修改应覆盖相关导航、进度或交互。
- 新增用户文案使用字符串资源；保持既有主题、组件和排版约定。
- 涉及数据库变更时提供增量迁移和兼容验证；保护书籍、进度、笔记及偏好。
- 保留第三方资源的版权、许可与来源说明。

模块边界见 [ARCHITECTURE.md](ARCHITECTURE.md)。
