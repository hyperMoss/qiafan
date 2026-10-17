# Android 自动发布

推送 `v1.0.1` 这样的版本标签会触发 `.github/workflows/android-release.yml`，构建正式签名 APK，校验签名，并将 APK 和 SHA-256 校验文件上传到该标签对应的 GitHub Release。也可在 Actions → Android Release → Run workflow 输入已有标签重跑；重跑替换同名附件。

普通 `master` 推送和 Pull Request 运行 Android CI，验证 Debug 和未签名 Release 构建，不读取正式签名、不发布附件。

## 一次性签名配置

仓库 Settings → Secrets and variables → Actions 配置四个 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 keystore 的 Base64 编码，单行 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名 alias |
| `ANDROID_KEY_PASSWORD` | alias 对应的密钥密码 |

正式签名须长期使用同一份证书，否则已安装的正式版无法覆盖升级。将 keystore 和密码另行安全备份；`signing/`、`*.jks`、`*.keystore` 已被 Git 忽略。工作流缺少任何签名 Secret 会停止，避免发布无法安装的未签名 APK。GitHub 自带的 `GITHUB_TOKEN` 用于上传 Release，不需要另存个人访问令牌。

2026-10-08 按用户授权创建正式证书，并配置到 `hyperMoss/qiafan`。本机证书为 `signing/qiafan-release.jks`，密码及 alias 保存在 `signing/credentials.json`。安全备份整个 `signing/` 目录；这两个文件均不提交到 Git。

也可用 GitHub CLI 配置，先执行 `gh auth login`，然后运行以下命令。替换 keystore 路径；密码及 alias 使用 CLI 的交互输入，不要把密码写进命令行：

```sh
base64 -i signing/release.jks | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo hyperMoss/qiafan
gh secret set ANDROID_KEYSTORE_PASSWORD --repo hyperMoss/qiafan
gh secret set ANDROID_KEY_ALIAS --repo hyperMoss/qiafan
gh secret set ANDROID_KEY_PASSWORD --repo hyperMoss/qiafan
```

## 发布新版本

先完成测试并提交功能代码和发布脚本，保持 `master` 工作区干净。需要安装 GitHub CLI 并登录，仓库的正式签名 Secrets 须已配置：

```sh
brew install gh
gh auth login
```

发布时运行一个命令，例如发布 `1.0.3`：

```sh
./release.sh 1.0.3
```

脚本校验版本和远端分支，将根目录 `VERSION` 更新为指定版本，只提交这个版本文件，创建 `v1.0.3` 标签，再原子推送 `master` 和标签。标签推送触发现有 Android Release 工作流，云端完成正式签名构建、验签和 APK 包信息检查。脚本等待对应标签与提交的工作流结束，确认 Release 已公开、APK 和 SHA-256 两个 Assets 均非空，才输出发布完成及 Release 链接。签名材料只由云端工作流读取，脚本不读取本机 keystore 或密码。

版本号也可写成 `v1.0.3`；省略版本时使用 `VERSION` 的当前值。新版本须大于远端所有有效版本标签，不能小于当前 `VERSION`。脚本不覆盖现有标签，不强制推送，不自动合并远端代码。若 `master` 落后或分叉，先同步代码再发布。

先查看计划，保留所有文件和 Git 状态：

```sh
./release.sh 1.0.3 --dry-run
```

预演只做本机检查，不访问 GitHub。实际新发布会拒绝未提交文件，包括未跟踪文件，避免遗漏待发布代码。请先提交相关文件，或将暂时不发布的改动放入 stash；不要把签名目录、密码或令牌加入提交。

网络中断或终端退出后，保留已完成的提交和标签，用相同版本继续：

```sh
./release.sh 1.0.3 --resume
```

远端已有标签时，继续模式只读取其工作流和 Assets，允许本机存在后续未提交改动。远端没有标签但本机标签存在时，继续模式推送该标签对应的提交；标签的 `VERSION` 必须匹配。继续模式不会更新版本文件、重新提交代码或移动标签。

