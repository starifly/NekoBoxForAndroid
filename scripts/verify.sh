#!/usr/bin/env bash
# One-command local verification gate. Runs ONLY the JVM-safe checks allowed for
# local runs (format, compile, unit tests). Never assembles an APK (that is
# Namespace/Depot only). Always stops the Gradle daemon at the end.
#
# Optional pre-push hook install (manual, not auto-wired):
#   ln -s ../../scripts/hooks/pre-push .git/hooks/pre-push
set -euo pipefail

cd "$(dirname "$0")/.."

cleanup() { ./gradlew --stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo ">> spotlessCheck"
./gradlew spotlessCheck

echo ">> compileOssDebugKotlin"
./gradlew :app:compileOssDebugKotlin

echo ">> testOssDebugUnitTest"
./gradlew :app:testOssDebugUnitTest

echo "OK: local verification passed."
