# Hysteria2 Gecko obfs — deferral note

Date: 2026-06-15
Status: **Deferred** (blocked on upstream availability)

## Goal
Add Hysteria2 "Gecko" QUIC obfuscation (from Hysteria app/v2.9.2) to this fork —
`obfs.type=gecko` with `min_packet_size` / `max_packet_size`.

## Why it is deferred
Gecko obfs does **not** exist in any stable sing-box / sing-quic release, nor in this
fork's pinned core. It currently lives only in pre-release code:

| Component | This fork's pin | Where Gecko actually is |
|---|---|---|
| sing-box | `starifly/sing-box` @ `4998428…` = **1.12.19-neko-1** (Salamander only) | upstream **`v1.14.0-alpha.31`** (`Hysteria2ObfsGecko`, `Hysteria2ObfsTypeGecko`) |
| sing-quic | `v0.5.2` (only `salamander.go`) | untagged commit **`9467ede27fb7`** (`hysteria2/gecko.go`), required by sing-box 1.14.0-alpha.31 (`sing-quic v0.6.2-0.20260525051024-9467ede27fb7`) |

Verified (2026-06-15):
- `option/hysteria2.go` on sing-box `main` has **no** gecko reference; only `v1.14.0-alpha.31` does.
- sing-quic tags up to `v0.6.1` / `main` contain only `salamander.go`; `gecko.go` appears
  only in commit `9467ede27fb7`.
- The runtime obfuscator lives in sing-quic, not sing-box — so this is a sing-quic feature,
  not just a sing-box option struct.

## Options considered
- **A. Pin to upstream sing-box 1.14.0-alpha.31** — rejected. Switches from
  `starifly/sing-box` to upstream, dropping all starifly protocol additions (Snell,
  Juicity, AnyTLS/AnyReality, ShadowsocksR, XHTTP, mux extensions) and shipping on an alpha.
- **B. Backport `gecko.go` into a forked sing-quic + bump sing-quic 0.5.2→0.6.x inside
  starifly/sing-box** — rejected for now. Large cross-repo Go effort (sing-quic 0.5→0.6
  changed QUIC internals), high regression risk to Hysteria2/TUIC, and adds a forked
  sing-quic to maintain.
- **C. Defer** — chosen. Wait until Gecko lands in a stable `starifly/sing-box` (or upstream
  sing-box stable that starifly rebases onto). Then it becomes a clean pin bump plus a small
  Android UI change.

## When Gecko becomes available (future work)
1. `core/singbox-gecko-base`: bump `COMMIT_SING_BOX` (and transitively sing-quic) in
   `buildScript/lib/core/get_source_env.sh` to a starifly commit that includes Gecko;
   rebuild `libcore.aar`; confirm a minimal `obfs.type=gecko` Hysteria2 outbound is accepted
   and existing Salamander/no-obfs profiles still work.
2. `feature/hy2-gecko-obfs`: Android side —
   - `HysteriaBean`: add an obfs-type field + `geckoMinPacketSize` / `geckoMaxPacketSize`
     (bump the kryo serialization version, currently 7, with deserialize handling).
   - `HysteriaFmt.kt`: stop hardcoding `type="salamander"` (line ~330); branch on obfs type;
     emit `min_packet_size`/`max_packet_size` for gecko.
   - `SingBoxOptions.java`: extend `Hysteria2Obfs` (currently `type`+`password`) to carry the
     gecko packet-size fields, matching the core's schema at that version.
   - URI parse/build: handle `obfs=gecko`, `obfs-min-packet-size`, `obfs-max-packet-size`.
   - UI (`hysteria_preferences.xml` + `HysteriaSettingsActivity.kt`): add an obfs-type
     selector; show min/max only for gecko.
   - Validation: password required for salamander/gecko; `1 <= min <= max <= 2048`;
     defaults 512 / 1200.