工作流失败时，先根据 Actions 日志修复构建环境或 Secrets，再重跑同一标签的发布：

```sh
./release.sh 1.0.3 --retry
```

`--retry` 在继续模式的基础上重跑失败工作流，并再次等待结果。代码问题需要修复后发布更大的新版本；同一标签重跑使用原提交。工作流不存在、失败或 Assets 缺失时，脚本返回非零状态并输出原因。脚本最长等待 90 秒发现标签推送的工作流；发现后由 `gh run watch` 持续等待，按 Ctrl+C 停止本机等待不会取消云端任务。

本机回归检查使用临时 Git 仓库和模拟 GitHub CLI，不产生真实发布：

```sh
python3 tests/release-script-test.py
shellcheck release.sh
```

## 联动官网准备脚本

`release.sh` 可在 APK 发布成功、Assets 校验通过且该标签仍是最新 Release 后，顺序运行官网项目的 `deploy.sh`。脚本传入本次发布的 `VERSION`、`RELEASE_TAG` 和 `RELEASE_URL`，避免官网误用其他版本。

配置一次本机官网目录，以后正常运行 `./release.sh 1.0.4` 就会自动联动：

```sh
printf '%s\n' /path/to/qiafan-site > .release-site-path
```

`.release-site-path` 仅保存本机路径，已加入 Git 忽略规则，不提交到仓库。也可用环境变量 `QIAFAN_SITE_DIR`，或单次指定目录：

```sh
./release.sh 1.0.4 --site-dir /path/to/qiafan-site
./release.sh 1.0.4 --dry-run
./release.sh 1.0.4 --resume --no-site
```

`--dry-run` 只显示联动计划，`--no-site` 跳过官网脚本。APK 发布或 Assets 检查失败时不运行官网脚本；官网脚本失败会返回非零状态，明确说明 APK 已发布，修复官网问题后用同版本 `--resume` 重试即可。已被新 Release 替代的旧标签会跳过官网同步，防止降级。

当前 `qiafan-site/deploy.sh` 更新本机下载链接、版本文字、二维码，并执行 lint、类型检查和构建。它没有公开的 WorkBuddy 发布 CLI，因此脚本完成仅表示本机已准备，线上网站仍需通过 WorkBuddy 发布。

## 官网直接读取最新版本

如果希望发布 APK 后无需再次修改官网，可让官网运行时读取 GitHub 的公开 [最新 Release 接口](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)。使用响应中的 `tag_name` 显示版本，并从 `assets` 选出已上传且非空的 `.apk`，读取其 `browser_download_url` 作为下载地址。校验文件则使用对应 `.apk.sha256` 附件。读取公开仓库不需要在前端放令牌。

```sh
gh api repos/hyperMoss/qiafan/releases/latest --jq '{version: .tag_name, apk: ([.assets[] | select(.name | endswith(".apk"))][0].browser_download_url)}'
```

