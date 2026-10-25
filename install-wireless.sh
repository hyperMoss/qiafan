#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_root"

usage() {
    cat <<'USAGE'
用法：./install-wireless.sh [选项] [-- Gradle参数]

  无选项                       自动发现已配对的无线设备，编译并安装 Debug APK
  --pair 地址:配对端口          配对新手机，运行时输入六位配对码
  --connect 地址:连接端口       连接已配对的手机
  --device 设备序列号           明确指定安装目标
  --apk 文件                   安装已有 APK，跳过编译
  --restart-adb                配对前重启 ADB 服务，会断开现有调试连接
  -h, --help                   显示帮助

配对端口与连接端口请分别从手机无线调试弹窗和主页面读取。
配对码不会写入文件或传给命令行参数。ADB 环境变量可指定 adb 可执行文件。
USAGE
}
fail() { printf '错误：%s\n' "$*" >&2; exit 1; }
validate_address() {
    [[ "$1" =~ ^[A-Za-z0-9._-]+:([0-9]{1,5})$ ]] || fail "地址须为 IP或主机名:端口"
    local port="${BASH_REMATCH[1]}"
    (( 10#$port > 0 && 10#$port <= 65535 )) || fail "端口须在 1..65535 范围内"
}

pair_address="" connect_address="" serial="" apk="" restart=false
gradle_args=()
while (( $# > 0 )); do
    case "$1" in
        --pair|--connect|--device|--apk)
            (( $# >= 2 )) && [[ -n "$2" && "$2" != -* ]] || fail "$1 缺少参数"
            case "$1" in
                --pair) pair_address="$2"; validate_address "$2" ;;
                --connect) connect_address="$2"; validate_address "$2" ;;
                --device) serial="$2" ;;
                --apk) apk="$2" ;;
            esac
            shift 2 ;;
        --restart-adb) restart=true; shift ;;
        -h|--help) usage; exit 0 ;;
        --) shift; gradle_args=("$@"); break ;;
        *) fail "未知选项 $1；用 --help 查看用法" ;;
    esac
done
if [[ -n "$apk" ]]; then
    [[ -f "$apk" ]] || fail "APK 不存在：$apk"
    (( ${#gradle_args[@]} == 0 )) || fail "--apk 跳过编译，不能同时指定 Gradle 参数"
fi
if [[ -z "${ADB:-}" ]]; then
    sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -z "$sdk_dir" && -f local.properties ]]; then
        sdk_dir="$(sed -n 's/^sdk.dir=//p' local.properties | head -n 1)"
    fi
    if [[ -n "$sdk_dir" && -x "$sdk_dir/platform-tools/adb" ]]; then
        ADB="$sdk_dir/platform-tools/adb"
    else
        ADB="$(command -v adb)" || fail "未找到 adb，请配置 Android SDK 或 ADB 环境变量"
    fi
fi
command -v "$ADB" >/dev/null || fail "无法执行 adb：$ADB"
if "$restart"; then
    "$ADB" kill-server
    "$ADB" start-server
fi

paired_guid=""
if [[ -n "$pair_address" ]]; then
    printf '六位配对码：' >&2
    pair_code=""
    if [[ -t 0 ]]; then
        IFS= read -r -s pair_code || fail "未读取到配对码"
    else
        IFS= read -r pair_code || fail "未读取到配对码"
    fi
    printf '\n' >&2
    [[ "$pair_code" =~ ^[0-9]{6}$ ]] || fail "配对码须为六位数字"
    if ! pair_output="$(printf '%s\n' "$pair_code" | "$ADB" pair "$pair_address" 2>&1)"; then
        unset pair_code
        printf '%s\n' "$pair_output" >&2
        fail "配对失败。请检查同一 Wi-Fi、弹窗端口和配对码；旧服务异常时可重试 --restart-adb"
    fi
    unset pair_code
    printf '%s\n' "$pair_output"
    paired_guid="$(printf '%s\n' "$pair_output" | sed -n 's/.*\[guid=\([^]]*\)\].*/\1/p')"
fi

if [[ -n "$connect_address" ]]; then
    "$ADB" connect "$connect_address"
    [[ -n "$serial" ]] || serial="$connect_address"
fi
if [[ -z "$serial" ]]; then
    # 配对成功后的 mDNS 自动连接可能稍晚出现，最多等待六秒。
    for attempt in 1 2 3 4; do
        online="$("$ADB" devices | awk 'NR > 1 && $2 == "device" && ($1 ~ /:/ || $1 ~ /\._adb-tls-connect\._tcp$/) {print $1}')"
        if [[ -n "$paired_guid" ]]; then
            online="$(printf '%s\n' "$online" | awk -v guid="$paired_guid" '$0 == guid "._adb-tls-connect._tcp" {print}')"
        fi
        [[ -z "$online" ]] || break
        services="$("$ADB" mdns services 2>/dev/null || true)"
        candidates="$(printf '%s\n' "$services" | awk -v guid="$paired_guid" '$2 == "_adb-tls-connect._tcp" && (guid == "" || $1 == guid) {print $3}' | sort -u)"
        count="$(printf '%s\n' "$candidates" | awk 'NF {n++} END {print n+0}')"
        if (( count > 1 )); then fail "发现多台无线设备，请用 --connect 或 --device 指定手机"; fi
        if (( count == 1 )); then
            validate_address "$candidates"
            "$ADB" connect "$candidates"
            if [[ "$("$ADB" -s "$candidates" get-state 2>/dev/null || true)" == "device" ]]; then
                online="$candidates"
                break
            fi
        fi
        if (( attempt < 4 )); then sleep 2; fi
    done
    count="$(printf '%s\n' "$online" | awk 'NF {n++} END {print n+0}')"
    (( count > 0 )) || fail "未发现在线无线手机。已配对请用 --connect 主页面IP:连接端口；首次连接请用 --pair"
    (( count == 1 )) || fail "发现多台在线无线设备，请用 --device 指定目标"
    serial="$online"
fi
[[ "$("$ADB" -s "$serial" get-state 2>/dev/null || true)" == "device" ]] || fail "目标设备未就绪：${serial}；请检查连接端口与手机授权"
printf '安装目标：%s\n' "$serial"

if [[ -z "$apk" ]]; then
    if (( ${#gradle_args[@]} > 0 )); then
        ./gradlew :androidApp:assembleDebug --no-daemon "${gradle_args[@]}"
    else
        ./gradlew :androidApp:assembleDebug --no-daemon
    fi
    apk="$project_root/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    printf '本次构建为测试版 com.example.qiafan.dev，与正式版并存。\n'
fi
[[ -f "$apk" ]] || fail "未找到 APK：$apk"
"$ADB" -s "$serial" install -r "$apk"
printf '\n安装完成：%s\n设备：%s\n' "$apk" "$serial"
