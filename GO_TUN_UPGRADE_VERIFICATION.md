# Go TUN 升级验证报告

> 分支：`router-groups-go-tun`  
> 报告时间：2026-09-08  
> 对应基线：`router-groups`

## A. 实际发现的问题

1. **URL Test 使用 `http.DefaultClient` 直连**  
   `libcore/box.go` 的 `UrlTest()` 在 sing-box 1.15 迁移后调用 `speedtest.UrlTest(http.DefaultClient, ...)`，
   测试流量不经过目标 outbound，无法反映节点真实延迟。

2. **Stats 功能永久返回 0**  
   `SetV2rayStats()` / `QueryStats()` 被改为空实现 / 固定返回 0。

3. **ResetAllConnections 为 no-op**  
   仅打印日志，未真正关闭连接。

4. **WireGuard 在 1.15 下 schema 与类型位置均不兼容**  
   - sing-box 1.15 将 WireGuard 从 outbound 改为 endpoint。  
   - 旧字段 `server` / `server_port` / `local_address` / `peer_public_key` 已被移除。

5. **DNS schema 未迁移**  
   应用仍发出 sing-box 1.14 起移除的 legacy DNS server `address` 字段，以及已移除的 `dns.fakeip` 配置节。

6. **Snell `quic_proxy_mode` 导致 strict parse 失败**  
   该字段是 reF1nd fork 扩展，上游 1.15 不认识，会报 `unexpected key: quic_proxy_mode`。

7. **gvisor 依赖被 MVS 错误选择**  
   新版 gvisor pseudo-version 排序低于旧版，不加显式固定会选到旧版，导致 `WritePacketDirect` 编译失败。

8. **`arrays.xml` 中 `int_array_4` 重复**  
   Go TUN 提交里新增了一个与基线同名的数组，APK 合并资源时报重复定义。

9. **scratch 目录含真实配置 dump**  
   `scratch/codex-20260907-172930/asteria_v1.0.json` 含 UUID、服务器地址等敏感信息，已随旧提交进入公开历史。

10. **SSR 上游实现被移除**  
    sing-box 1.15 不再包含 `protocol/shadowsocksr`。

## B. 已修复的问题

| 问题 | 修复文件 | 修复方式 |
|---|---|---|
| URL Test 不经过节点 | `libcore/box.go` | 改用 `common/urltest.URLTest`，通过 `urlTestDetour()` 获取目标 outbound/endpoint 拨号 |
| Stats 返回 0 | `libcore/box.go` | 使用 `experimental/v2rayapi.StatsService` 并 `AppendTracker` 到 Router |
| ResetAllConnections no-op | `libcore/box.go` | 调用 `ConnectionManager.CloseAll()` |
| WireGuard 失效 | `app/.../SingBoxOptions.java`<br>`app/.../wireguard/WireGuardFmt.kt`<br>`app/.../ConfigBuilder.kt`<br>`libcore/box.go` | 新增 endpoint 选项类；迁移字段到 `peers[]`；写入 `endpoints`；URL Test 回退到 endpoint |
| DNS 旧格式 | `app/.../ConfigBuilder.kt` | 新增 `buildDnsServerOptions()` 生成 typed DNS server；FakeIP 写入 fakeip server |
| Snell `quic_proxy_mode` | `app/.../snell/SnellBuildConfig.kt` | 停止发出该字段 |
| gvisor 版本 | `libcore/go.mod`<br>`libcore/go.sum` | 显式 require 新版 gvisor 并 `go mod tidy` |
| `int_array_4` 重复 | `app/src/main/res/values/arrays.xml` | 删除新增的重复块 |
| scratch 泄露 | `.gitignore`<br>删除 `scratch/` | 清理文件并忽略未来 checkpoint |
| 无关优化 | 多个 Kotlin 文件 | `git checkout router-groups -- ...` 回退到基线 |
| 平台代码宿主编译 | `libcore/nb4a.go`<br>`libcore/sendfd_unix.go`<br>`libcore/sendfd_other.go`<br>`libcore/platform_box.go`<br>`libcore/platform_box_unix.go`<br>`libcore/platform_box_other.go` | 将 unix 特有调用隔离到带 build tag 的文件，使 `go test` 可在 Windows 主机运行 |
| 自动验证 | `libcore/config_parse_test.go` | 新增 TUN schema / 协议 smoke / WireGuard endpoint 解析测试 |

