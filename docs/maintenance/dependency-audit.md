# Dependency Audit

Branch: `maintenance/dependency-audit`
Date: 2026-06-15
Base: `hawkff/NekoBoxForAndroid` (fork of `starifly/NekoBoxForAndroid`, itself a fork of `MatsuriDayo/NekoBoxForAndroid`)
Scope: **documentation only — no functional changes.** This file identifies update targets and risky areas so that follow-up branches can be small and reversible.

> "Latest safe target" cells marked `VERIFY` could not be confirmed from inside this environment (no package-index access at audit time). Confirm the newest stable release before bumping, then record the exact version in the update branch PR.

---

## Current app identity

Source: `nb4a.properties`, `buildSrc/src/main/kotlin/Helpers.kt`

| Field | Value | Source |
|---|---|---|
| Package name | `com.nb4a` | `nb4a.properties:1` |
| Version name | `1.4.2-mod-12` | `nb4a.properties:2` |
| Pre-release version name | `pre-1.4.2-20260214-1` | `nb4a.properties:3` |
| Version code (raw) | `46` | `nb4a.properties:4` |
| Version code (effective) | `230` (raw × 5) | `Helpers.kt:146` |
| Android namespace | `io.nekohasekai.sagernet` | `app/build.gradle.kts:30` |
| Debug app id suffix | `.debug` | `Helpers.kt:97` |
| ABI splits | armeabi-v7a, arm64-v8a, x86, x86_64 (no universal APK) | `Helpers.kt:169-177` |
| Product flavors | oss, fdroid, play, preview | `Helpers.kt:180-191` |

### Signing behavior
- Release keystore is `release.keystore` at repo root, configured only when `KEYSTORE_PASS` is present (`Helpers.kt:118-139`).
- Credentials come from `local.properties` or env (`KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`).
- **Both** release and debug build types are signed with the release key when it exists (`Helpers.kt:133-138`).
- CI injects `local.properties` via base64 `LOCAL_PROPERTIES` secret and passes keystore secrets per build (`.github/workflows/release.yml:64-68`).
- **Caveat (inherited from starifly):** package name `com.nb4a` (upstream is `moe.nb4a`) and a different signing key mean APKs from this fork will not update over upstream/other-fork installs.

---

## Android build stack

Sources: `gradle/wrapper/gradle-wrapper.properties`, `buildSrc/build.gradle.kts`, `build.gradle.kts`, `Helpers.kt`, `.github/workflows/*`, `buildScript/init/*`

| Component | Current | Latest safe target | Risk | Branch |
|---|---|---|---|---|
| Gradle wrapper | 8.10.2 | VERIFY (8.10.x/8.11.x) | Low | update-build-toolchain |
| Android Gradle Plugin | 8.8.1 | VERIFY | Medium (AGP majors change DSL) | update-build-toolchain |
| Kotlin | 2.0.21 | VERIFY | Medium (KSP must match) | update-build-toolchain |
| KSP | 2.0.21-1.0.27 | VERIFY (must pair with Kotlin) | Medium | update-build-toolchain |
| compileSdk / targetSdk | 35 / 35 | 35 (or VERIFY 36) | Medium (targetSdk bump = behavior changes) | update-build-toolchain |
| minSdk | 21 | keep 21 | n/a | — |
| buildToolsVersion | 35.0.1 | VERIFY | Low | update-build-toolchain |
| NDK | 25.0.8775105 | VERIFY (pinned in CI + env) | Medium (core build) | update-build-toolchain / core branch |
| Java (source/target) | 1.8 | keep 1.8 (desugar enabled) | Low | — |
| Java (CI runner / Go build) | 17 implied via runners | VERIFY | Low | update-build-toolchain |
| core library desugaring | desugar_jdk_libs 2.0.3 | VERIFY | Low | update-build-toolchain |

NDK is pinned in three places that must stay in sync: `.github/workflows/build.yml:63`, `.github/workflows/release.yml:65` (`ndk/25.0.8775105`) and `buildScript/init/env_ndk.sh:13`.

---

## App dependencies

Source: `app/build.gradle.kts:41-87`

