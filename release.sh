#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_root"

usage() {
    cat <<'USAGE'
用法：./release.sh [版本号] [--dry-run | --resume | --retry] [--site-dir 目录 | --no-site]

  ./release.sh 1.0.3             更新 VERSION、提交版本、打标签并推送，等待发布
  ./release.sh 1.0.3 --dry-run   只显示计划，不提交、打标签或推送
  ./release.sh 1.0.3 --resume    继续已有标签的推送或发布检查
  ./release.sh 1.0.3 --retry     继续已有标签，重跑失败的发布工作流
  ./release.sh 1.0.3 --site-dir /path/to/qiafan-site  发布成功后运行官网 deploy.sh
  ./release.sh 1.0.3 --resume --no-site              只检查 APK，跳过官网脚本

版本号可带 v 前缀；省略时读取 VERSION。新发布要求 master 分支且工作区干净。
只自动提交 VERSION；功能代码请先自行提交。需要 git 和已登录的 GitHub CLI。
官网目录可通过 QIAFAN_SITE_DIR 或本机 .release-site-path 配置；deploy.sh 仅准备网站上线。
USAGE
}

fail() { printf '错误：%s\n' "$*" >&2; exit 1; }
version_code() {
    local value="$1"
    [[ "$value" =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$ ]] || return 1
    local major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}"
    (( major <= 2000 && major * 1000000 + minor * 1000 + patch > 0 )) || return 1
    printf '%s\n' "$((major * 1000000 + minor * 1000 + patch))"
}

version="" mode="new" site_dir="${QIAFAN_SITE_DIR:-}"
if [[ -z "$site_dir" && -f .release-site-path ]]; then
    IFS= read -r site_dir < .release-site-path || true
fi
while (( $# > 0 )); do
    arg="$1"
    case "$arg" in
        -h|--help) usage; exit 0 ;;
        --dry-run|--resume|--retry)
            [[ "$mode" == "new" ]] || fail "只能选择一个运行模式"
            mode="${arg#--}" ;;
        --site-dir)
            (( $# >= 2 )) && [[ -n "$2" && "$2" != -* ]] || fail "--site-dir 需要官网目录"
            site_dir="$2"; shift ;;
        --no-site) site_dir="" ;;
        -*) fail "未知参数 $arg" ;;
        *) [[ -z "$version" ]] || fail "只能指定一个版本号"; version="${arg#v}" ;;
    esac
    shift
done
[[ -n "$version" ]] || version="$(tr -d '\r\n' < VERSION)"
code="$(version_code "$version")" || fail "版本须为 major.minor.patch，范围 0..2000 / 0..999 / 0..999，且大于 0.0.0"
tag="v$version"
command -v git >/dev/null || fail "请安装 git"
git rev-parse --is-inside-work-tree >/dev/null
git diff --check
branch="$(git symbolic-ref --short -q HEAD)" || fail "请先切回 master 分支"
dirty="$(git status --porcelain)"
if [[ -n "$site_dir" ]]; then
    [[ -f "$site_dir/deploy.sh" ]] || fail "官网目录不存在 deploy.sh，请检查 --site-dir 或 .release-site-path"
    site_dir="$(cd "$site_dir" && pwd)"
fi

if [[ "$mode" == "dry-run" ]]; then
    printf '预演：版本 %s，标签 %s，versionCode %s，当前分支 %s\n' "$version" "$tag" "$code" "$branch"
    printf '计划：校验远端版本 → 提交 VERSION → 创建标签 → 原子推送 master 和标签 → 等待 Android Release → 检查 APK 和 SHA-256 Assets\n'
    [[ -z "$dirty" ]] || printf '实际发布前请提交或暂存到 stash 当前改动：\n%s\n' "$dirty"
    printf '实际发布时会检查 master 分支、GitHub 登录、远端分支和标签冲突。预演不访问远端。\n'
    [[ -z "$site_dir" ]] || printf 'APK 发布成功且仍为最新版本后，运行 %s/deploy.sh，传入 VERSION=%s；不执行线上部署。\n' "$site_dir" "$version"
    exit 0
fi

command -v gh >/dev/null || fail "请安装 GitHub CLI：brew install gh"
remote_url="$(git remote get-url --push origin)"
repo="$(gh repo view "$remote_url" --json nameWithOwner --jq .nameWithOwner)"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail "无法识别 origin 对应的 GitHub 仓库，请检查 gh auth login"
remote_tags="$(git ls-remote --tags "$remote_url")"
remote_commit="$(printf '%s\n' "$remote_tags" | awk -v ref="refs/tags/$tag" '$2 == ref { plain=$1 } $2 == ref "^{}" { peeled=$1 } END { print peeled ? peeled : plain }')"

trap 'printf "流程未完成。已有标签时可运行 ./release.sh %s --resume；工作流失败可用 --retry。已完成的提交或推送会保留。\n" "$version" >&2' ERR

