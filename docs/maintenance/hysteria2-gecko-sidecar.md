# Hysteria2 Gecko obfs via bundled official-binary sidecar

Date: 2026-06-15
Status: Implementing. Supersedes `hysteria2-gecko-deferral.md` (Gecko is no longer blocked
on a sing-box re-platform).

## Decision
Ship Hysteria2 "Gecko" obfuscation by bundling the **official `apernet/hysteria`** Android
client binary as a sidecar (the same pattern as Mieru), instead of re-platforming the
sing-box core. This keeps the starifly core (and SSR/Snell/Juicity) intact and is a
days-scale change rather than a weeks-scale core migration. (Endorsed by external review.)

## Why this works cleanly
- Official hysteria ships Android binaries for all 4 ABIs (`hysteria-android-{arm64,armv7,amd64,386}`)
  in release `app/v2.9.2` — no cross-compile needed.
- Official hysteria client config has `obfs.type: gecko` with `obfs.gecko.{password,minPacketSize,maxPacketSize}`.
- Official hysteria has a built-in Android FD-protect hook:
  `quic.sockopts.fdControlUnixSocket` — it connects to a unix socket and sends each QUIC
  socket fd for protection. NekoBox **already** runs exactly such a server: libcore's
  `protect_server.ServeProtect("protect_path", ...)` (box.go:245) listens on the relative
  socket `protect_path` in the process working dir (`noBackupFilesDir`) and calls
  `VpnService.protect(fd)`. Sidecars run with that cwd. So we point hysteria's
  `fdControlUnixSocket` at that socket — no upstream patch, no new bridge.

## Routing model (which HY2 profiles use the sidecar)
- Non-obfs and **Salamander** Hysteria2 keep using the native starifly sing-box `hysteria2`
  outbound (unchanged — `canUseSingBox()` stays true).
- **Gecko** Hysteria2 routes to the bundled official-binary sidecar: `canUseSingBox()`
  returns false for Gecko, so the existing `needExternal()` machinery builds a sing-box
  `socks` outbound + dokodemo mapping pointing at the sidecar's local SOCKS5, exactly like
  Mieru/Naive/HY1.

## Data model (HysteriaBean)
Keep `obfuscation` (the obfs password — backward compatible; existing Salamander profiles
keep working). Add:
- `hysteria2ObfsType: Int` — 0=NONE, 1=SALAMANDER, 2=GECKO (default derived: if
  `obfuscation` non-blank and type unset on old data => SALAMANDER, else NONE).
- `geckoMinPacketSize: Int` (default 512), `geckoMaxPacketSize: Int` (default 1200).
- Bump kryo serialize version 7 -> 8; deserialize reads the new fields only when
  `version >= 8`, defaulting otherwise (old profiles => SALAMANDER if they had obfuscation).
Validation: gecko requires password; `1 <= min <= max <= 2048`.

## Config generation (new `buildHysteria2SidecarConfig`)
Emit hysteria client YAML (the official schema):
```yaml
server: "<serverAddress>:<serverPorts>"
auth: "<authPayload>"
tls: { sni: "<sni>", insecure: <allowInsecure|global> }   # ca via caText if set
obfs:
  type: gecko
  gecko: { password: "<obfuscation>", minPacketSize: <min>, maxPacketSize: <max> }
quic:
  sockopts:
    fdControlUnixSocket: "<noBackupFilesDir>/protect_path"
socks5:
  listen: "127.0.0.1:<port>"
  username: "<random>"
  password: "<random>"
  disableUDP: false
lazy: true
```
- Pre-resolve the server domain to an IP in NekoBox (set `server` to IP, `tls.sni` to the
  original host) so the sidecar's own DNS doesn't loop into the VPN — the one extra
  correctness item beyond fd-protect. (Hysteria mapping already routes the server via the
  dokodemo `direct` inbound through the core, which handles this; verify.)
- The sing-box `socks` outbound must carry the same random SOCKS user/pass.

## Binary packaging
- `buildScript/lib/hysteria2.sh` (`./run lib hysteria2`): download official
  `hysteria-android-{arm64,armv7,amd64,386}` from `app/v2.9.2`, install as
  `app/executableSo/<abi>/libhysteria2.so`. (Mirror `buildScript/lib/mieru.sh`; here we can
  download prebuilt instead of cross-compiling.)
- `PluginManager.initNativeInternal` already maps `hysteria2-plugin -> libhysteria2.so`.
- CI: the existing Mieru job was generalized into a combined `Native Build (Sidecars)` job
  that builds/caches both `libmieru.so` and `libhysteria2.so` (one `app/executableSo` cache),
  with a verify step covering all 4 ABIs and both binaries, across all 4 workflows.
- `Executable.EXECUTABLES` already lists `libhysteria.so`; add `libhysteria2.so`.

## BoxInstance wiring
- `init()`: for HysteriaBean with protocolVersion==2 && Gecko, `initPlugin("hysteria2-plugin")`
  and store `buildHysteria2SidecarConfig(port, protectPath)` (JSON config; viper detects the
  format from the `.json` extension).
- `launch()`: new branch — write the config to cache and invoke the official binary as
  `libhysteria2.so --disable-update-check --config <file> --log-level <level> client`.

## UI (hysteria_preferences.xml + HysteriaSettingsActivity)
- Replace the single obfs password field with an obfs **type selector** (None/Salamander/Gecko)
  + password + gecko min/max packet-size (shown only for Gecko, HY2 only).
- DataStore wiring for the new fields.

## Tests / verification
- Build core + APK; confirm `libhysteria2.so` packaged for all 4 ABIs.
- Existing Salamander HY2 profile still uses native outbound, round-trips unchanged.
- Gecko profile: import/export URI (`obfs=gecko&obfs-password=&obfs-min/max-packet-size`),
  generated YAML correctness, sidecar launches, sing-box socks outbound points at it.
- Runtime connect test deferred to the emulator QA pass (x86 metal, quota now approved).

## Scope guard
Touches: HysteriaBean (+serialization), HysteriaFmt (canUseSingBox/parse/export/new YAML
builder), ConfigBuilder (Gecko obfs already handled by needExternal path — verify pluginId),
BoxInstance (init+launch branches), UI, build script + CI, Executable. No core/Go change.