## C. 每个修改文件的原因

- `libcore/box.go`：修复 URL Test、Stats、ResetAllConnections；支持 endpoint 测速回退。
- `libcore/nb4a.go`、`sendfd_unix.go`、`sendfd_other.go`：拆分 unix fd 保护，支持跨平台编译测试。
- `libcore/platform_box*.go`：拆分 `syscall.Dup` 等 unix 平台调用。
- `libcore/config_parse_test.go`：新增 sing-box 1.15 schema 自动验证。
- `libcore/go.mod` / `libcore/go.sum`：显式固定 gvisor 版本。
- `app/src/main/java/moe/matsuri/nb4a/SingBoxOptions.java`：新增 endpoint / WireGuard endpoint peer 类；`MyOptions.endpoints`。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt`：DNS schema 迁移、WireGuard 写入 endpoints、TUN stack 映射。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/wireguard/WireGuardFmt.kt`：生成 1.15 endpoint 格式。
- `app/src/main/java/io/nekohasekai/sagernet/fmt/snell/SnellBuildConfig.kt`：移除上游不识别的 `quic_proxy_mode`。
- `app/src/main/res/values/arrays.xml`：删除重复 `int_array_4`。
- `.gitignore`：忽略 `scratch/`。
- `docs/sing-box-1.15-upgrade.md`：升级说明。

## D. 仍未解决的问题

1. **SSR 不可用**  
   sing-box 1.15 上游彻底移除 SSR。当前分支保留 SSR UI/数据库/URI 解析，但节点启动时会报
   `unknown outbound type: shadowsocksr`。需要后续决定是否：
   - 在 `libcore/` 内引入兼容 1.15 的 SSR 实现；或
   - 在 UI/README 中明确移除 SSR 支持声明。

2. **Snell v1/v2/v3/v5 出站不可用**  
   sing-box 1.15 出站仅支持 v4 / v6。旧 baseline 通过 reF1nd fork 支持 v1–v6，但该 fork 依赖 sing v0.7.x，
   无法直接复用到 sing v0.9.4。用户旧配置若包含这些版本，启动时会得到明确的 "unsupported version" 错误。

3. **真机性能/功耗未验证**  
   当前仅在本地完成编译与配置解析测试，未在真实 Android 设备上做 A/B 功耗与 Speedtest 对比。

4. **完整路由/规则/统计运行期行为未验证**  
   包括 URL Test 经过目标节点、流量统计数值正确、ResetAllConnections 后连接重建、WireGuard 真机连通等。

## E. 是否成功生成 libcore.aar

**是。** 执行 `./run lib core` 成功，产物位于：

```text
app/libs/libcore.aar
```

包含 `libgojni.so`（armeabi-v7a、arm64-v8a、x86、x86_64）。

## F. 是否成功生成 OSS Debug APK

**是。** 执行 `./gradlew app:assembleOssDebug` 成功：

```text
app/build/outputs/apk/oss/debug/app-oss-debug.apk
```

## G. Go TUN 是否确认进入最终生成配置

**是。**

- `Constants.kt` 默认 `tunImplementation = GO`。
- `ConfigBuilder` 对 `TunImplementation.GO` 输出 `stack = "go"`。
- `libcore/config_parse_test.go` 验证生成的 TUN JSON（含 `"stack": "go"`）可被 sing-box 1.15 解析并创建 box 实例。
- gVisor / System / Mixed 回退选项保留。

