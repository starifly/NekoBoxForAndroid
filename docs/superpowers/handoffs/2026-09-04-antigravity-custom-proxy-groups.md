# Antigravity Handoff: NekoBox Custom Proxy Groups

## Mission

Continue and finish the user-approved custom proxy-group feature in this exact worktree:

`C:\Users\renos\Documents\Proxy\NekoBoxForAndroid-router-groups`

Do not restart the implementation and do not replace it with fixed US/SG/JP groups. Review and finish the existing uncommitted code.

## User-approved behavior

- Users can create any number of custom proxy groups with arbitrary display names.
- Each group selects one or more existing subscription groups as sources.
- Include regex filters node names; empty include means all source nodes.
- Exclude regex runs after include and wins.
- The same node may belong to multiple custom groups.
- Modes in this phase are only `selector` and `url-test`.
- Route rules explicitly select a custom group. Never infer a group from a rule name, domain, or app.
- A stable internal `router.<uuid>` tag survives display-name changes and subscription refreshes.
- Missing, disabled, or empty referenced groups must produce a visible group-specific error. Never silently fall back.
- Failed/empty subscription refresh preserves the last valid members and records an error.
- Deleting a group is blocked while route rules reference it.
- Preserve all existing subscriptions, routes, App routing, AdBlock, `.invalid` load rules, DNS/TUN, profiles, and default behavior.
- Do not implement fallback, load balancing, nested groups, Clash YAML import, or predefined groups.

## Hard safety boundaries

- Never delete anything outside this project directory.
- Do not touch `C:\Users\renos\Documents\Proxy\A7.yaml`, `nekobox_isA8.json`, or similar isA8 files.
- Preserve all current working-tree changes. Do not reset, clean checkout, or discard files.
- Gradle `clean` inside this project is allowed and required for final ABI verification.
- Do not commit or push until verification is complete. Do not rewrite existing commits.
- Use minimal changes only; no unrelated cleanup or refactoring.

## Read first

1. `AGENTS.md` in the parent Proxy workspace and any repository-local instructions.
2. `docs/superpowers/specs/2026-09-04-custom-proxy-groups-design.md`
3. `docs/superpowers/plans/2026-09-04-custom-proxy-groups.md`
4. This handoff, then `git status --short` and the complete diff from `f8e6418`.

## Git state

- Branch: `router-groups`
- Existing commits:
  - `f8e6418 docs: define custom proxy groups design`
  - `bdc6392 docs: plan custom proxy groups implementation`
- The implementation is intentionally still uncommitted and includes earlier Antigravity work plus follow-up corrections. Preserve it.

## Toolchain

- Project-local Android SDK is configured by `local.properties`:
  `C:\Users\renos\Documents\Proxy\.router-groups-toolchain\android-sdk`
- Portable JDK 17:
  `C:\Users\renos\AppData\Local\CodexTools\jdk-17.0.20\jdk-17.0.20.1+1`
- Before Gradle commands in PowerShell:
  `$env:JAVA_HOME='C:\Users\renos\AppData\Local\CodexTools\jdk-17.0.20\jdk-17.0.20.1+1'`
- Do not delete or relocate either external toolchain directory.
- ADB path:
  `C:\Users\renos\Documents\Proxy\.router-groups-toolchain\android-sdk\platform-tools\adb.exe`

## Work already completed

- Independent include/exclude matching with cross-group overlap.
- Room v10 models for custom groups, materialized members, ordered subscription sources, stable selection, errors, and explicit `RuleEntity.routerGroupId`.
- No predefined groups or semantic route assignment.
- Repository validation, CRUD, preview, deletion guard, refresh reconciliation, stable node identity, and member-order preservation.
- ConfigBuilder emits selector/url-test outbounds and explicit route references; missing/disabled/empty references throw.
- Dedicated proxy-group list/editor and route-group picker.
- Selector hot switching through the existing native selector API.
- Backup version 3 stores groups, members, sources, and separate rule references (`routerRuleRefs`) while preserving the old RuleEntity parcel layout.
- Subscription failure records an error on affected groups.
- Old fixed inline group-card/manual-membership UI was removed.
- AAR ABI gate requires `libcore.HTTPClient`, rejects legacy `libcore.HttpClient`, checks `newHttpClient`, all four JNI ABIs, and compiled app caller bytecode.