| Dependency | Current | Target | Reason | Risk |
|---|---|---|---|---|
| kotlinx-coroutines-android | 1.6.4 | VERIFY (1.8.x+) | Old; aligns with Kotlin 2.0 | Medium |
| androidx.core:core-ktx | 1.9.0 | VERIFY | Routine | Low |
| androidx.recyclerview | 1.3.0 | VERIFY | Routine | Low |
| androidx.activity:activity-ktx | 1.10.1 | VERIFY | Already fairly new | Low |
| androidx.fragment:fragment-ktx | 1.5.6 | VERIFY | Lags activity | Low |
| androidx.browser | 1.5.0 | VERIFY | Routine | Low |
| androidx.swiperefreshlayout | 1.1.0 | (latest stable) | Routine | Low |
| androidx.constraintlayout | 2.1.4 | VERIFY | Routine | Low |
| androidx.navigation:*-ktx | 2.5.3 | VERIFY | Routine | Low |
| androidx.preference:preference-ktx | 1.2.0 | VERIFY | Routine | Low |
| androidx.appcompat | 1.6.1 | VERIFY | Routine | Low |
| androidx.work:work-* | 2.8.1 | VERIFY | Routine | Low |
| com.google.android.material | 1.8.0 | VERIFY | Routine | Low |
| com.google.code.gson | 2.9.0 | 2.11.0 (VERIFY) | Maintenance/security | Low |
| zxing-lite | 2.1.1 | VERIFY | QR scanning | Low |
| blacksquircle editorkit/language-* | 2.6.0 | VERIFY | Config editor | Low |
| **com.squareup.okhttp3:okhttp** | **5.0.0-alpha.3** | **5.0.0 stable (VERIFY)** | **On an alpha; move to stable line** | **Medium** |
| **org.yaml:snakeyaml** | **1.30** | **2.x (VERIFY)** | **Security (see below)** | **Medium** |
| material-about-library | 3.2.0-rc01 | VERIFY | About screen | Low |
| process-phoenix | 2.1.2 | VERIFY | Process restart | Low |
| kryo | 5.2.1 | VERIFY (hold — profile serialization) | **Do NOT bump casually**; kryo wire format backs profile blobs | Medium |
| guava | 31.0.1-android | VERIFY | Routine | Low |
| ini4j | 0.5.4 | VERIFY | Config parse | Low |
| recyclerview-fastscroll | 2.0.1 | VERIFY | UI | Low |
| androidx.room:room-* | 2.6.1 | VERIFY (2.6.x) | DB; bump cautiously | Medium |
| Roomigrant (MatrixDev) | 0.3.4 | hold | Schema migration generator | Medium |
| desugar_jdk_libs | 2.0.3 | VERIFY | Desugaring | Low |

Notes:
- `kryo` and `Room` touch persisted user data. Treat both as **migration-sensitive**, not routine bumps.
- okhttp is on an **alpha** (`5.0.0-alpha.3`); prefer moving to a stable 5.0.0 once verified, in its own commit, because the API surface changed across the 5.0 alphas.

---

## Core dependencies

Sources: `libcore/go.mod`, `buildScript/lib/core/get_source.sh`, `buildScript/lib/core/get_source_env.sh`, `.github/workflows/*`

The core is **not a normal Go-module update**. `libcore/go.mod` replaces sing-box and libneko with local checkouts, and `get_source.sh` clones pinned commits of `starifly/sing-box` and `starifly/libneko`.

```text
go.mod replaces (libcore/go.mod:93-97):
  github.com/matsuridayo/libneko   => ../../libneko
  github.com/sagernet/sing-box     => ../../sing-box
  github.com/sagernet/sing-vmess   => github.com/starifly/sing-vmess v0.2.8-mod.1
```