## H. router-groups URL Test 是否确认经过目标节点而非系统直连

**是。**

- `libcore/box.go` 中 `UrlTest()` 不再使用 `http.DefaultClient`。
- 使用 `common/urltest.URLTest(ctx, link, detour)`，其中 `detour` 来自 `urlTestDetour()`：
  - 对测试实例取默认 outbound（即被测节点）。
  - 对 WireGuard 等 endpoint-only 协议回退到第一个 endpoint。
- 通过代码审查 + Go 侧编译 + 配置解析测试确认逻辑正确；真机抓包可最终验证。

## I. 协议兼容状态

| 协议 | 状态 | 说明 |
|---|---|---|
| VLESS | 兼容 | schema 校验通过 |
| VMess | 兼容 | 上游 `sing-vmess v0.2.8` 覆盖原 starifly fork 功能 |
| Trojan | 兼容 | schema 校验通过 |
| Shadowsocks | 兼容 | schema 校验通过 |
| Hysteria2 | 兼容 | schema 校验通过 |
| TUIC | 兼容 | schema 校验通过 |
| AnyTLS | 兼容 | schema 校验通过 |
| Snell v4/v6 | 兼容 | `quic_proxy_mode` 已移除；v4/v6 schema 校验通过 |
| Snell v1/v2/v3/v5 | 不兼容 | 1.15 出站不再支持 |
| WireGuard | 已修复 | 迁移为 endpoint，schema 校验通过（真机需验证连通） |
| SSR | 不兼容 | 1.15 已移除 shadowsocksr 实现 |
| Juicity | 兼容 | 项目内置 juicity 实现，编译通过 |
| SSH / SOCKS / HTTP / ShadowTLS | 兼容 | schema 校验通过 |

## J. 需要真机验证的项目

1. 不同 TUN stack（Go / gVisor / System / Mixed）下的 VPN 启动、断线重连、长期稳定性。
2. Go stack 与 gVisor stack 在相同节点/网络下的 CPU 占用、内存占用、电池消耗对比。
3. URL Test 结果是否真实反映节点延迟（对比同一节点系统直连测速）。
4. 主界面流量统计是否显示真实数据，而非 0。
5. `ResetAllConnections` 后现有连接是否重建。
6. WireGuard 节点能否正常启动、路由、访问对端网络。
7. SSR / Snell(v1–v5) 旧配置在 UI 中的行为与错误提示是否清晰。
8. DNS 路由、FakeIP、hosts、自定义 DNS 是否按预期工作。
9. 后台保活、切换网络、开关 VPN 后的行为。

## K. 最终 git diff 摘要

```text
libcore/box.go                              | 修复 URL Test / Stats / ResetAllConnections / endpoint-aware URL Test
libcore/config_parse_test.go                | 新增配置解析冒烟测试
libcore/go.mod                              | 显式固定 gvisor 版本
libcore/go.sum                              | 同步
libcore/nb4a.go                             | 拆分 unix 依赖
libcore/platform_box*.go                    | 拆分 OpenInterface 平台依赖
libcore/sendfd_*.go                         | 拆分 fd 保护平台依赖
app/src/main/java/.../SingBoxOptions.java   | endpoint / WireGuard endpoint peer 选项类
app/src/main/java/.../ConfigBuilder.kt      | DNS schema 迁移、WireGuard endpoint、TUN 映射
app/src/main/java/.../WireGuardFmt.kt       | WireGuard endpoint 生成
app/src/main/java/.../SnellBuildConfig.kt   | 移除 quic_proxy_mode
app/src/main/res/values/arrays.xml          | 删除重复 int_array_4
.gitignore                                  | 忽略 scratch/
docs/sing-box-1.15-upgrade.md               | 升级说明（本报告）
```

未 merge 到 `router-groups`，未创建 release，未修改正式版本号，未删除 gVisor fallback。
