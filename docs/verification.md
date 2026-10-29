# 验证记录

[返回项目首页](../README.md)

这里保留各次开发与发布的验证结果。带日期的记录只说明当时的版本和环境；原记录中的“本轮”指对应的那次验证。编译、单元测试、真实接口联调与设备验收分别记录，不互相替代。

## v1.0.11 发布验证

2026-10-26 发布 [v1.0.11](https://github.com/hyperMoss/qiafan/releases/tag/v1.0.11)，包含多层转发链及分享图片修复。

- 本机以 `-PappVersionName=1.0.11` 运行 `:androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:assembleRelease :androidApp:lintRelease --no-daemon`，全部通过。
- 单元测试 71 项，失败、错误和跳过均为 0。Release lint 无错误，有 48 条警告；构建仍有既有弃用与 D8 Kotlin 元数据警告。
- [Android CI](https://github.com/hyperMoss/qiafan/actions/runs/40290944257) 与 [Android Release](https://github.com/hyperMoss/qiafan/actions/runs/40290944183) 均成功。
- 下载公开 Release APK，核验包名 `com.example.qiafan`、版本 `1.0.11`、versionCode `1000011`，未开启 debuggable。
- APK SHA-256 与附件一致：`6c87a2950830e8540526252d59021a59c7e579f3013be8f272c03fe36fa2745f`。
- 签名证书 SHA-256 与 v1.0.10 一致：`c605f22b7f38bf5a5a42ceef65d189d8b59adfbf25cb98244592e45ec71d7633`。
- 发布时没有连接的 Android 设备，未补做设备验收，也未重新执行 xAuth 登录或写入类接口联调。详情与点击路径的设备复核仍待完成。

## 编译与安装

- 2026-10-26：转发链展示改动在本机 `:androidApp:assembleDebug`、`:androidApp:assembleRelease` 和 `:androidApp:testDebugUnitTest`（71 项用例，0 失败）全部通过，既有弃用及 D8 Kotlin 元数据警告仍在。Debug `1.0.10-dev`（versionCode 1000010）安装到 `23127PN0CC` 后启动无崩溃，真实关注流中的多层转发按 `转自 @昵称` 加分隔线逐层显示；同一改动的分享图在三种样式下生成于 `androidApp/build/reports/content-block-shares/chain-*.png`，原图只绘制一次。随后手机无线调试断开，未再补做详情页与点击路径的设备复核。推送到 `master` 后的 Android CI（run 40220257505）Debug 与未签名 Release 编译均通过，仅有既有的 Node.js 20 Action 弃用提示。
- 2026-10-13：受保护主页修复后，`:androidApp:assembleDebug :androidApp:assembleDebugAndroidTest --offline --no-daemon` 通过，设备检查通过 13 项。最终 `installDebug` 的模拟器属性查询超时，改用 `adb install -r` 成功覆盖安装主应用和测试 APK；既有弃用及 D8 警告仍在。
- 2026-10-13：`:androidApp:installDebug --offline --no-daemon` 编译并覆盖安装到 MuMu Android 12。`:androidApp:assembleDebugAndroidTest` 通过，原生解析测试通过六项用例，覆盖转义搜索高亮、网址连续性、链接偏移和非搜索正文边界。构建仍有既有的弃用及 D8 Kotlin 元数据警告。
- 解析回归可在安装 Debug 主应用和 `androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk` 后运行 `adb shell am instrument -w com.example.qiafan.dev.test/com.example.qiafan.fanfou.FanfouTextInstrumentation`。成功输出为 `Text and follow response checks passed 13 cases.`。覆盖搜索高亮、规范提及链接及申请待确认响应的边界；普通 403、401 和 500 不会被当作申请成功。运行会重启测试进程，不清除登录数据。
- 2026-10-08：`./install-debug.sh --offline` 编译并安装成功（模拟器及已连接的 Android 12 设备），覆盖正文提及高亮及转发原文改动；构建仍有既有的弃用和 D8 Kotlin 元数据警告。
- 2026-10-05：`./gradlew :androidApp:compileDebugKotlin --offline --no-daemon` 编译通过。
- 2026-10-05：`./install-debug.sh --offline` 执行成功，Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`，已安装到 Android 12 模拟器。

## 真实饭否联调

- 2026-10-13：设备已有会话读取 HTML 格式正文，`@LeeMay` 的真实主页链接指向 `miao.l`；`users/show` 返回昵称 LeeMay、头像、14 字签名、`protected=true`、`following=false`。请求申请关注后，服务端返回“已向 LeeMay 发出关注请求，请等待确认。”，客户端将这条明确的待确认响应识别为申请状态；其他错误仍显示失败。未把等待通过当作已关注。
- 2026-10-13：设备已有会话读取 `http` 搜索结果，关键词加粗且网页地址保持完整；打开真实动态后，正文网页链接和 `@砂音色` 主页跳转通过。未发布、转发或删除动态。
- 2026-10-08：通过设备已有会话读取真实提及并打开用户主页；本轮没有发送转发，转发写入服务端及超长草稿编辑流程尚未设备验收。
- 使用设备上已有的授权会话，从“通知 / @我的”打开一条确实带有回复关系的提及，再通过“查看原动态”打开 `@amouse` 的真实原动态。
- 上下文接口在该原动态下返回 5 条当前账号可见的真实回复；另一条回复动态返回 0 条评论。
- 本轮未重新执行 xAuth 登录，也未发送评论或删除动态；这些线上成功响应仍未验收。

## 设备目视验收

- 2026-10-13：MuMu Android 12 从真实搜索结果的 `@LeeMay` 打开 `@miao.l` 主页，正确显示头像、昵称、完整签名及关注锁。点击“申请关注”后出现“关注申请已发送 / 已发送关注申请，等待对方通过”弹窗；关闭后按钮显示禁用的“已申请”，再次点击无新弹窗，返回并重新打开主页仍显示“已申请”，内容保持锁定。验收期间模拟器时间曾比服务器慢约 46 分钟，产生 `Expired timestamp`，同步时钟后接口恢复。对方尚未批准，批准后的内容读取未验收；首次申请的正常 200 响应、跨账号切换及提交中返回重开的并发路径尚未设备验收。
- 2026-10-13：工具条仅使用位移后，Debug 编译安装通过。MuMu 的关注页已验证顶栏、底栏和发布按钮滑出及反向恢复；通知页已验证顶栏、底栏收起与恢复，详情返回和侧栏覆盖正常。源码已删除工具条的 `GONE`/`INVISIBLE` 切换及显隐引发的滚动补偿。未测量帧率或覆盖所有消息页，不把该结果当作其他渲染卡顿已消除的证明。
- 2026-10-13：MuMu Android 12 真实详情长按出现原生选区手柄及“复制、分享、全选”浮条，复制的片段可粘贴到输入框，验收草稿已清除且未发送。正文网址可打开 WebView，右上角“浏览器”已跳转系统浏览器。`solidot.org` 网页内容加载成功；另一个 HTTP 链接升级 HTTPS 后返回 `ERR_CONNECTION_CLOSED`，内置页面正确显示错误与重试。详情无加载浮层，通知读取浮层无提示文字，热门空状态正常；侧栏覆盖头栏、底栏及发布按钮，刷新入口已移除。
- 2026-10-13：验收期间模拟器中的应用两次处于 `TN` 暂停状态，ANR 记录显示线程停在 `do_signal_stop`；恢复进程后完成文本选择验收。本轮未重测非空评论选区、所有网页重定向、网页历史返回及所有设备尺寸。
- 2026-10-08：模拟器真实提及列表中多个中文 `@昵称` 正确显示蓝色；点击非链接正文能打开动态详情，点击正文中的 `@鸭泪锅` 能进入 `@wocalage` 主页。正文长按和完整转发发送流程尚未验收。
- 2026-10-08：补充独立“转发”入口后重新编译安装成功；模拟器已从“更多”菜单点击“转发”，确认编辑页预填原作者和完整转发链正文，并显示字数及“转发”按钮。未点击发送，线上发布结果与失败恢复仍未验收。
- 2026-10-08：评论前缀改为昵称后编译安装成功；模拟器确认评论栏显示“回复 @一勺甜酒酿”，按该昵称前缀扣除 7 字，剩余 133 字。未发送评论，昵称前缀下服务端是否保留回复关系仍待线上验收。
- 2026-10-08：关注页工具栏改为 220 毫秒位移与透明度动画后编译安装成功；模拟器确认圆形发布按钮，向下浏览后顶栏、底栏及发布按钮隐藏，反向滚动后恢复。截图验证了隐藏/恢复结果，未逐帧测量动画流畅度。
- 真实非空评论页：评论下方的原动态卡片正确显示作者、头像、时间、完整正文和图片；点击“查看详情”会重新打开该原动态详情。
- 评论正文中的蓝色 `@一只特立独行的耗子` 可点击，已进入正确的 `@amouse` 受保护用户主页。
- 0 条评论页正确显示“评论 0 条”和“还没有评论”，无空白占位或布局回归。
- 2026-10-03 已验证热门页两个子页、公开动态读取、最近搜索清除、上滑隐藏与下滑恢复顶栏、头像与正文图片、资料页签名及侧边栏顺序；热门话题列表当时返回空结果。
