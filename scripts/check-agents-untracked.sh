#!/usr/bin/env bash
# Fail if AGENTS.md is tracked by git. AGENTS.md holds local-only credentials and must
# remain gitignored/untracked (maintainer decision: keep local, never publish). Shared by
# the Namespace (.github/workflows/ci.yml) and Depot (.depot/workflows/guard.yml) guard jobs.
set -euo pipefail

if git ls-files --error-unmatch AGENTS.md >/dev/null 2>&1; then
    echo "::error::AGENTS.md is tracked by git but must remain gitignored (it holds local credentials)." >&2
    exit 1
fi
echo "OK: AGENTS.md is not tracked."