## Checks already passed

- `:app:compileOssDebugKotlin`
- All `:app:testOssDebugUnitTest`
- `:app:compileOssDebugAndroidTestKotlin`
- `:app:verifyLibcore`
- `:app:verifyOssDebugLibcoreCallers`
- `git diff --check` (only Windows line-ending warnings)

The latest combined JVM/Android-test compilation completed successfully. There was no connected Android device. `lintOssDebug` was started but intentionally interrupted when the user requested this handoff; do not treat lint as passed or failed.

## Required remaining work

1. Perform a focused code review against the approved spec and current plan. Fix only confirmed defects.
2. Pay special attention to:
   - backup v3 import transaction and old-backup behavior;
   - explicit route target serialization and invalid references;
   - editor preview/change listeners and validation messages;
   - selector persistence of both `selectedProxyId` and `selectedNodeKey`;
   - refresh failure/empty-success preservation semantics;
   - no remaining exclusivity or fixed-name assumptions outside test fixture strings;
   - Room schema 10 matching current entities;
   - URL-test `url`, nanosecond `interval`, and `tolerance` fields supported by the pinned binding.
3. Rerun full JVM tests and Android-test compilation.
4. Run lint to completion and classify findings. Do not broadly clean pre-existing warnings.
5. Run a clean debug build so stale native callers cannot survive:
   `./gradlew.bat :app:clean :app:assembleOssDebug --console=plain --no-build-cache`
6. Inspect the produced APK:
   - all expected `libgojni.so` ABI entries exist;
   - no stale `Llibcore/HttpClient;` descriptor exists;
   - current `Llibcore/HTTPClient;` descriptor exists.
7. Record APK path, size, SHA-256, exact command results, schema version, ABI results, lint result, and unrun device tests in:
   `docs/superpowers/verification/2026-09-04-custom-proxy-groups.md`
8. Audit scope from `f8e6418`; verify no changes to protected config files and no unintended DNS/TUN/subscription/AdBlock/`.invalid` behavior.
9. If exactly one authorized Android device is connected, install the clean APK without clearing app data and run the device checklist below. Otherwise stop with the APK ready and clearly mark device acceptance unverified.
10. Only after all available checks pass, commit coherent implementation and verification changes. Do not push unless the user explicitly asks.

## Device acceptance checklist

1. Upgrade install without clearing data; confirm old database opens.
2. Refresh subscription 1 and subscription 2; confirm the prior `HttpClient/HTTPClient` crashes do not recur.
3. Create `US1` from both subscriptions with a US include regex.
4. Create a second group containing at least one of the same nodes; confirm overlap works.
5. In selector mode, switch one group and confirm the other group is unchanged.
6. In URL-test mode, confirm only that group's members are tested/selected.
7. Edit one route rule and explicitly select `US1`; start the service and verify the route resolves.
8. Refresh both subscriptions; confirm group definitions, route references, and valid members survive.
9. Confirm AdBlock, `.invalid` load rules, existing App routing, DNS/TUN, subscriptions, and ordinary profiles still function.
10. Capture exact logs for any failure; do not claim success from APK build alone.

## Current device state

The project SDK's `adb devices -l` returned no attached devices at handoff time. Do not invent device results.

## Completion report format

Lead with whether the feature is statically complete and whether real-device acceptance was actually performed. List:

- what changed;
- exact passing/failing commands;
- APK absolute path and SHA-256;
- remaining risks or unverified device steps;
- commit hashes created;
- confirmation that protected files and unrelated behavior were not changed.
