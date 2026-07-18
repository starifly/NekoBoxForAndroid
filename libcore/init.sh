#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi
BIN_DIR=$(go env GOBIN)
if [ -z "$BIN_DIR" ]; then
    BIN_DIR="$GOPATH/bin"
fi
TARGET_BIN="$GOPATH/bin"
mkdir -p "$TARGET_BIN"

GOMOBILE_COMMIT="${GOMOBILE_COMMIT:-17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f}"

if [ ! -f "$TARGET_BIN/gomobile-matsuri" ] || [ ! -f "$TARGET_BIN/gobind-matsuri" ]; then
    rm -rf gomobile
    git init -q gomobile
    git -C gomobile remote add origin https://github.com/MatsuriDayo/gomobile.git
    git -C gomobile fetch --depth 1 origin "$GOMOBILE_COMMIT" || exit 1
    git -C gomobile checkout -q FETCH_HEAD || exit 1
    pushd gomobile

    # Fix: upgrade x/tools for Go 1.26+ compatibility
    go get golang.org/x/tools@latest
    go mod tidy

    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    rm -rf gomobile
    cp -f "$BIN_DIR/gomobile" "$TARGET_BIN/gomobile-matsuri"
    cp -f "$BIN_DIR/gobind" "$TARGET_BIN/gobind-matsuri"
fi

export PATH="$TARGET_BIN:$PATH"
GOBIND=gobind-matsuri gomobile-matsuri init