if [[ "$mode" == "new" ]]; then
    [[ "$branch" == "master" ]] || fail "新版本须在 master 分支发布"
    [[ -z "$dirty" ]] || fail "工作区有未提交改动，请先提交功能代码或 stash，再运行脚本"
    [[ -z "$remote_commit" ]] || fail "$tag 已在远端存在；检查已有发布请用 --resume，不覆盖标签"
    if git show-ref --verify --quiet "refs/tags/$tag"; then
        fail "$tag 已在本机存在；继续上次流程请用 --resume"
    fi
    current="$(tr -d '\r\n' < VERSION)"
    current_code="$(version_code "$current")" || fail "VERSION 内容无效"
    (( code >= current_code )) || fail "新版本不能小于 VERSION 中的 $current"
    while read -r _ ref; do
        published="${ref#refs/tags/v}"
        if published_code="$(version_code "$published")"; then
            (( code > published_code )) || fail "版本必须大于远端已有的 v$published"
        fi
    done <<< "$remote_tags"
    git fetch --no-tags "$remote_url" master
    git merge-base --is-ancestor FETCH_HEAD HEAD || fail "master 落后或分叉，请先同步代码"
    git cat-file -e HEAD:.github/workflows/android-release.yml
    if [[ "$current" != "$version" ]]; then
        printf '%s\n' "$version" > VERSION
        git add -- VERSION
        git commit -m "release: bump version to $version" --only -- VERSION
    fi
    git tag -a "$tag" -m "恰饭 $version"
    tag_commit="$(git rev-parse "$tag^{commit}")"
else
    if [[ -n "$remote_commit" ]]; then
        tag_commit="$remote_commit"
    else
        tag_commit="$(git rev-parse --verify "$tag^{commit}")" || fail "没有可继续的 $tag 标签"
        [[ "$(git show "$tag_commit:VERSION")" == "$version" ]] || fail "本机标签 VERSION 不匹配"
    fi
fi

if [[ -z "$remote_commit" ]]; then
    printf '推送 %s 到 %s，提交 %s\n' "$tag" "$repo" "$tag_commit"
    git push --atomic --no-follow-tags "$remote_url" "$tag_commit:refs/heads/master" "refs/tags/$tag:refs/tags/$tag"
fi

run_id=""
printf '等待 %s 的 Android Release 工作流…\n' "$tag"
for (( attempt=0; attempt<30; attempt++ )); do
    run_id="$(gh run list --repo "$repo" --workflow android-release.yml --branch "$tag" --commit "$tag_commit" --event push --limit 20 --json databaseId --jq '.[0].databaseId // empty')"
    [[ -z "$run_id" ]] || break
    sleep 3
done
[[ -n "$run_id" ]] || fail "90 秒内未发现发布工作流；请查看仓库 Actions，然后用 --resume 继续"
printf 'Actions：https://github.com/%s/actions/runs/%s\n' "$repo" "$run_id"

if [[ "$mode" == "retry" ]]; then
    conclusion="$(gh run view "$run_id" --repo "$repo" --json conclusion --jq .conclusion)"
    if [[ -n "$conclusion" && "$conclusion" != "success" ]]; then
        previous_attempt="$(gh run view "$run_id" --repo "$repo" --json attempt --jq .attempt)"
        gh run rerun "$run_id" --repo "$repo"
        restarted=false
        for (( attempt=0; attempt<30; attempt++ )); do
            current_attempt="$(gh run view "$run_id" --repo "$repo" --json attempt --jq .attempt)"
            if (( current_attempt > previous_attempt )); then restarted=true; break; fi
            sleep 2
        done
        "$restarted" || fail "尚未确认工作流开始重跑，请稍后用 --resume 继续"
    fi
fi

if ! gh run watch "$run_id" --repo "$repo" --exit-status --interval 10; then
    fail "工作流未成功，请查看上述 Actions 链接；修复后可用 --retry 重跑"
fi
conclusion="$(gh run view "$run_id" --repo "$repo" --json conclusion --jq .conclusion)"
[[ "$conclusion" == "success" ]] || fail "工作流状态为 $conclusion，未确认发布成功"

assets_ok="$(gh release view "$tag" --repo "$repo" --json tagName,isDraft,assets --jq "(.tagName == \"$tag\") and (.isDraft == false) and ([.assets[] | select(.name == \"qiafan-$tag.apk\" and .size > 0)] | length > 0) and ([.assets[] | select(.name == \"qiafan-$tag.apk.sha256\" and .size > 0)] | length > 0)")"
[[ "$assets_ok" == "true" ]] || fail "Release Assets 缺少非空 APK 或 SHA-256 文件，未确认发布完成"
release_url="$(gh release view "$tag" --repo "$repo" --json url --jq .url)"
printf '\n发布完成：%s\n' "$release_url"
printf 'Assets：qiafan-%s.apk、qiafan-%s.apk.sha256\n' "$tag" "$tag"

if [[ -n "$site_dir" ]]; then
    latest_tag="$(gh release view --repo "$repo" --json tagName --jq .tagName)"
    if [[ "$latest_tag" != "$tag" ]]; then
        printf '官网同步已跳过：最新 Release 是 %s，本次检查的是 %s，保留官网当前版本。\n' "$latest_tag" "$tag"
        exit 0
    fi
    printf '\n准备官网 %s，版本 %s…\n' "$site_dir" "$version"
    if ! VERSION="$version" RELEASE_TAG="$tag" RELEASE_URL="$release_url" bash "$site_dir/deploy.sh"; then
        fail "APK 已发布，但官网脚本失败；修复后用 ./release.sh $version --resume 重试官网准备，不必重发标签"
    fi
    printf '官网脚本已完成。本机版本、二维码和构建已准备；WorkBuddy 线上发布仍需另行完成。\n'
fi