[最新发布页](https://github.com/hyperMoss/qiafan/releases/latest) 可作请求失败时的后备入口。GitHub 也支持 `releases/latest/download/固定文件名` 的[最新附件链接](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)，但本项目目前附件名带版本，例如 `qiafan-v1.0.3.apk`，不能把其中的版本替换成 `latest` 或通配符直接下载。采用固定下载链接需要工作流另上传统一命名的附件。

本轮接入官网准备脚本；官网当前仍使用构建时写入的版本和二维码，尚未改为运行时读取 API。

标签仅接受 `vmajor.minor.patch`，暂不接受预发布后缀。CI 使用标签版本写入 APK 的 `versionName`；本地构建使用 `VERSION`。`versionCode = major × 1000000 + minor × 1000 + patch`，major 范围 0..2000，minor/patch 范围 0..999。每次更新须使用更大的版本号；已发布标签保持不变。

正式包名是 `com.example.qiafan`，Debug 包名为 `com.example.qiafan.dev`。正式签名与 Debug 签名各自独立。

当前通过 GitHub 分发 APK，Release lint 仅豁免 Google Play 目标 API 上架检查 `ExpiredTargetSdkVersion`，其它检查保留。未升级已有 `targetSdk=30`；本配置不代表满足 Google Play 上架要求。

## 本机正式构建

在终端设置 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 环境变量，然后运行：

```sh
REQUIRE_RELEASE_SIGNING=true ./gradlew :androidApp:assembleRelease
```

产物是 `androidApp/build/outputs/apk/release/androidApp-release.apk`。不要将密码写进 Gradle 文件、命令参数或仓库。无签名环境变量的普通本地 release 构建仍生成 unsigned APK；自动发布强制使用正式签名。

## 验证记录

- 2026-10-13：官网联动通过 ShellCheck 和 15 项脚本回归，新增验证准确版本传入、发布失败不调用官网、官网失败可继续、忽略的本机路径配置、跳过官网及旧版本不降级。本机 `--dry-run` 正确识别配置的官网目录；测试使用临时 Git 仓库及替身官网脚本，未运行真实官网构建或 WorkBuddy 线上发布。
- 2026-10-13：`release.sh` 通过 ShellCheck 和 9 项本机回归，覆盖预演、非法版本、脏工作区、版本提交及准确标签推送、已有标签继续、本机标签补推、工作流失败、缺少 Assets、失败重跑和工作流未出现。测试使用临时 Git 仓库；未创建或推送真实新版本。
- 2026-10-13：实际执行 `./release.sh 1.0.2 --resume`，只读检查已有远端标签，定位到提交 `728b00e` 的成功发布工作流 `38381984674`，并确认公开 Release 中 APK 与 SHA-256 Assets 均非空。未提交、推送或重跑该工作流；这项检查验证真实 GitHub 查询路径，新版本发布和失败重跑仍只有本机模拟验证。
- 2026-10-13：[v1.0.2 发布工作流](https://github.com/hyperMoss/qiafan/actions/runs/38381984674) 全部通过。[Release Assets](https://github.com/hyperMoss/qiafan/releases/tag/v1.0.2) 包含 `qiafan-v1.0.2.apk`（3,387,071 字节）和 `.apk.sha256`。下载后的 APK 验签通过，包名为 `com.example.qiafan`，版本为 `1.0.2`，versionCode 为 `1000002`，未开启 Debug。SHA-256 与附件一致，为 `754bdd3bff57149c361f065cad6182ae0322d570f0c98f0fe05a49cf59bc31f7`。保留已有 `v1.0.1` 标签及附件，新版本包含正文选择、网页浏览、搜索加粗、侧栏与工具条位移修复。
- 2026-10-08：两个工作流通过 actionlint；本机临时测试证书下 `assembleRelease` 成功，apksigner 验签通过，并确认正式包名 `com.example.qiafan`、标签覆盖版本 `1.0.1` 和 versionCode `1000001`。测试证书已删除，不用于正式发布。
- 2026-10-08：正式证书及四个签名 Secrets 已配置；本机正式签名构建和验签通过。[Android CI](https://github.com/hyperMoss/qiafan/actions/runs/37793359730) 的 Debug 与 Release 构建通过；[Android Release](https://github.com/hyperMoss/qiafan/actions/runs/37794322295) 正式签名构建、包信息校验及 Assets 上传全部通过。
- [v1.0.1 Release](https://github.com/hyperMoss/qiafan/releases/tag/v1.0.1) 的 Assets 包含 `qiafan-v1.0.1.apk`（3,380,054 字节）和 `.apk.sha256`。下载后的 APK 验签通过，证书与本机正式证书一致；包名 `com.example.qiafan`、版本 `1.0.1`、versionCode `1000001`，未开启 Debug。SHA-256 与附件一致：`e7673630460bed9d8e705e57dbdb550f80337cf903c45818f9ef8c4372ceb1e5`。本次验证发布产物，不替代真实饭否功能及设备触控验收。
