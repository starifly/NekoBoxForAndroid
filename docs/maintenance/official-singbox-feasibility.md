# Feasibility: switching the core to official SagerNet/sing-box

Date: 2026-06-15
Status: **Investigated (read-only). Not recommended as a drop-in switch.**

## What was asked
Switch this fork's core from `starifly/sing-box` to the official
`SagerNet/sing-box`.

## The actual fork chain
```text
SagerNet/sing-box        (official, latest stable v1.13.13)
  └── MatsuriDayo/sing-box   (NekoBox lineage; heavy Android/gomobile + extra protocols)
        └── starifly/sing-box   (this fork's pinned core; ~v1.12.19-neko-1)
```
`starifly/sing-box`'s GitHub parent is `MatsuriDayo/sing-box`, **not** official directly.
So "switch to official" means leaving the entire Matsuri/NekoBox core lineage that this
app is built on — not a remote/URL change.

## Why it is not a config swap
`libcore/` (the gomobile bridge, 8 Go files) imports sing-box **internal** packages
directly, including several that **do not exist in official sing-box**:

| libcore import | In official v1.13.x? |
|---|---|
| `sing-box/nekoutils` | NO (Matsuri-only glue) |
| `sing-box/protocol/shadowsocksr` | NO (SSR not in official) |
| `sing-box/protocol/snell` | NO (Snell not in official) |
| `sing-box/protocol/anytls` | Present, but fork/official APIs differ |
| `experimental/libbox/platform` | Present, but the fork's platform API diverges |
| `adapter`, `adapter/{inbound,outbound,endpoint,service}`, `option`, `dns/...` | Present, but 1.12-fork vs 1.13-official APIs differ |

Official `protocol/` dir (v1.13.13, confirmed) has: anytls, block, cloudflare, direct,
dns, group, http, hysteria, hysteria2, mixed, naive, redirect, shadowsocks, shadowtls,
socks, ssh, tailscale, tor, trojan, tuic, tun, vless, vmess, wireguard.

**Missing vs this fork:** `shadowsocksr`, `snell`, `juicity` (juicity is wired via the
separate `dyhkwong/sing-juicity` dep), plus the `nekoutils` glue.

## App-side coupling (what would break)
- `app/.../SingBoxOptions.java` is a **4830-line** hand-maintained mirror of the fork's
  `option` structs. Official's option schema differs (1.13 vs 1.12 fork) and would need
  re-deriving.
- The app exposes fork-only protocols as first-class profile types:
  - `TYPE_SSR = 3` (ShadowsocksR) — 8 files
  - `TYPE_SNELL = 24` — 11 files
  - `TYPE_JUICITY = 23` — 9 files
  These would all break (no core support in official) and the profile types/DB enums,
  fmt parsers/exporters, and UI editors would need removal or re-sourcing.
- `box_include.go` registers the fork's protocol set; official's registration API differs.
- `BoxInstance.kt` + platform interface target the Matsuri `libbox/platform` API.

## Cost / risk
This is **re-platforming the app onto a different core**, not an upgrade:
1. Rewrite all 8 libcore Go bridge files against official 1.13.x adapter/option/platform APIs.
2. Re-derive the 4830-line `SingBoxOptions.java` schema.
3. Lose ShadowsocksR, Snell, Juicity (and re-source or drop them) — visible feature loss.
4. Re-implement/verify the external-plugin mapping (mieru/naive/hysteria/etc.).
5. Re-validate every remaining protocol end to end.
Estimated: weeks of work, high regression risk across most protocols. Result would be a
materially different app from the NekoBox/Matsuri lineage.

## Recommendation
**Do not switch wholesale.** The fork exists precisely because of the Matsuri/starifly
core additions; official sing-box can't host this app's feature set without major loss and
rework.

If the underlying motivation is **newer upstream sing-box features** (e.g. the Gecko obfs
that started this), the realistic paths are, in order of preference:
1. **Wait for `starifly`/Matsuri to rebase onto newer official sing-box** (they track
   upstream), then bump the pin — keeps all fork protocols.
2. **Cherry-pick/backport** a specific upstream feature into the starifly fork (a fork of
   the fork) when a single feature is needed.
3. Full re-platform to official only if the project intentionally abandons the
   Matsuri-lineage protocol set — a separate product decision, out of scope for a
   maintenance/feature pass.

## Verification performed (read-only)
- Enumerated libcore's sing-box imports and matched them against official v1.13.13's
  package layout.
- Confirmed SSR/Snell/Juicity/nekoutils absent from official.
- Measured app-side coupling (SingBoxOptions.java size, ProxyEntity types, file counts).
- No core or app code was changed.
