# Android 开发指南

[返回项目首页](../README.md)

## 环境与安装

需要 JDK 17、Android SDK、可用的 Gradle 仓库，以及已连接并授权调试的 Android 设备。项目使用 Gradle 8.8、Android Gradle Plugin 8.5.2。一条命令完成 Debug 编译和安装：

```sh
./install-debug.sh
```

脚本调用 Gradle 的 `:androidApp:installDebug`，该任务会先构建 APK，再安装到设备。首次构建需能下载依赖；依赖已缓存时可用 `./install-debug.sh --offline`。Debug 包名是 `com.example.qiafan.dev`，可以与原有 `com.example.qiafan` 并排安装。正式包名仍为 `com.example.qiafan`。如需编译 iOS 目标，还需 Xcode 与 CocoaPods；Android 构建不依赖 iOS 运行环境。

### 无线安装到真机

手机与电脑连接同一 Wi-Fi，在手机开发者选项中开启无线调试。已配对的手机可直接运行：

```sh
./install-wireless.sh
```

首次使用时，在手机打开“使用配对码配对设备”，保持弹窗打开，并将下方地址替换为弹窗显示的配对地址。脚本会提示输入六位配对码：

```sh
./install-wireless.sh --pair 手机IP:配对端口
```

如果未自动发现已配对的手机，使用无线调试主页面的连接端口：

```sh
./install-wireless.sh --connect 手机IP:连接端口
```

也可以安装已有 APK 或指定设备：

```sh
./install-wireless.sh --apk androidApp/build/outputs/apk/debug/androidApp-debug.apk
./install-wireless.sh --device 设备序列号 -- --offline
```

配对端口和连接端口分别取自配对弹窗与无线调试主页面。脚本不保存配对码或手机地址。发现多台无线设备时，必须指定目标。默认只安装到选中的一台设备，测试版与正式版并存。

遇到旧 ADB 服务异常时，可添加 `--restart-adb` 后重试。这个选项会断开电脑当前的所有 ADB 调试连接，脚本默认不重启服务。出现安装确认弹窗时，在手机上允许安装。

## 编译与检查

在仓库根目录运行：

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:testDebugUnitTest :androidApp:lintRelease
./gradlew :androidApp:assembleRelease
```

Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。未配置签名时，Release 产物为 `androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk`。正式签名与发布步骤见 [Android 自动发布](android-release.md)。

## 设备上的解析与关注响应检查

先连接已授权的 Android 设备，再编译、安装主应用和测试 APK：

```sh
./install-debug.sh
./gradlew :androidApp:assembleDebugAndroidTest
adb install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb shell am instrument -w com.example.qiafan.dev.test/com.example.qiafan.fanfou.FanfouTextInstrumentation
```

成功输出为 `Text and follow response checks passed 13 cases.`，覆盖搜索高亮、提及链接与申请待确认响应的解析。运行会重启测试进程，不清除登录数据。这些检查不替代真实登录、发布、回复和图片保存的设备验收。历史结果见 [验证记录](verification.md)。
