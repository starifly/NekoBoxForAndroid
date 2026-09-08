# sing-box 1.15 / Go TUN stack 升级说明

## 目标

将 NekoBox for Android 的 sing-box 核心升级到包含新 **Go TUN stack** 的版本，
以改善 Android VPN 的 TUN 性能、CPU/内存效率和长期运行功耗。

本次升级**不删除**原有 gVisor / System / Mixed 回退能力；Go stack 仅作为新安装默认项。

## 固定依赖

| 组件 | 版本 / Commit |
|---|---|
| sing-box | `cf69a007cd45311fbd5d7bafe39e5eda3267089f` (`Add go TUN stack`) |
| sing | `v0.9.4-0.20260908053243-3e69d072c058` |
| sing-tun | `v0.9.1-0.20260908051822-65ba32b917d5` |
| gvisor | `v0.0.0-20260727.0-sing-box-mod.1`（显式固定，见下方说明） |
| Go | `1.25.5` |
| Android NDK | `25.0.8775105` |

> 正式 `1.15.0-alpha.3` / beta / stable 发布后，再单独评估是否从 commit pin 切换到 release tag。
> 当前不跟踪浮动 `testing` HEAD。

## 核心变更

1. **TUN stack 默认 `go`**
   - `Constants.kt` 增加 `TunImplementation.GO = 3`。
   - `DataStore.kt` 默认 `tunImplementation → GO`。
   - `ConfigBuilder` 将 `GO` 映射为 `stack = "go"`。
   - `arrays.xml` 增加 `tun_implementation_go` 文案与 `int_array_4` 枚举。

2. **TUN schema 迁移到 sing-box 1.15**
   - `Inbound_TunOptions` 使用新的 `address` 字段替代已移除的 `inet4_address` / `inet6_address`。
   - 移除 `endpoint_independent_nat` 等已废弃字段；保留等价的 `udp_mapping` / `udp_filtering` 默认行为。
   - 镜像 sing-box 1.15 新增字段：`interface_name`、`dns_mode`、`dns_address`、`udp_mapping` 等。

3. **DNS schema 迁移到 sing-box 1.15**
   - sing-box 1.14 起移除 DNS 服务器旧 `address` 字段。
   - `ConfigBuilder` 新增 `buildDnsServerOptions()`，将用户设置中的地址字符串转换为新的 typed DNS server 格式：
     - `local` → `{"type":"local"}`
     - `fakeip` → `{"type":"fakeip", "inet4_range":"...", "inet6_range":"..."}`
     - `rcode://success` → `{"type":"rcode", "rcode":"success"}`
     - `https://dns.google/dns-query` → `{"type":"https", "server":"dns.google", "path":"/dns-query", "tls":{...}}`
     - `tls://...`、`tcp://...`、`udp://...` 等分别映射到对应类型。
   - 移除已废弃的 `dns.fakeip` 配置节，FakeIP 范围现在直接写在 fakeip DNS server 上。

4. **WireGuard 迁移到 endpoint**
   - sing-box 1.15 不再将 WireGuard 注册为 outbound，仅作为 endpoint。
   - `ConfigBuilder` 现在把 WireGuard 节点写入 `endpoints` 而非 `outbounds`。
   - 弃用旧字段 `server` / `server_port` / `local_address` / `peer_public_key`，改用新的 `peers[]` 结构。
   - `urlTestDetour()` 增加 endpoint 回退，确保 URL Test 仍然经过 WireGuard 节点。

5. **恢复被升级破坏的平台功能**
   - **URL Test**：改用上游 `common/urltest.URLTest`，请求通过目标 outbound 拨号，不再使用 `http.DefaultClient` 直连。
   - **流量统计**：使用 `experimental/v2rayapi.StatsService` 替代已移除的 `boxapi`。
   - **ResetAllConnections**：调用 `ConnectionManager.CloseAll()` 真正关闭连接，而非仅打印日志。

6. **协议兼容性处理**
   - **SSR**：sing-box 1.15 上游已彻底移除 `protocol/shadowsocksr`。当前分支保留 UI/数据库代码，
     但使用 SSR 的节点在启动时会得到明确的 "unknown outbound type: shadowsocksr" 错误。
     后续恢复方案：像 `protocol/juicity` 一样在 `libcore/` 内引入一个兼容 sing-box 1.15 的 SSR 实现。
   - **Snell**：sing-box 1.15 出站仅支持版本 `4` / `6`；`quic_proxy_mode` 是 reF1nd fork 扩展，上游已不支持。
     `SnellBuildConfig` 不再发出 `quic_proxy_mode`，避免 strict parsing 报错。
   - **VMess / Juicity**：使用上游 `sing-vmess` v0.2.8 与项目内置 juicity 实现；编译与 schema 校验通过。

7. **清理仓库污染**
   - 删除并忽略 `scratch/`，避免开发 checkpoint / 真实配置 dump 进入仓库。
   - 回退与本次升级无关的优化（BootReceiver、Room cache、Gson 单例、RouterGroupListFragment 等）到基线。

## 构建方式

```bash
# 环境：JDK 17, Go 1.25.5, Android SDK (platform 36, build-tools 36.0.0), NDK 25.0.8775105
./run lib core
./gradlew app:assembleOssDebug
```

## 已知临时兼容项

- `libcore/go.mod` 中显式 `require github.com/sagernet/gvisor v0.0.0-20260727.0-sing-box-mod.1`。
  由于该 pseudo-version 字符串在 semver 预发布比较中排序低于旧版 `20250325...`，
  不加显式固定时 MVS 会错误地选择旧版 gvisor，导致 `WritePacketDirect` 编译失败。
- `libcore/` 中新增 `config_parse_test.go` 作为最低限度自动验证；
  Windows 宿主上 WireGuard endpoint 相关 case 跳过（需要 `with_gvisor` 标签），在 Android 构建中会自动运行。

## gVisor fallback

保留 `with_gvisor` build tag；`libcore/build.sh` 仍然编译 gVisor 栈。
用户可在设置中将 `TUN implementation` 从 `Go` 切换为 `gVisor` / `System` / `Mixed`。
