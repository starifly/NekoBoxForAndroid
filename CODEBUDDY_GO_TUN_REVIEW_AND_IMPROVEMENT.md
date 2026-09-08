# NekoBoxForAndroid `router-groups-go-tun` 改进任务

目标仓库：`https://github.com/Gitefy/NekoBoxForAndroid`

目标分支：`router-groups-go-tun`

基线分支：`router-groups`

当前 Go TUN 分支提交：`c841034de16e24ee66e7928017458ccb1a29b0dd`

当前 sing-box 固定提交：`cf69a007cd45311fbd5d7bafe39e5eda3267089f` (`Add go TUN stack`)

当前 sing-tun：`v0.9.1-0.20260908051822-65ba32b917d5`

---

## 0. 总原则

这是一次 **sing-box 1.15 / Go TUN 核心升级**，不要顺便做无关性能优化或 UI 重构。

优先级顺序：

1. 保证原有功能不回归。
2. 保证 Go TUN 真正启用并可回退。
3. 保证可编译、可安装、可真机运行。
4. 最后再做性能优化。

禁止为了“先编译通过”把已有功能改成 no-op、固定返回 0、默认直连或静默删除协议支持。

所有修改分成小提交，每个提交只解决一类问题。

---

# P0：必须先修复的回归

## 1. 修复 URL Test / Router Groups 测速路径

当前 `libcore/box.go` 中，因为旧 `boxapi.CreateProxyHttpClient` 被 sing-box 1.15 移除，代码临时改成了：

```go
speedtest.UrlTest(http.DefaultClient, ...)
```

这是不可接受的临时兼容方案。

原因：

- `http.DefaultClient` 走系统直连；
- 它没有通过待测试的 sing-box Box / outbound；
- Router Group 的 URL Test、节点延迟测试、自动选择可能得到错误结果；
- 直连能访问时会产生“所有节点都正常”的假象；
- 直连不能访问时又可能产生“所有节点失败”的假象。

### 任务

研究 sing-box 1.15 当前 API，重新实现一个 **真正通过指定 Box/outbound 路由的 HTTP client**。

优先寻找上游已有实现或等价 API，不要自行重造复杂拨号栈。

检查：

- `experimental/v2rayapi`
- router / outbound manager
- dialer / connection manager
- libbox 中 URLTest 或 HTTP client 的实现
- sing-box for Android 当前版本如何做 URL Test

### 验收

至少验证：

1. 测试节点 A 时流量确实经过节点 A；
2. 测试节点 B 时流量确实经过节点 B；
3. direct test 与 proxy test 行为不同且符合预期；
4. Router Group 自动测试/选择恢复正常；
5. 禁止继续使用 `http.DefaultClient` 作为代理节点 URL Test 的正式实现。

---

## 2. 恢复流量统计能力

当前升级代码把：

- `SetV2rayStats`
- `QueryStats`

改成了 no-op / 固定返回 0。

这属于明确功能回归。

### 任务

调查 sing-box 1.15 中原 `boxapi` stats 的迁移位置。

优先检查：

```text
experimental/v2rayapi
```

以及当前上游 Clash API / V2Ray API / connection tracker 的实现。

重新实现：

- Stats service 初始化；
- 指定 outbound 流量跟踪；
- `QueryStats()`；
- 原有 gomobile ABI 尽量保持不变。

### 验收

如果 UI 原来能显示流量，则升级后必须继续显示真实数据，而不是永久为 0。

如果确认该功能已经彻底无法用原方式实现，不允许静默 no-op：必须输出技术报告说明替代方案和影响，再等待确认。

---

## 3. 恢复 ResetAllConnections

当前：

```go
ResetAllConnections(system bool)
```

在 sing-box 1.15 升级后被改成日志 + no-op。

### 任务

检查 sing-box 1.15 对连接管理的替代 API，例如：

- trafficcontrol
- connection manager
- outbound interrupt / reset
- router connection tracking

恢复真正关闭/重置连接的能力。

不要为了 ABI 保留而留下假实现。

### 验收

调用 ResetAllConnections 后，已有 TCP/UDP 连接应真正重建，而不仅是打印日志。

---

