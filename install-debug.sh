#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$project_root"

# installDebug builds the Debug APK and installs it on connected Android devices.
./gradlew :androidApp:installDebug --no-daemon "$@"

printf '\nDebug APK: %s\n' "$project_root/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
printf 'Application ID: com.example.qiafan.dev\n'
