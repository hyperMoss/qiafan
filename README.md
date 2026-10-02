# 恰饭

一个以文字阅读为主的饭否 Android 客户端。Android 界面参考「恰饭-KMP-原型方案」的纸白底色、衬线标题、发丝分隔信息流和固定底部导航；仍使用项目约定的关注、通知、热门三个一级入口。现有 Kuikly 多平台工程保留；iOS、鸿蒙和 Web 尚未接入这套客户端界面。

## 构建与运行

需要 JDK 17、Android SDK 和可用的 Gradle 仓库。项目使用 Gradle 8.8、Android Gradle Plugin 8.5.2。运行：

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

第二条命令会在已连接且授权调试的 Android 设备上完成构建和安装。Debug 包名是 `com.example.qiafan.dev`，可以与原有 `com.example.qiafan` 并排安装。正式包名仍为 `com.example.qiafan`。如需编译 iOS 目标，还需 Xcode 与 CocoaPods；Android 构建不依赖 iOS 运行环境。

## 登录

打开应用后输入饭否用户名（或邮箱）和密码。客户端按 [nofan 的登录实现](https://github.com/fanfoujs/nofan/blob/main/source/nofan.ts)使用 xAuth，并使用其[公开的 Consumer Key/Secret](https://github.com/fanfoujs/nofan/blob/main/source/util.ts)向饭否换取 Access Token。密码只用于这次 HTTPS 请求，不写入本地存储；Token 使用 Android Keystore 加密保存在设备上。退出登录会清除会话和浏览历史。旧版手动输入 Consumer Key 和 PIN 的会话需要重新登录。

公开的 Consumer Secret 会包含在安装包中，无法当作私密凭据使用；其有效性取决于饭否与 nofan 应用的当前状态。饭否 [API 文档](https://github.com/FanfouAPI/FanFouAPIDoc/wiki/Apicategory)较旧，部分示例使用 HTTP。客户端仅通过 HTTPS 传输，按 nofan 的兼容方式使用 HTTP 地址计算 OAuth 签名，不会将密码或令牌通过明文 HTTP 发送。当前没有用于联调的饭否测试账号，因此 xAuth 与业务接口的线上可用性尚未确认；服务端报错会直接显示。

## 使用范围

- 主入口：关注、通知、热门。热门可在设置中关闭，关闭后不请求热门接口。
- 左侧导航：搜索、浏览历史、我的主页、收藏、当前范围原图保存、刷新和设置。搜索可选全站动态、用户、指定用户内。
- 动态列表：进入关注页自动加载；下拉刷新、滚动到底自动分页。点击动态进入整页详情，点击作者进入用户时间线，点图片查看原图。长按打开操作面板；收藏和快转也有可见按钮。
- 通知：按提及、私信和关注请求分栏显示。未读数来自通知计数接口，列表各自从对应接口加载。私信页只展示已取得的收件箱内容，并可尝试发送文本私信；不把它冒充完整的服务端会话历史。
- 发布：关注页右下角入口可发布最多 140 字的纯文本动态，失败时保留正文并恢复按钮。图片发布与详情评论尚未接入。
- 原图：卡片、原图查看器或侧栏可保存当前已加载范围内的原图。保存到系统“图片/恰饭”，按地址去重，显示进度、失败动态和取消入口；不会自动抓取未加载的历史页。

## 验证状态

本次重新设计已通过 `./gradlew :androidApp:assembleDebug --offline --no-daemon`，Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。本次检查时 `adb devices -l` 没有连接的设备，且没有可用 AVD，所以新界面的真机触控、图片保存与饭否接口尚未验收。此前版本曾成功安装到设备，不能代表这次 APK 已完成设备验收。
# qiafan