## 4. 检查 WireGuard 支持是否被意外删除

`nekoboxAndroidOutboundRegistry()` 中原 WireGuard 注册被删除。

sing-box 新版本可能已经把 WireGuard 从传统 outbound 迁移成 endpoint，因此不要机械把旧代码加回来。

### 任务

确认 sing-box 1.15 的 WireGuard 架构：

- 是否已经从 outbound 迁移到 endpoint；
- NekoBox 当前 WireGuard bean/config 是否已经同步新 schema；
- endpoint registry 是否注册 WireGuard；
- 旧用户配置是否可以迁移；
- WireGuard 节点能否真正启动。

### 验收

项目 README 声明支持 WireGuard，则升级后不得因为核心升级而静默失效。

---

## 5. 检查 ShadowsocksR 支持

升级中 `shadowsocksr.RegisterOutbound()` 被删除。

### 任务

确认 sing-box 1.15 是否彻底移除了上游 SSR。

如果 NekoBox 仍计划支持 SSR：

- 查明原项目通过什么 fork/registry 提供；
- 寻找兼容 sing 0.9.x / sing-box 1.15 的实现；
- 不要只为了编译通过删除功能。

如果确实暂时不能兼容：

- 必须明确标注当前分支 SSR 不可用；
- 不要在 UI/README 中继续假装可用；
- 给出后续恢复方案。

但在没有确认前，不要删除用户已有 SSR 配置数据。

---

## 6. 检查被禁用的协议 fork

`libcore/go.mod` 中原来的：

```text
replace github.com/sagernet/sing-vmess => github.com/starifly/sing-vmess ...
replace github.com/sagernet/sing-snell => github.com/reF1nd/sing-snell ...
```

被注释掉。

### 任务

逐个检查：

- 为什么旧项目需要这些 fork；
- fork 相对 upstream 提供了哪些 NekoBox 必需能力；
- 1.15 是否已经吸收这些修改；
- 当前 upstream 版本是否会导致 VMess / Snell 行为变化。

不要为了依赖解析简单就默认删除 fork。

如果 upstream 已完全覆盖功能，需要在报告中列出证据后再保留 upstream。

---

# P0：清理仓库污染

## 7. 删除所有 scratch / checkpoint 文件

当前 Go TUN 提交把以下开发临时文件一起提交到了正式分支：

```text
scratch/codex-20260907-172930/
scratch/codex_check_config.go
scratch/codex_fix_config.py
scratch/generate_v1_config.py
scratch/parse_backup.py
```

其中还包括完整 JSON 配置 checkpoint。

### 任务

1. 从正式分支删除整个 `scratch/`；
2. 检查这些文件是否含服务器地址、UUID、密码、token、私钥等真实配置；
3. 如果含敏感数据，立即报告，不要在输出里复制敏感内容；
4. 将 `scratch/`、临时导出配置等加入 `.gitignore`；
5. 不要把开发 checkpoint 放进正式代码仓库。

注意：如果真实密钥曾进入公开 Git 历史，仅删除文件不能撤销泄露，需要提示用户旋转相关凭证。

---

# P1：把 Go TUN 升级本身做干净

## 8. 把无关优化从 Go TUN 提交中拆出去

当前同一提交还包含：

- BootReceiver `goAsync()` 修改；
- RoomPreferenceDataStore read-through cache；
- Gson 单例复用；
- RouterGroupListFragment rebuild 优化；
- 其他与 sing-box 1.15 无直接关系的优化。

这些改动未必错，但不应与核心升级绑在一起。

### 任务

优先恢复到基线实现，把这些无关优化从 Go TUN 升级 commit 中拆出去。

推荐最终 commit 结构：

```text
1. chore: clean scratch/checkpoint files
2. feat: upgrade sing-box/sing-tun dependencies for 1.15
3. refactor: adapt NekoBox platform interfaces to sing-box 1.15
4. feat: add Go TUN option and default for new installs
5. fix: restore URL test on sing-box 1.15
6. fix: restore stats/reset-connections compatibility
7. fix: restore/verify protocol compatibility
```

Room cache、BootReceiver、Gson 等另开独立 commit/branch，之后单独测试。

