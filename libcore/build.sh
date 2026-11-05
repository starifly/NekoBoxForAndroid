#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri
# 内核升级会改动依赖矩阵，允许 go 自动补全 go.sum
export GOFLAGS="-mod=mod"
# 可选：GOMOBILE_TARGET 限定 ABI（如 "android/arm64"），不设置时保持原有全量编译
TARGET_ARGS=()
if [ -n "${GOMOBILE_TARGET:-}" ]; then
  TARGET_ARGS=("-target=$GOMOBILE_TARGET")
fi
"$GOPATH"/bin/gomobile-matsuri bind "${TARGET_ARGS[@]}" -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
