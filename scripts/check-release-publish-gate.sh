#!/usr/bin/env bash
set -euo pipefail

WORKFLOW=".github/workflows/release.yml"

has_safe_publish_input() {
    local file=$1
    local block line
    local has_type=false
    local has_default=false

    block=$(
        awk '
            /^  workflow_dispatch:[[:space:]]*$/ { in_dispatch = 1; next }
            in_dispatch && /^    inputs:[[:space:]]*$/ { in_inputs = 1; next }
            in_inputs && /^      publish:[[:space:]]*$/ { in_publish = 1; next }
            in_publish && $0 !~ /^       / && $0 !~ /^[[:space:]]*$/ { exit }
            in_publish { print }
        ' "$file"
    )

    while IFS= read -r line; do
        [[ "$line" =~ ^[[:space:]]+type:[[:space:]]*boolean[[:space:]]*$ ]] && has_type=true
        [[ "$line" =~ ^[[:space:]]+default:[[:space:]]*false[[:space:]]*$ ]] && has_default=true
    done <<< "$block"

    $has_type && $has_default
}

publish_job_block() {
    local file=$1
    awk '
        /^  publish:[[:space:]]*$/ { in_publish = 1; next }
        in_publish && $0 !~ /^    / && $0 !~ /^[[:space:]]*$/ { exit }
        in_publish { print }
    ' "$file"
}

has_positive_publish_condition() {
    local block=$1
    local line
    while IFS= read -r line; do
        if [[ "$line" =~ ^[[:space:]]+if:[[:space:]]*\$\{\{[[:space:]]*inputs\.publish[[:space:]]*==[[:space:]]*true[[:space:]]*\}\}[[:space:]]*$ ]]; then
            return 0
        fi
    done <<< "$block"
    return 1
}

has_publish_write_permission() {
    local block=$1
    local line
    local in_permissions=false
    while IFS= read -r line; do
        if [[ "$line" =~ ^[[:space:]]{4}permissions:[[:space:]]*$ ]]; then
            in_permissions=true
            continue
        fi
        if $in_permissions && [[ "$line" =~ ^[[:space:]]{4}[^[:space:]] ]]; then
            break
        fi
        if $in_permissions && [[ "$line" =~ ^[[:space:]]+contents:[[:space:]]*write[[:space:]]*$ ]]; then
            return 0
        fi
    done <<< "$block"
    return 1
}

has_workflow_read_permission() {
    local file=$1
    local line
    local in_permissions=false
    while IFS= read -r line; do
        if [[ "$line" =~ ^permissions:[[:space:]]*$ ]]; then
            in_permissions=true
            continue
        fi
        if $in_permissions && [[ "$line" =~ ^[^[:space:]] ]]; then
            break
        fi
        if $in_permissions && [[ "$line" =~ ^[[:space:]]+contents:[[:space:]]*read[[:space:]]*$ ]]; then
            return 0
        fi
    done < "$file"
    return 1
}

check_file() {
    local file=$1
    local failed=0
    local content
    local publish_block
    content=$(<"$file")
    publish_block=$(publish_job_block "$file")

    if ! has_safe_publish_input "$file"; then
        echo "Error: release publish input must be a boolean that defaults to false: $file" >&2
        failed=1
    fi

    if ! has_positive_publish_condition "$publish_block"; then
        echo "Error: publish job must use the positive inputs.publish == true condition: $file" >&2
        failed=1
    fi

    if [[ "$content" == *"github.event.inputs.publish != 'y'"* ]]; then
        echo "Error: legacy fail-open release publish condition is present: $file" >&2
        failed=1
    fi

    if ! has_workflow_read_permission "$file"; then
        echo "Error: workflow-level contents permission must remain read-only: $file" >&2
        failed=1
    fi

    if ! has_publish_write_permission "$publish_block"; then
        echo "Error: publish job must retain contents write permission: $file" >&2
        failed=1
    fi

    return "$failed"
}

self_test() {
    check_file "$WORKFLOW"

    local tmp_dir
    local unsafe_workflow
    local quoted_tmp_dir
    tmp_dir=$(mktemp -d)
    unsafe_workflow="$tmp_dir/release.yml"
    printf -v quoted_tmp_dir '%q' "$tmp_dir"
    trap "rm -rf -- $quoted_tmp_dir" EXIT

    cp "$WORKFLOW" "$unsafe_workflow"
    sed 's#if: \${{ inputs\.publish == true }}#if: github.event.inputs.publish != '"'"'y'"'"'#' \
        "$unsafe_workflow" > "$unsafe_workflow.tmp"
    mv "$unsafe_workflow.tmp" "$unsafe_workflow"

    if check_file "$unsafe_workflow"; then
        echo "Error: release publish guard accepted the prior unsafe condition" >&2
        return 1
    fi

    rm -rf -- "$tmp_dir"
    trap - EXIT
    echo "OK: release publish gate is explicit and rejects the prior unsafe condition."
}

case "${1:-}" in
    "")
        check_file "$WORKFLOW"
        echo "OK: release publish gate is explicit and safe by default."
        ;;
    --self-test)
        self_test
        ;;
    *)
        echo "Usage: $0 [--self-test]" >&2
        exit 2
        ;;
esac