尤其 RoomPreferenceDataStore cache 有 stale-cache 和并发一致性风险，不要在 Go TUN 升级阶段引入额外状态缓存。

---

## 9. Go TUN 默认值策略

当前代码增加：

```text
GVISOR = 0
SYSTEM = 1
MIXED  = 2
GO     = 3
```

新安装默认：

```text
tunImplementation = GO
```

这个方向正确。

### 要求

- 新安装默认 Go；
- 保留 gVisor/System/Mixed 手动回退；
- 暂时保留 `with_gvisor` build tag；
- 不要因为有 Go stack 就删除 gVisor；
- 老用户如果数据库中已经存有 0/1/2，默认尊重旧设置，不强制迁移，避免升级后行为突变。

后续真机验证稳定后，再决定是否把老用户自动迁移到 Go。

---

## 10. 校验 TUN schema，不要长期手工维护 SingBoxOptions.java

当前已将：

```text
inet4_address / inet6_address
```

迁移到：

```text
address
```

并删除 `endpoint_independent_nat` 等旧字段。

方向符合 sing-box 1.15 schema 变化，但 `SingBoxOptions.java` 是高风险文件。

### 任务

确认该文件原本是否由生成器生成。

如果可以生成：

- 必须使用与固定 sing-box commit 对应的 generator 重新生成；
- 不要手工长期维护数千行 schema 映射。

如果只能手工维护：

至少对所有当前 NekoBox 实际使用字段做 schema 对照测试。

重点检查：

- TUN inbound
- DNS
- route/rule
- WireGuard endpoint
- selector/urltest
- V2Ray transport
- Hysteria/TUIC/AnyTLS/Snell

---

## 11. 固定上游依赖，保证可复现

当前固定 sing-box commit：

```text
cf69a007cd45311fbd5d7bafe39e5eda3267089f
```

这是当前一次 `Add go TUN stack` 提交。

暂时可以使用 commit pin，但注意 sing-box testing 分支近期可能发生 rebase/force-push，同标题提交可能出现不同 SHA。

### 要求

- 不要跟随浮动 `testing` HEAD；
- 所有 dependency 使用明确版本或 commit；
- `go.mod` / `go.sum` 必须由 `go mod tidy` 正常生成；
- 写入一个简短 `docs/sing-box-1.15-upgrade.md`，记录：
  - sing-box SHA；
  - sing SHA；
  - sing-tun SHA/version；
  - Go 版本；
  - NDK 版本；
  - 已知临时兼容项。

等正式 `1.15.0-alpha.3` / beta / stable 发布后，再单独评估是否从 commit pin 切换到 release tag。

不要自动追最新 HEAD。

---

# P1：构建与测试

## 12. 安装完整的可验证构建环境

为了真正完成这次工作，需要让 CodeBuddy 能执行编译，而不是只做静态代码修改。

### 必需环境

至少准备：

```text
JDK 17
Go >= go.mod 要求版本（当前为 Go 1.25.5）
Android SDK command-line tools
Android SDK platform / build-tools（按项目 Gradle 要求安装）
Android NDK 25.0.8775105
gomobile-matsuri / gobind-matsuri（按项目现有 run 脚本安装）
```

不需要安装完整 Android Studio GUI；Android SDK command-line tools + 对应 platform/build-tools + NDK 即可。

### 构建顺序

优先使用仓库已有脚本，不要自己发明构建流程：

```bash
./run lib core
./gradlew app:assembleOssDebug
```

如果项目 `./run init action gradle` 有初始化要求，则按 GitHub Actions 的流程执行。

---

## 13. 添加最低限度自动验证

至少增加以下检查：

### A. 配置生成测试

生成 VPN 配置后断言：

```json
{
  "type": "tun",
  "stack": "go"
}
```

并确认 `address` 正确包含 IPv4/IPv6 CIDR。

分别覆盖：

- IPv4 only；
- IPv6 only；
- dual stack；
- Go；
- gVisor；
- System；
- Mixed。

### B. sing-box config parse test

把 NekoBox 生成的配置交给当前固定 sing-box 核心解析，禁止只测试 JSON 能否序列化。

