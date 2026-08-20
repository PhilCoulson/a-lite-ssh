#!/usr/bin/env bash
# 每次发新版本运行一次：编译 release APK 并上传到 GitHub Releases
# 用法（在仓库根目录）：./scripts/publish-github-release.sh
# 前置：已运行 make-release-keystore.sh，并填好 keystore.properties；已 gh auth login
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [[ ! -f keystore.properties ]]; then
  echo "Missing keystore.properties. Copy keystore.properties.example and run scripts/make-release-keystore.sh" >&2
  exit 1
fi

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
