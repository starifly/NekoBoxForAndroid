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

# gomobile toolchain pin. Resolved from MatsuriDayo/gomobile master2 and pinned to
# an immutable commit for reproducible JNI-bridge generation. Bump deliberately.
GOMOBILE_COMMIT="${GOMOBILE_COMMIT:-17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f}"

# Install gomobile
if [ ! -f "$TARGET_BIN/gomobile-matsuri" ] || [ ! -f "$TARGET_BIN/gobind-matsuri" ]; then
    # Fresh checkout dir every time so a partial/stale clone from an interrupted
    # prior run can't be reused; fail fast if any git step fails rather than
    # building from wrong/absent sources.
    rm -rf gomobile
    git init -q gomobile
    git -C gomobile remote add origin https://github.com/MatsuriDayo/gomobile.git
    git -C gomobile fetch --depth 1 origin "$GOMOBILE_COMMIT" || exit 1
    git -C gomobile checkout -q FETCH_HEAD || exit 1
    pushd gomobile

    # Keep gobind bootstrap compatible with Go 1.24.x in CI environments.
    sed -i 's|golang.org/x/mobile/cmd/gobind@latest|golang.org/x/mobile/cmd/gobind@v0.0.0-20231108233038-35478a0c49da|g' cmd/gomobile/init.go
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