目标是捕获 1.15 schema breaking changes。

### C. libcore ABI test

现有 `verifyLibcore` 继续保留，并确保：

- `libcore.aar` 存在；
- `libgojni.so` 存在；
- gomobile Java ABI 与 App 调用一致。

### D. Protocol smoke tests

至少确认配置可构建：

- VLESS
- VMess
- Trojan
- Shadowsocks
- Hysteria2
- TUIC
- AnyTLS
- Snell
- WireGuard
- SSR（如果项目继续宣称支持）

无需全部真实连接服务器，但配置构建和 core parse 必须通过。

---

# P2：真机 Go TUN 验证

## 14. 不要只跑 Speedtest

真机安装 Debug APK 后做 A/B：

```text
Go stack
vs
gVisor
vs
Mixed
```

使用相同：

- 手机；
- Wi-Fi/5G 网络；
- 节点；
- MTU；
- 测试服务器；
- 时间窗口。

### 记录指标

1. 空闲 10 分钟 CPU；
2. 下载持续 10 分钟 CPU；
3. PSS/RSS；
4. GC 次数/压力；
5. 手机电池温度/机身温升；
6. 峰值下载；
7. 峰值上传；
8. UDP/QUIC；
9. IPv6；
10. 屏幕熄灭后连接保持；
11. Wi-Fi ↔ 蜂窝网络切换；
12. VPN 重连；
13. Router Group URL Test 是否真实经过节点。

只有这些指标通过后，才评价 Go TUN 是否真正改善能效。

---

# P2：CI

## 15. 检查 GitHub Actions

当前分支没有看到实际 workflow run 结果，因此不能把“推送成功”视为“编译成功”。

确保 CI 至少执行：

```text
Native Build (LibCore)
Build OSS APK
```

如果 Actions 在仓库里被禁用，至少在 CodeBuddy 环境完整执行同等命令并保存日志。

CI 失败时先修复根因，不允许通过删除功能、no-op、跳过 task 来让 CI 变绿。

---

# 明确禁止

不要进行以下操作：

- 不要继续用 `http.DefaultClient` 代替节点代理测速；
- 不要让 `QueryStats()` 永久返回 0；
- 不要让 `ResetAllConnections()` 永久 no-op；
- 不要为了编译通过静默删除 WireGuard/SSR/VMess/Snell 等能力；
- 不要删除 gVisor fallback；
- 不要跟踪浮动 testing HEAD；
- 不要把 scratch/checkpoint/config dump 提交到仓库；
- 不要在这次 Go TUN 任务里继续扩展 Room cache、UI 性能优化等无关修改；
- 不要在没有编译和 config parse 验证的情况下声明完成。

---

# 工作方式（适合 GLM 5.3 Flash）

一次只做一个 P0 子任务。

每完成一个任务：

1. 输出修改文件列表；
2. 输出为什么修改；
3. 执行针对性测试；
4. 执行 `./run lib core`（环境允许时）；
5. 需要 App 层变更时执行 `./gradlew app:assembleOssDebug`；
6. 报告测试结果；
7. 单独 commit；
8. 再进行下一个任务。

不要一次性重写大量代码。

遇到不确定的 sing-box 1.15 API，先读取上游当前固定 commit 对应源码，禁止凭旧版本经验猜 API。

---

# 第一轮执行顺序

请现在按以下顺序开始：

1. 删除并 ignore `scratch/`，检查是否可能含敏感信息；
2. 把无关优化从 Go TUN 升级变更中分离，优先回到基线实现；
3. 修复 URL Test，使测试流量真正经过目标 Box/outbound；
4. 恢复 Stats；
5. 恢复 ResetAllConnections；
6. 检查 WireGuard / SSR / VMess fork / Snell fork 回归；
7. 对照 sing-box 1.15 schema 完成配置迁移审计；
8. 完整编译 `libcore.aar`；
9. 构建 `assembleOssDebug`；
10. 输出一份 `GO_TUN_UPGRADE_VERIFICATION.md`，记录编译结果、仍存在的问题和真机测试清单。

不要直接做 release APK。

先得到一个功能完整、可调试、可验证的 Debug 版本。