| Component | Current | Target | Reason | Risk |
|---|---|---|---|---|
| starifly/sing-box commit | `4998428a136368500428a6a35cdd466a7042c75b` | VERIFY (commit with HY2 Gecko) | **Required for Gecko obfs** | High |
| starifly/libneko commit | `1c47a3af71990a7b2192e03292b4d246c308ef0b` | hold unless needed | Keep separate from sing-box bump | High |
| starifly/sing-vmess | `v0.2.8-mod.1` | hold | Fork pin | Medium |
| Go toolchain (go.mod) | go 1.24.7 / toolchain 1.24.9 | VERIFY | **Mismatch: go.mod declares 1.24.x, CI uses ^1.25 (see next row)** | Medium |
| Go toolchain (CI) | `^1.25` (`build.yml:31`, `release.yml:33`) | align with go.mod | **Mismatch: CI uses ^1.25, go.mod declares 1.24.x** | Medium |
| sagernet/sing | v0.7.18 | follow sing-box | Transitive via fork | Medium |
| sagernet/quic-go | v0.52.0-sing-box-mod.3 | follow sing-box | QUIC/Hysteria path | High |
| sagernet/sing-quic | v0.5.2 | follow sing-box | **Hysteria2 lives here** | High |
| sagernet/sing-tun | v0.7.10 | follow sing-box | TUN | Medium |
| dyhkwong/sing-juicity | v0.0.3 | hold | Juicity protocol | Medium |
| reF1nd/sing-snell | v0.0.6 | hold | Snell protocol | Medium |
| anytls/sing-anytls | v0.0.11 | hold | AnyTLS | Medium |
| metacubex/mihomo | v1.19.13 | hold | Clash compat | Medium |

> **Gecko finding (confirmed):** at the pinned commit `4998428…`, `option/hysteria2.go` defines `Hysteria2Obfs` with only `Type` and `Password` fields — **no `min_packet_size` / `max_packet_size`.** Gecko obfs is therefore **not supported by the current core**. This validates the plan: `core/singbox-gecko-base` must land (pin bump or backport) before any Android Gecko UI work.

---

## Security-sensitive findings

### 1. Unsafe YAML deserialization (Medium/High)
- `app/build.gradle.kts:69` — `org.yaml:snakeyaml:1.30`.
- `app/src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt:255-259` — uses default `Yaml().loadAs(text, Map::class.java)` on **untrusted remote subscription content** (fetched at `RawUpdater.kt:80`).
- The default constructor allows arbitrary Java type instantiation (CVE-2022-1471 class of gadget). The `Map` cast happens *after* dangerous construction, so it does not mitigate.
- **Fix direction (separate security branch):** use `Yaml(SafeConstructor(LoaderOptions()))` and/or upgrade to SnakeYAML 2.x with a safe loader. Not part of the toolchain branch.

### 2. WebDAV credentials in plaintext + http:// allowed (Medium)
- Keys: `Constants.kt:184-187`. Storage: `DataStore.kt:302-316` — `webdavPassword` stored as plain string in `configurationStore` (no EncryptedSharedPreferences/Keystore).
- Basic auth sent at `BackupFragment.kt:237-239,267-269,287-289,361-363`; `WebDAVSettingsActivity.kt:142-144,180-182`.
- `http://` accepted: `BackupFragment.kt:212-213,342-343` (scheme check accepts both); `WebDAVSettingsActivity.kt:130` uses `URL(server)` with no scheme restriction.
- **Risk:** an `http://` endpoint transmits Basic-auth creds and the full secrets-bearing backup in cleartext, with no warning.
- **Fix direction:** warn/deny on non-TLS; consider encrypting stored creds.

### 3. LAN-exposed inbound can be unauthenticated (Medium)
- `ConfigBuilder.kt:143` — `bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else 127.0.0.1`.
- Inbound auth gate `DataStore.kt:155-160` — `mixedInboundNeedsAuth` is false when `appendHttpProxy` is active on Android Q+.
- **Risk:** `allowAccess=true` (bind `0.0.0.0`) **and** `appendHttpProxy` on Q+ ⇒ proxy exposed to LAN with no auth. The bind decision and auth decision are independent/uncoupled.
- **Fix direction:** force auth whenever bind is `0.0.0.0`, regardless of `appendHttpProxy`. Note: starifly already added an `appendHttpProxy` confirmation prompt; this is a separate, deeper coupling fix.

### 4. Swapped geosite/geoip fallback URLs (Bug, Low-Medium)
- `DataStore.kt:135-136`:
  - `rulesGeositeUrl` default → `…/sing-geoip/releases/latest/download/geoip.db` (wrong; points at geoip)
  - `rulesGeoipUrl` default → `…/sing-geosite/releases/latest/download/geosite.db` (wrong; points at geosite)
