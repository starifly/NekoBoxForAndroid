#!/usr/bin/env bash
# Fail if the lint baseline GREW versus the merge base with main. app/lint-baseline.xml
# records pre-existing lint findings so the gate is green today; the policy (documented at
# the top of lint.xml) is that it may only SHRINK - new lint errors must be fixed, not
# baselined. This guard counts <issue elements in the baseline on the current tree and on
# the merge base and fails if the count increased. Shared by the Depot (guard.yml) and
# Namespace (ci.yml) guard jobs.
set -euo pipefail

BASELINE="app/lint-baseline.xml"

if [ ! -f "$BASELINE" ]; then
    echo "OK: no lint baseline present (nothing to guard)."
    exit 0
fi

count_issues() {
    # grep -c prints the count but exits 1 when there are no matches; under `set -e` that would
    # abort, so tolerate the no-match case and normalize to a single integer.
    grep -c "<issue" "$1" || true
}

current=$(count_issues "$BASELINE")

# Determine the comparison point: merge base with origin/main (fall back to main, then
# to HEAD~1). On the first commit or a shallow checkout this may be empty -> skip.
base_ref=""
for ref in origin/main main; do
    if git rev-parse --verify --quiet "$ref" >/dev/null; then
        base_ref="$ref"
        break
    fi
done

if [ -z "$base_ref" ]; then
    echo "OK: no main ref to compare against (count=$current)."
    exit 0
fi

merge_base=$(git merge-base HEAD "$base_ref" 2>/dev/null || true)
if [ -z "$merge_base" ]; then
    echo "OK: no merge base with $base_ref (count=$current)."
    exit 0
fi

if git cat-file -e "$merge_base:$BASELINE" 2>/dev/null; then
    baseline_old=$(git show "$merge_base:$BASELINE" | { grep -c "<issue" || true; })
else
    baseline_old=0
fi

echo "lint baseline: base=$baseline_old current=$current (ref=$base_ref)"

if [ "$current" -gt "$baseline_old" ]; then
    echo "::error::Lint baseline GREW ($baseline_old -> $current). New lint findings must be fixed, not baselined. See lint.xml baseline policy." >&2
    exit 1
fi

echo "OK: lint baseline did not grow."
