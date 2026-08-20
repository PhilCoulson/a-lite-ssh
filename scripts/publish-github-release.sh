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

apk="app/build/outputs/apk/release/app-release.apk"
meta="app/build/outputs/apk/release/output-metadata.json"
json="app/build/outputs/apk/release/version.json"
if [[ ! -f "$apk" ]]; then
  echo "没有找到 $apk，打包没有生成安装包。" >&2
  exit 1
fi
if [[ ! -f "$meta" ]]; then
  echo "没有找到 $meta，无法读取版本号。" >&2
  exit 1
fi

python3 - "$meta" "$json" <<'PY'
import json, sys
from pathlib import Path
meta = json.loads(Path(sys.argv[1]).read_text())
el = meta["elements"][0]
payload = {"versionCode": el["versionCode"], "versionName": el["versionName"]}
Path(sys.argv[2]).write_text(json.dumps(payload) + "\n")
print(payload["versionName"], payload["versionCode"])
PY

version_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionName"])' "$json")"
version_code="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionCode"])' "$json")"
tag="v${version_name}"
named="app/build/outputs/apk/release/a-lite-ssh-${version_name}.apk"
cp -f "$apk" "$named"

echo "已生成 $named （${version_name} / ${version_code}）"
echo "正在上传到 GitHub Releases…"

notes_file="$(mktemp)"
trap 'rm -f "$notes_file"' EXIT
if [[ $# -gt 0 ]]; then
  printf '%s\n' "$*" > "$notes_file"
else
  printf 'A-Lite SSH %s\n' "$version_name" > "$notes_file"
fi

if gh release view "$tag" >/dev/null 2>&1; then
  echo "Release $tag 已存在，改为上传安装包。" >&2
  gh release upload "$tag" "$named" "$json" --clobber
else
  gh release create "$tag" \
    --title "${version_name} (${version_code})" \
    --notes-file "$notes_file" \
    "$named" \
    "$json"
fi

echo "发布完成：$tag"
echo "打开查看：https://github.com/PhilCoulson/a-lite-ssh/releases/tag/${tag}"
echo "手机请先卸载旧的 debug 包，再安装这个 Release 里的 APK。之后即可应用内更新。"
