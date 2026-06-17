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

# Pinned naiveproxy release.
NAIVE_VERSION="${NAIVE_VERSION:-v149.0.7827.114-1}"
BASE="https://github.com/klzgrad/naiveproxy/releases/download/${NAIVE_VERSION}"

OUT="$(pwd)/app/executableSo"
WORK="$(pwd)/.naive-build"
mkdir -p "$WORK"

# Map Android ABI -> naiveproxy plugin APK ABI tag (identical here).
extract_abi() {
  local abi="$1"
  local apk="naiveproxy-plugin-${NAIVE_VERSION}-${abi}.apk"
  echo ">> fetching libnaive.so for $abi ($apk)"
  curl -fL --retry 3 --retry-delay 2 --max-time 300 "$BASE/$apk" -o "$WORK/$apk"
  mkdir -p "$OUT/$abi"
  # The plugin APK ships the client as lib/<abi>/libnaive.so.
  unzip -o -j "$WORK/$apk" "lib/$abi/libnaive.so" -d "$OUT/$abi" >/dev/null
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
