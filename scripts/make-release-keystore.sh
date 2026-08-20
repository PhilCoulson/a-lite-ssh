#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
out="$root/release.jks"
if [[ -f "$out" ]]; then
  echo "release.jks already exists; refusing to overwrite." >&2
  exit 1
fi
keytool -genkeypair -v \
  -keystore "$out" \
  -alias alite \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10950 \
  -dname "CN=A-Lite SSH"
cp -n keystore.properties.example keystore.properties
echo
echo "Created $out"
echo "Fill storePassword / keyPassword in keystore.properties."
echo "For GitHub Actions, add secrets:"
echo "  RELEASE_KEYSTORE_BASE64=$(base64 -w0 "$out" 2>/dev/null || base64 < "$out")"
echo "  RELEASE_STORE_PASSWORD"
echo "  RELEASE_KEY_ALIAS=alite"
echo "  RELEASE_KEY_PASSWORD"
