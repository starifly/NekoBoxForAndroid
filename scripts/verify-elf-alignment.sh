#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <libcore.aar>" >&2
    exit 2
fi

aar=$1

if [ ! -f "$aar" ]; then
    echo "Error: AAR not found: $aar" >&2
    exit 1
fi

for command in unzip readelf; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Error: required command is unavailable: $command" >&2
        exit 1
    fi
done

tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
unzip -o "$aar" -d "$tmp" >/dev/null

failed=0
for abi in arm64-v8a x86_64; do
    so="$tmp/jni/$abi/libgojni.so"
    if [ ! -f "$so" ]; then
        echo "::error::missing required native lib jni/$abi/libgojni.so" >&2
        failed=1
        continue
    fi

    readelf_output=$(readelf -lW "$so")
    min=""
    for alignment in $(awk '/LOAD/{print $NF}' <<< "$readelf_output"); do
        decimal=$(printf '%d' "$alignment")
        if [ -z "$min" ] || [ "$decimal" -lt "$min" ]; then
            min=$decimal
        fi
    done

    if [ -z "$min" ]; then
        echo "::error::$abi libgojni.so has no LOAD segments" >&2
        failed=1
        continue
    fi

    if [ "$min" -lt 16384 ]; then
        echo "::error::$abi libgojni.so is NOT 16 KB aligned ($min)" >&2
        failed=1
        continue
    fi

    printf '%s libgojni.so min LOAD align = %d (0x%x)\n' "$abi" "$min" "$min"
done

exit "$failed"
