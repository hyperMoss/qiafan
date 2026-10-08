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

1. 更新根目录 `VERSION`，例如 `1.0.1`，完成测试并提交当前版本代码。
2. 推送代码，再推送同名版本标签：

```sh
git push origin master
git tag -a v1.0.1 -m "恰饭 1.0.1"
git push origin v1.0.1
```

3. 在 GitHub Actions 查看 Android Release；成功后到 Releases 下载 `qiafan-v1.0.1.apk`。

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

- 2026-10-08：两个工作流通过 actionlint；本机临时测试证书下 `assembleRelease` 成功，apksigner 验签通过，并确认正式包名 `com.example.qiafan`、标签覆盖版本 `1.0.1` 和 versionCode `1000001`。测试证书已删除，不用于正式发布。
- 正式签名 Secrets、GitHub Actions 云端构建及首次 Release 发布仍须实际执行后确认；本地通过不代表云端已验收。
