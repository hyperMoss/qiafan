# 恰饭

一个以文字阅读为主的饭否 Android 客户端。Android 界面参考「恰饭-KMP-原型方案」的纸白底色、衬线标题、发丝分隔信息流和固定底部导航；仍使用项目约定的关注、通知、热门三个一级入口。现有 Kuikly 多平台工程保留；iOS、鸿蒙和 Web 尚未接入这套客户端界面。

## 构建与运行

需要 JDK 17、Android SDK、可用的 Gradle 仓库，以及已连接并授权调试的 Android 设备。项目使用 Gradle 8.8、Android Gradle Plugin 8.5.2。一条命令完成 Debug 编译和安装：

```sh
./install-debug.sh
```

脚本调用 Gradle 的 `:androidApp:installDebug`，该任务会先构建 APK，再安装到设备。首次构建需能下载依赖；依赖已缓存时可用 `./install-debug.sh --offline`。Debug 包名是 `com.example.qiafan.dev`，可以与原有 `com.example.qiafan` 并排安装。正式包名仍为 `com.example.qiafan`。如需编译 iOS 目标，还需 Xcode 与 CocoaPods；Android 构建不依赖 iOS 运行环境。

## 登录

打开应用后输入饭否用户名（或邮箱）和密码。客户端按 [nofan 的登录实现](https://github.com/fanfoujs/nofan/blob/main/source/nofan.ts)使用 xAuth，并使用其[公开的 Consumer Key/Secret](https://github.com/fanfoujs/nofan/blob/main/source/util.ts)向饭否换取 Access Token。密码只用于这次 HTTPS 请求，不写入本地存储；Token 使用 Android Keystore 加密保存在设备上。退出登录会清除会话、浏览历史和本机最近搜索。旧版手动输入 Consumer Key 和 PIN 的会话需要重新登录。

公开的 Consumer Secret 会包含在安装包中，无法当作私密凭据使用；其有效性取决于饭否与 nofan 应用的当前状态。饭否 [API 文档](https://github.com/FanfouAPI/FanFouAPIDoc/wiki/Apicategory)较旧，部分示例使用 HTTP。客户端仅通过 HTTPS 传输，按 nofan 的兼容方式使用 HTTP 地址计算 OAuth 签名，不会将密码或令牌通过明文 HTTP 发送。当前没有用于联调的饭否测试账号，因此 xAuth 与业务接口的线上可用性尚未确认；服务端报错会直接显示。

## 使用范围

- 主入口：关注、通知、热门。热门可在设置中关闭，关闭后不请求热门接口。
- 左侧导航：当前账号头像与首位的个人主页入口，其后是搜索、浏览历史、收藏、刷新和设置。搜索可选全站动态、用户、指定用户内。
- 用户资料页：点击作者后显示高清头像、签名和按时间排序的动态；不显示资料页子标签。
- 热门页：顶部可搜索动态或话题并查看本机最近搜索；子页包括热门话题与“随便看看”公开动态。
- 动态列表：首次进入关注页显示铺满可视区域的卡片骨架和小浮层；切换标签或从详情返回时立即恢复本次会话已加载的动态和滚动位置，不重复请求；下拉刷新时保留已有内容，滚动到底自动分页。上滑消息时隐藏顶部工具条，下滑或返回顶部时恢复。列表图片按比例预览，点图片查看原图；点动态进入整页详情，点作者进入用户资料页。列表底部不显示操作按钮，可点右上角“更多”或长按打开操作菜单；详情页保留收藏、快转按钮。
- 通知：按提及、私信和关注请求分栏显示。未读数来自通知计数接口，列表各自从对应接口加载。私信页只展示已取得的收件箱内容，并可尝试发送文本私信；不把它冒充完整的服务端会话历史。
- 发布：关注页右下角入口可发布最多 140 字的纯文本动态，失败时保留正文并恢复按钮。图片发布与详情评论尚未接入。
- 原图：可从单条动态卡片或图片查看器保存该张原图到系统“图片/恰饭”；批量保存入口已移除。

## 验证状态

2026-10-02 已通过 `./gradlew :androidApp:installDebug --offline --no-daemon`，Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，并安装到一台已连接的 Android 12 设备。设备上确认了热门页两个子页、公开动态读取、搜索词进入最近搜索并可清除、上滑隐藏与下滑恢复顶栏、真实头像与正文图片显示、用户资料页签名及侧边栏入口顺序。热门话题列表此次返回空结果。登录换取令牌、原图保存及受保护账号权限未在本次设备操作中验收。
