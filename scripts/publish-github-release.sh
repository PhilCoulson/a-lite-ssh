#!/usr/bin/env bash
# 每次发新版本运行一次：编译 release APK 并上传到 GitHub Releases
# 用法（在仓库根目录）：./scripts/publish-github-release.sh
# 前置：已运行 make-release-keystore.sh，并填好 keystore.properties；已 gh auth login
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [[ ! -f keystore.properties ]]; then
  echo "缺少 keystore.properties。请先运行 ./scripts/make-release-keystore.sh 并填好密码。" >&2
  exit 1
fi

find_android_sdk() {
  local candidate
  for candidate in \
    "${ANDROID_HOME:-}" \
    "${ANDROID_SDK_ROOT:-}" \
    "$HOME/android-sdk" \
    "$HOME/Android/Sdk" \
    /home/ubuntu/android-sdk \
    /opt/android-sdk; do
    if [[ -n "$candidate" && -d "$candidate/platform-tools" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

if ! sdk_dir="$(find_android_sdk)"; then
  echo "找不到 Android SDK，所以 Gradle 无法打包。" >&2
  echo "处理：安装 Android Studio，或设置环境变量 ANDROID_HOME 指向 SDK 目录。" >&2
  echo "这个环境里常见路径是 \$HOME/Android/Sdk 或 /home/ubuntu/android-sdk。" >&2
  exit 1
fi
export ANDROID_HOME="$sdk_dir"
export ANDROID_SDK_ROOT="$sdk_dir"
printf 'sdk.dir=%s\n' "$sdk_dir" > local.properties
echo "Using Android SDK at $sdk_dir"

./gradlew :app:assembleRelease :app:writeUpdateMetadata

apk="$(ls -1 app/build/outputs/apk/release/*.apk | head -n 1)"
json="app/build/outputs/apk/release/version.json"
if [[ ! -f "$apk" || ! -f "$json" ]]; then
  echo "Release artifacts were not produced." >&2
  exit 1
fi

version_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionName"])' "$json")"
version_code="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionCode"])' "$json")"
tag="v${version_name}"
named="app/build/outputs/apk/release/a-lite-ssh-${version_name}.apk"
cp -f "$apk" "$named"

notes_file="$(mktemp)"
trap 'rm -f "$notes_file"' EXIT
if [[ $# -gt 0 ]]; then
  printf '%s\n' "$*" > "$notes_file"
else
  printf 'A-Lite SSH %s\n' "$version_name" > "$notes_file"
fi

gh release create "$tag" \
  --title "${version_name} (${version_code})" \
  --notes-file "$notes_file" \
  "$named" \
  "$json"

echo "Published $tag"
echo "Install this APK once on the phone, then later versions can update in-app."
