#!/bin/bash
# Download the official apernet/hysteria Android client binaries and install them as
# bundled native executables (app/executableSo/<abi>/libhysteria2.so).
#
# Used for Hysteria2 profiles with Gecko obfs, which the native (starifly) sing-box core
# does not support. PluginManager.initNativeInternal resolves
# "hysteria2-plugin" -> libhysteria2.so from nativeLibraryDir.
#
# Usage: ./run lib hysteria2
set -e
set -o pipefail

# Pinned Hysteria release (app/v2.9.2 introduced Gecko obfs).
HYSTERIA_VERSION="${HYSTERIA_VERSION:-v2.9.2}"
BASE="https://github.com/apernet/hysteria/releases/download/app/${HYSTERIA_VERSION}"

OUT="$(pwd)/app/executableSo"

# Fetch the official checksums once for integrity verification.
HASHES="$(curl -fsSL --retry 3 --retry-delay 2 --max-time 120 "$BASE/hashes.txt")"

sha256_tool() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

dl() {
  local abi="$1" asset="$2"
  echo ">> downloading libhysteria2.so for $abi ($asset)"
  mkdir -p "$OUT/$abi"
  curl -fL --retry 3 --retry-delay 2 --max-time 300 "$BASE/$asset" -o "$OUT/$abi/libhysteria2.so"
  # Verify against the official SHA256 (hashes.txt lines: "<sha>  build/<asset>").
  local expected actual
  expected="$(printf '%s\n' "$HASHES" | awk -v a="build/$asset" '$2==a {print $1}')"
  if [ -z "$expected" ]; then
    echo "Error: no checksum for $asset in hashes.txt" >&2
    exit 1
  fi
  actual="$(sha256_tool "$OUT/$abi/libhysteria2.so")"
  if [ "$expected" != "$actual" ]; then
    echo "Error: checksum mismatch for $asset (expected $expected, got $actual)" >&2
    exit 1
  fi
  chmod +x "$OUT/$abi/libhysteria2.so"
}

dl "arm64-v8a"   "hysteria-android-arm64"
dl "armeabi-v7a" "hysteria-android-armv7"
dl "x86"         "hysteria-android-386"
dl "x86_64"      "hysteria-android-amd64"

echo ">> installed Hysteria2 binaries:"
ls -la "$OUT"/*/libhysteria2.so