- The two defaults are swapped; resetting to custom defaults would populate geosite with geoip data and vice-versa, breaking routing.
- **Fix direction:** swap the two default URLs back. Small, isolated fix — good candidate for its own tiny PR.

### 5. DNS/routing defaults (informational)
- `DataStore.kt`: `remoteDns` `https://dns.google/dns-query` (`:124`), `directDns` `https://223.5.5.5/dns-query` (`:125`), `enableDnsRouting=true` (`:126`), `enableFakeDns=true` (`:127`), `ipv6Mode=DISABLE` (`:177`), `bypass=true` (`:181`), `strictRoute=true` (`:188`). Consumed in `ConfigBuilder.kt:144-153`.

---

## Feature-readiness notes (for later branches)

### Hysteria2 Gecko obfs
- Data model: single `HysteriaBean.obfuscation` String (`HysteriaBean.java:24`); **no obfs-type field**. kryo serialization version is `7` (`HysteriaBean.java:85`) → bumping requires deserialize handling.
- Emission point: `HysteriaFmt.kt:328-333` hardcodes `type = "salamander"`.
- URI parse/build: `HysteriaFmt.kt:95-97` (parse `obfs-password`), `:159-162` (emit `obfs=salamander`).
- UI: `hysteria_preferences.xml:30-35` + `HysteriaSettingsActivity.kt` — single password field, **no type selector**.
- SingBoxOptions Java mirror: `SingBoxOptions.java:588-594` `Hysteria2Obfs{ type, password }` — would need `min_packet_size`/`max_packet_size` added once core supports them.
- **Blocker:** core pin lacks Gecko (see Core section). Do `core/singbox-gecko-base` first.

### Mieru (already plugin-based)
- Existing: `MieruBean.java`, `MieruFmt.kt` (`buildMieruConfig` emits standalone mieru JSON), `MieruSettingsActivity.kt`, `mieru_preferences.xml`.
- Launch path: external plugin `mieru-plugin` / package `moe.matsuri.exe.mieru` (`PluginEntry.kt:17-26`).
- **Native goal = replace the external plugin** with a bundled binary/sidecar reusing the existing `buildMieruConfig` JSON. Bean fields today: `protocol` (TCP/UDP), `username`, `password`, `mtu` — fewer than the mbox outbound schema; extend as needed.

### MasterDnsVPN
- Not present in the codebase; greenfield. Plan as a SOCKS5 sidecar (new `MasterDnsVpnBean` + config generator + process runner + packaged binaries).

---

## Recommended update branches (in order)

1. `maintenance/update-build-toolchain` — Gradle/AGP/Kotlin/KSP/AndroidX/CI only. No core, no protocol, no DB/serialization. Also fix the CI/go.mod Go-version mismatch here (or in the core branch).
2. `fix/geo-fallback-urls` — swap the two default URLs in `DataStore.kt:135-136`. Tiny, isolated.
3. `security/yaml-safe-load` — SnakeYAML safe loader + version bump.
4. `security/webdav-tls-and-secrets` — warn/deny http://, consider credential encryption.
5. `security/inbound-auth-coupling` — require auth when binding 0.0.0.0.
6. `core/singbox-gecko-base` — bump or backport sing-box for HY2 Gecko (blocks Gecko UI).
7. `feature/hy2-gecko-obfs` — model/parse/export/UI/config-builder (depends on #6).
8. `feature/mieru-native-sidecar` — bundle mieru binary, drop external-plugin requirement.
9. `feature/masterdnsvpn-client-sidecar` — new SOCKS5 sidecar client.

---

## Acceptance criteria for this audit branch
- [x] No functional app changes (docs-only).
- [x] Update targets identified; risky areas flagged.
- [x] Core-source pins documented separately from Gradle/Kotlin/app deps.
- [x] Gecko core gap confirmed against the actual pinned commit.
- [ ] CI still builds (run after pushing; this branch only adds a markdown file).

## Open items to verify before bumping
- Exact latest-stable versions for every `VERIFY` cell (no index access at audit time).
- Whether targetSdk 36 is required by any store deadline.
- The specific starifly/sing-box commit (or upstream sing-box ≥ 1.14.0) that introduces HY2 Gecko `min_packet_size`/`max_packet_size`.
- Resolve CI Go `^1.25` vs `go.mod` `go 1.24.7` mismatch.
