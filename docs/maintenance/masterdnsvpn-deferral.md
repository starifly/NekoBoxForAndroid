# MasterDnsVPN integration — deferral note and design

Date: 2026-06-15
Status: **Deferred** (blocked on upstream Android socket protection)

## Goal
Add `masterking32/MasterDnsVPN` (a DNS-tunneling VPN that carries TCP/UDP over DNS
queries) as a client profile in NekoBox, using the same bundled-binary sidecar pattern
that now powers Mieru.

## Why it is deferred
MasterDnsVPN has **no Android `VpnService.protect()` mechanism**. Its client
(`cmd/client`, Go 1.25, TOML config) sends its tunnel carrier as DNS queries to
configured resolver IPs (e.g. `8.8.8.8:53`). When NekoBox's `VpnService` is active and
captures all traffic into the TUN, those upstream DNS sockets would be captured by the VPN
and loop back into sing-box instead of egressing on the real underlying network — breaking
the tunnel.

Repo inspection (2026-06-15) found no protect-path env, no `SO_MARK`/fwmark/`Dialer.Control`
socket-control hook, and no Android-specific code. By contrast, Mieru integrates cleanly
because upstream Mieru honors `MIERU_PROTECT_PATH` and lets NekoBox protect its upstream
sockets.

## Architecture analysis (confirmed with an external model review)
- **sing-box `direct`/bypass routing rule for the resolver IPs — does NOT work.** A sing-box
  route rule only affects sing-box's own outbound sockets. The MasterDnsVPN sidecar is a
  separate process; its sockets are not sing-box's, so a `direct` rule cannot keep them off
  the VPN. At best it would hairpin captured sidecar packets back through sing-box, which is
  fragile and semantically wrong for a DNS tunnel (sing-box may treat the carrier DNS as
  ordinary DNS and rewrite/route it).
- **Android `VpnService.Builder.excludeRoute()` (API 33+) — works by destination but is the
  wrong tool.** It would exclude *all* traffic to the resolver IPs for the whole covered UID
  (not just MasterDnsVPN's DNS carrier), is IP-prefix based (not per-socket/session), can't
  follow dynamic resolver changes without rebuilding the VPN, is awkward pre-API 33, and
  interacts badly with per-app/lockdown VPN expectations. Leak-prone; not production-grade.
- **Per-socket `VpnService.protect(fd)` is the only reliable way** to keep a separate
  sidecar's upstream sockets off the VPN. Raw `SO_MARK` from app code is gated by
  Android/netd permission checks and is not a substitute.

## Recommended sequence (when work resumes)

### Step 1 — Ship Proxy-mode-only sidecar (interim, usable)
Full feature scaffolding, gated to NekoBox proxy mode (no TUN, so no VPN loop):
- `MasterDnsVpnBean` (new `ProxyEntity.TYPE_*`, `TypeMap`, `needExternal()=true`).
- Fields: `domains`, `dataEncryptionMethod`, `encryptionKey`, `resolvers`,
  `resolverBalancingStrategy`, `localSocksPort`, `socks5Auth`/`user`/`pass`,
  `protocolType` (SOCKS5), plus an `advancedToml` override for the long tail of knobs.
- TOML generator writing `client_config.toml` (mirror `client_config.toml.simple`) +
  a resolvers file.
- Process runner via the existing `GuardedProcessPool` (like Mieru), local SOCKS5 on
  `127.0.0.1:<allocated port>`; sing-box `socks` outbound -> that port.
- Bundled binaries: cross-compile `cmd/client` for all 4 ABIs into
  `app/executableSo/<abi>/libmasterdnsvpn.so`, built in CI (mirror `buildScript/lib/mieru.sh`
  and the per-workflow Mieru job).
  - **Go toolchain decision:** MasterDnsVPN requires `go 1.25.0`, while libcore's CI is
    pinned to `1.24.9` (matching `libcore/go.mod`'s `toolchain` directive). Use a **separate
    Go setup step scoped to the MasterDnsVPN build job** (its own `actions/setup-go` with
    `go-version: '1.25.x'`), leaving the libcore toolchain pin untouched. Do **not** raise the
    whole-repo Go pin just for this binary — libcore is reproducibility-sensitive and was
    aligned to 1.24.9 deliberately. The MasterDnsVPN build is an independent native binary, so
    an isolated newer Go for that one job is the lower-risk choice.
- In VPN mode, disable the profile with a clear message:
  "MasterDnsVPN requires Android socket protection for VPN mode. Proxy mode is supported;
  VPN mode will be enabled after upstream resolver sockets can be protected."
- Security defaults: `LISTEN_IP=127.0.0.1`, SOCKS5 auth on with random user/pass, never log
  `ENCRYPTION_KEY` or SOCKS creds, bind loopback only.

### Step 2 — Upstream a socket-protect hook to MasterDnsVPN (fork/PR)
- Add a `MASTERDNSVPN_PROTECT_PATH` env (unset = normal desktop behavior).
- Wire every upstream resolver socket through `net.Dialer.ControlContext` (connected
  TCP/UDP) and `net.ListenConfig.Control` (unconnected UDP `PacketConn`), protecting the fd
  *after creation, before connect/bind*.
- Audit ALL upstream socket creation sites: UDP query, TCP fallback, IPv4+IPv6, resolver
  health checks, MTU/probe, validation, any future DoH/DoT. Do NOT protect the local SOCKS
  listener.
- Fail fast in VPN mode if protect fails.

### Step 3 — NekoBox protect bridge (reuse Mieru's)
- NekoBox creates a private unix domain socket under app-private storage, starts the protect
  server before the sidecar, launches the client with `MASTERDNSVPN_PROTECT_PATH`.
- The client passes the real fd via `SCM_RIGHTS`; NekoBox calls `VpnService.protect(fd)` and
  returns a status byte. (Pass the actual fd, not a numeric string.)

### Step 4 — Enable VPN mode; remove any interim workaround
- App -> TUN -> sing-box -> SOCKS outbound -> 127.0.0.1:client -> protected resolver sockets.
- No sing-box "direct resolver IP" rule needed — the carrier DNS never enters sing-box.

### Step 5 — Verification that matters
- Full-route VPN (`0.0.0.0/0` + `::/0`), no excludeRoute, no sing-box direct rule:
  MasterDnsVPN works AND the TUN shows no `sidecar -> resolver:53` packets; protect-server
  logs show every resolver socket protected.
- Matrix: UDP + TCP fallback, multiple resolvers, failover/health checks, IPv6 resolvers,
  Wi-Fi<->cellular switch, VPN restart, always-on/lockdown, target Android versions.

## Why not do it all now
- VPN-mode support (the headline use case) depends on an upstream code change that doesn't
  exist yet; shipping a sing-box-routing workaround would be leak-prone and architecturally
  wrong.
- The proxy-mode-only interim is a real option (Step 1) but was deferred together with the
  rest pending a decision to invest in the upstream protect hook. This note captures the full
  design so the work can start cleanly later.
