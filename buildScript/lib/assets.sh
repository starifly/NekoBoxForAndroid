#!/bin/bash

set -e
set -o pipefail

GEOIP_VERSION="${GEOIP_VERSION:-20260612}"
GEOIP_SHA256="${GEOIP_SHA256:-71484cf35bb48453e26bcc3373a0988a2536588f8e3ca96cda59ff742af6c392}"
GEOSITE_VERSION="${GEOSITE_VERSION:-20260625041655}"
GEOSITE_SHA256="${GEOSITE_SHA256:-7e4220f1700bcb63204b11c9a5a07d1c315d1262c3e0049f23d548b0b7b0343a}"

sha256_tool() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

download_verified() {
  local url="$1" output="$2" expected="$3" tmp actual
  tmp="${output}.download"
  rm -f "$tmp"
  curl -fL --retry 3 --retry-delay 2 --max-time 300 "$url" -o "$tmp"
  actual="$(sha256_tool "$tmp")"
  if [ "$expected" != "$actual" ]; then
    rm -f "$tmp"
    echo "Error: checksum mismatch for $output (expected $expected, got $actual)" >&2
    exit 1
  fi
  mv "$tmp" "$output"
}

DIR=app/src/main/assets/sing-box
rm -rf "$DIR"
mkdir -p "$DIR"
cd "$DIR"

####
echo VERSION_GEOIP=$GEOIP_VERSION
echo -n "$GEOIP_VERSION" > geoip.version.txt
download_verified \
  "https://github.com/SagerNet/sing-geoip/releases/download/$GEOIP_VERSION/geoip.db" \
  geoip.db \
  "$GEOIP_SHA256"
xz -9 geoip.db

####
echo VERSION_GEOSITE=$GEOSITE_VERSION
echo -n "$GEOSITE_VERSION" > geosite.version.txt
download_verified \
  "https://github.com/SagerNet/sing-geosite/releases/download/$GEOSITE_VERSION/geosite.db" \
  geosite.db \
  "$GEOSITE_SHA256"
xz -9 geosite.db
