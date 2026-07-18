#!/bin/bash
# Extract the official naiveproxy client binary from klzgrad/naiveproxy plugin
# APKs and install it as a bundled native executable
# (app/executableSo/<abi>/libnaive.so).
#
# This lets NekoBox run NaïveProxy profiles without installing the separate
# external plugin (moe.matsuri.exe.naive). PluginManager.initNativeInternal
# resolves "naive-plugin" -> libnaive.so from nativeLibraryDir, mirroring the
# bundled Mieru / Hysteria mechanism, and falls back to the external APK plugin
# when the bundled binary is absent.
#
# Usage: ./run lib naive
set -e
set -o pipefail

# Pinned naiveproxy release. The SHA256 values below are for the downloaded
# plugin APKs for this exact version; update them in the same change as any
# NAIVE_VERSION bump.
NAIVE_VERSION="${NAIVE_VERSION:-v149.0.7827.114-1}"
NAIVE_SHA256_ARM64_V8A="${NAIVE_SHA256_ARM64_V8A:-07f58c14849f3fb047d342fdc8e34d65a745a133f436469673f29624bba87f6a}"
NAIVE_SHA256_ARMEABI_V7A="${NAIVE_SHA256_ARMEABI_V7A:-be0e126d2631a0a4c8f9140595243f51a8c676c0756deb67144677ebfe7d7202}"
NAIVE_SHA256_X86="${NAIVE_SHA256_X86:-82a3b8ef29876ccaa6f7df4dc3dabfaa92eb954a7de8a3e0ff93f92afc17e9ca}"
NAIVE_SHA256_X86_64="${NAIVE_SHA256_X86_64:-7957af60ac3bedaf6bd35c172297bd9e730b90ac25f6bd26fa19a4591ceec13a}"
BASE="https://github.com/klzgrad/naiveproxy/releases/download/${NAIVE_VERSION}"

OUT="$(pwd)/app/executableSo"
WORK="$(pwd)/.naive-build"
mkdir -p "$WORK"

sha256_tool() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

expected_sha256_for_abi() {
  case "$1" in
    arm64-v8a) echo "$NAIVE_SHA256_ARM64_V8A" ;;
    armeabi-v7a) echo "$NAIVE_SHA256_ARMEABI_V7A" ;;
    x86) echo "$NAIVE_SHA256_X86" ;;
    x86_64) echo "$NAIVE_SHA256_X86_64" ;;
    *) echo "Error: unsupported ABI $1" >&2; exit 1 ;;
  esac
}

verify_sha256() {
  local file="$1" expected="$2" actual
  actual="$(sha256_tool "$file")"
  if [ "$expected" != "$actual" ]; then
    echo "Error: checksum mismatch for $(basename "$file") (expected $expected, got $actual)" >&2
    exit 1
  fi
}

# Map Android ABI -> naiveproxy plugin APK ABI tag (identical here).
extract_abi() {
  local abi="$1"
  local apk="naiveproxy-plugin-${NAIVE_VERSION}-${abi}.apk"
  local apk_path="$WORK/$apk"
  echo ">> fetching libnaive.so for $abi ($apk)"
  curl -fL --retry 3 --retry-delay 2 --max-time 300 "$BASE/$apk" -o "$apk_path"
  verify_sha256 "$apk_path" "$(expected_sha256_for_abi "$abi")"

  mkdir -p "$OUT/$abi"
  # The plugin APK ships the client as lib/<abi>/libnaive.so. Extract only after
  # the downloaded APK matches the pinned SHA256.
  unzip -o -j "$apk_path" "lib/$abi/libnaive.so" -d "$OUT/$abi" >/dev/null
  if [ ! -f "$OUT/$abi/libnaive.so" ]; then
    echo "Error: libnaive.so not found in $apk" >&2
    exit 1
  fi
  chmod +x "$OUT/$abi/libnaive.so"
}

extract_abi "arm64-v8a"
extract_abi "armeabi-v7a"
extract_abi "x86"
extract_abi "x86_64"

echo ">> installed naive binaries:"
ls -la "$OUT"/*/libnaive.so
