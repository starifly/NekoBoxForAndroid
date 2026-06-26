# Parser contract & characterization notes

This documents the subscription/config parsers, what is covered by characterization tests,
and the invariants that must not regress. Generated for Plan 007.

## YAML hardening invariants (MUST NOT regress)

`RawUpdater.parseRaw` parses untrusted Clash/Mihomo subscription YAML with SnakeYAML. The
following guards defend against CVE-2022-1471-class attacks and a malicious-subscription DoS,
and must be preserved by any future change:

- A `SafeConstructor` (no arbitrary type instantiation from YAML tags).
- `LoaderOptions.codePointLimit` (input size cap).
- `LoaderOptions.maxAliasesForCollections = 200` (billion-laughs / alias-bomb guard).

Plan 006 adds per-entry resilience (safe casts, skip-bad-node) on top of these; it must not
weaken any of the above.

## Tested formats (JVM unit tests)

| Format | Test | Notes |
|--------|------|-------|
| Shadowsocks `ss://` (plaintext userinfo only; base64url still unasserted) | `ShadowsocksFmtTest` | happy-path golden |
| Kryo bean round-trip / boundaries | `KryoConvertersTest` | format stability (Plan 008/021) |

## Constraint: code paths that reach `Logs.x(...)` are NOT JVM-unit-testable

`io.nekohasekai.sagernet.ktx.Logs` calls `libcore.Libcore.nekoLogPrintln(...)`, which is a
**native** method from `libcore.aar`. The native library is not loaded in a pure-JVM unit
test, so any parser code path that reaches a `Logs.x(...)` call throws `UnsatisfiedLinkError`
under `testOssDebugUnitTest`.

Practical consequence for characterization:
- `parseShadowsocks` happy path is Logs-free → JVM-testable.
- `parseV2Ray` (vmess/vless/trojan) logs in its `parseV2RayN`/Kitsunebi fallback `catch`
  blocks before reaching the std path, so it is **not** cleanly JVM-testable as-is.
- `parseRaw` reaches `app.getString(...)` (Android resources) and `Logs` on several paths.

To characterize the Logs-touching parsers, either:
1. run them as **instrumented** tests (the androidTest harness + emulator exists via the
   Plan 025 / Depot `android-instrumented` workflow, where `libcore` is loaded), or
2. extract the pure decode logic away from the `Logs`/`app` calls first (a TECHDEBT refactor,
   out of scope for the characterization pass).

This is recorded so later plans (009 dedup, 020 ini4j, 021 kryo, 022 hysteria) know where the
JVM safety net does and does not reach.

## Items flagged during characterization (looks-wrong, NOT fixed here)

- `HysteriaFmt.kt`: `// TODO parse HY2 JSON+YAML`; `parseHysteria1Json` hard-codes
  `protocolVersion = 1`. Tracked by Plan 022; characterize-don't-fix.
