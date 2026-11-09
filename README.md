# 时屿 · TimeAssistant

Android 时间管理应用，包含任务与日程、专注计时、提醒、AI 聊天、日历集成以及同步与备份功能。

## 开发环境

- JDK 17
- Android SDK 36，最低支持 Android 7.0（API 24）
- Gradle 8.13（使用项目自带的 Gradle Wrapper）
- Android Gradle Plugin 8.13.2

## 构建

使用 Android Studio 打开项目，配置 Android SDK 路径并完成 Gradle 同步。命令行构建前设置 `JAVA_HOME`，通过 `ANDROID_HOME` 或本机 `local.properties` 指定 SDK。

Windows：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest :core:domain:test :core:network:test
```

macOS / Linux：

```sh
sh ./gradlew assembleDebug
sh ./gradlew testDebugUnitTest :core:domain:test :core:network:test
```

默认启用 OpenAI OAuth 集成，可使用 `-PwithOpenAiOAuth=false` 构建不含该集成的版本。AI 服务连接在应用中配置，API 密钥和签名文件不随源码提供。

## 项目结构

| 目录 | 内容 |
| --- | --- |
| `app` | Android 应用与设备测试 |
| `core` | 领域模型、网络、序列化、设计组件与导航 |
| `data` | 本地数据与凭据存储 |
| `engine` | AI 调用与任务处理 |
| `feature` | 引导、设置与聊天功能 |
| `integration` | 日历、兼容 AI 服务、OAuth 与额度查询集成 |
| `tools` | 本地构建、设备验收与界面检查脚本 |
| `docs` | 用户手册、模块说明与历史验收报告 |

详细说明见 [产品文档](docs/README.md)。历史报告中的测试结果与本机证据路径仅对应当时的验收环境。

`tools/Verify-Android.ps1` 等本地脚本依赖指定的工具路径；公开仓库不包含本机 JDK、SDK、构建缓存、设备证据和演示草稿。常规构建请使用上述 Gradle Wrapper 命令。
