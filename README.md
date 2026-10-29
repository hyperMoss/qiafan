<div align="center">
  <img src="docs/assets/icon.svg" width="96" height="96" alt="恰饭应用图标" />
  <h1>恰饭</h1>
  <p>一个以文字阅读为主的饭否 Android 客户端。</p>
  <p>简洁的信息流，完整的转发链，随手保存与分享。</p>
  <p>
    <a href="https://github.com/hyperMoss/qiafan/releases/latest"><img src="https://img.shields.io/github/v/release/hyperMoss/qiafan?label=release&amp;color=0077B5" alt="最新版本" /></a>
    <a href="https://github.com/hyperMoss/qiafan/actions/workflows/android-ci.yml"><img src="https://github.com/hyperMoss/qiafan/actions/workflows/android-ci.yml/badge.svg?branch=master" alt="Android CI" /></a>
    <img src="https://img.shields.io/badge/Android-6.0%2B-3DDC84?logo=android&amp;logoColor=white" alt="支持 Android 6.0 及以上" />
  </p>
  <p>
    <a href="https://github.com/hyperMoss/qiafan/releases/latest">下载最新版</a> ·
    <a href="docs/usage.md">使用说明</a> ·
    <a href="docs/android-development.md">开发指南</a> ·
    <a href="https://github.com/hyperMoss/qiafan/issues">反馈问题</a>
  </p>
</div>

## 功能

- 阅读关注动态，下拉刷新、自动分页，返回列表时恢复滚动位置。
- 查看提及、私信和关注请求，浏览公开动态，搜索动态与用户。
- 浏览用户主页、收藏和本机历史，访问有权查看的受保护内容。
- 发布文字动态、回复、转发与一键快转，保留多层转发中的评论和署名。
- 查看与保存单张原图，将动态生成便笺、极简或深色样式的分享图片。
- 选择和复制正文，打开正文中的用户提及与网页链接。

当前客户端以 Android 为验收平台。仓库保留 Kotlin Multiplatform 与 Kuikly 工程，iOS、鸿蒙和 Web 尚未接入这套饭否客户端。

## 下载与安装

1. 打开 [最新 Release](https://github.com/hyperMoss/qiafan/releases/latest)。
2. 在 **Assets** 中下载 `qiafan-v版本号.apk`，在 Android 设备上安装。
3. 打开恰饭，使用饭否用户名或邮箱和密码登录。

## dev 测试构建

推送到 `dev` 时，`Android Dev APK` 工作流使用仓库 Actions secrets 中独立的 `ANDROID_CI_KEYSTORE_BASE64`、`ANDROID_CI_KEYSTORE_PASSWORD`、`ANDROID_CI_KEY_ALIAS` 和 `ANDROID_CI_KEY_PASSWORD` 构建固定签名的 `.ci` 包（`com.example.qiafan.ci`）。该 CI 测试签名与正式 Release 签名分开，推送构建可连续覆盖安装。dev 目标 PR 使用临时签名的 `.dev` debug 包；fork PR 不会获得仓库 secrets。构建产物含 APK、manifest 和 SHA-256，保留 7 天。该流程不发布正式 Release。

dev push 的 artifact 名称为 `qiafan-ci-<run_id>-<run_attempt>`，包含 `qiafan-ci.apk`、`manifest.json` 和 `qiafan-ci.apk.sha256`，安装前用 manifest 核对 commit 和摘要。

## 本机构建与运行
支持 Android 6.0 及以上。Release 同时提供 `.apk.sha256` 校验文件；历史版本与更新说明见 [Releases](https://github.com/hyperMoss/qiafan/releases)。

正式版包名为 `com.example.qiafan`，开发版为 `com.example.qiafan.dev`，两者可以并存。更新正式版时，安装新 APK 即可覆盖升级。

## 登录与本机数据

密码仅用于通过 HTTPS 换取访问令牌，不保存在本机。令牌使用 Android Keystore 加密存储；浏览历史和最近搜索保存在设备上，可手动清除。设置中还提供图片缓存及通知与信息流缓存的清除入口。

登录使用 nofan 的公开应用凭据，其可用性取决于饭否服务与应用授权状态。认证方式、接口限制与使用细节见 [使用说明](docs/usage.md)。

## 本地开发

准备 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0。项目使用 Kotlin 2.1.21、Gradle 8.8 和 Android Gradle Plugin 8.5.2，Gradle 通过仓库自带的 Wrapper 运行。

获取源码：

```sh
git clone https://github.com/hyperMoss/qiafan.git
cd qiafan
```

通过 Android Studio 打开项目并配置 SDK，或在仓库根目录的 `local.properties` 中设置 `sdk.dir`，再构建 Debug APK：

```sh
./gradlew :androidApp:assembleDebug
```

首次构建需要下载依赖；Debug APK 输出到 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

连接已授权调试的 Android 设备后，可编译并安装：

```sh
./install-debug.sh
```

脚本调用 Gradle 的 `:androidApp:installDebug`，该任务会先构建 APK，再安装到设备。首次构建需能下载依赖；依赖已缓存时可用 `./install-debug.sh --offline`。Debug 包名是 `com.example.qiafan.dev`，可以与原有 `com.example.qiafan` 并排安装。云端 CI 测试包使用独立的 `com.example.qiafan.ci` 包名和固定测试证书，与 `.dev` 及正式包并排安装。正式包名仍为 `com.example.qiafan`。如需编译 iOS 目标，还需 Xcode 与 CocoaPods；Android 构建不依赖 iOS 运行环境。

### 无线安装到真机

手机与电脑连接同一 Wi-Fi，在手机开发者选项中开启无线调试。已配对的手机可直接运行：
运行单元测试与 Release lint：

```sh
./gradlew :androidApp:testDebugUnitTest :androidApp:lintRelease
```

离线构建、无线安装和设备检查见 [Android 开发指南](docs/android-development.md)。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `androidApp/src/main/java/com/example/qiafan/fanfou/` | Android 界面、饭否 API、会话存储与图片处理 |
| `androidApp/src/test/`、`androidApp/src/androidTest/` | 单元测试与设备检查 |
| `shared/` | Kuikly 共享工程，当前饭否客户端逻辑尚未迁入 |
| `iosApp/`、`ohosApp/`、`static_server/` | 原有平台壳与辅助目录 |
| `docs/` | 使用、开发、发布与验证文档 |

## 文档

- [使用说明](docs/usage.md)：界面操作、登录方式与功能限制。
- [Android 开发指南](docs/android-development.md)：环境、构建、安装和检查命令。
- [Android 自动发布](docs/android-release.md)：版本管理、正式签名与 GitHub Release。
- [验证记录](docs/verification.md)：构建、测试、真实接口联调与设备验收结果。

## 反馈与贡献

通过 [Issues](https://github.com/hyperMoss/qiafan/issues) 报告问题或提出建议。报告问题时，附上应用版本、Android 版本、复现步骤和预期结果；分享截图与日志前，请移除密码、令牌和私信等个人信息。

提交改动前阅读 [开发约定](AGENTS.md)，运行相关检查，并在 Pull Request 中说明修改内容与验证方式。

## 致谢

- [Kuikly](https://github.com/Tencent-TDS/KuiklyUI)：项目的多平台工程基础。
- [FanfouAPI](https://github.com/FanfouAPI/FanFouAPIDoc)：饭否 API 文档。
- [nofan](https://github.com/fanfoujs/nofan)：xAuth 登录实现参考与公开应用凭据。

## 许可证

仓库目前尚未声明项目许可证。
