#!/usr/bin/env python3
"""把 NekoBox 需要的 nekoutils 钩子补进 sing-box 源码。

用法: patch-singbox.py <sing-box 源码目录>

对应 starifly fork 相对官方的四处定制：
  1. nekoutils/srs.go                  geoip/geosite 规则集加载钩子
  2. nekoutils/callback.go             选择器切换回调
  3. route/rule/rule_set_local.go      识别 "geoip:" / "geosite:" 前缀
  4. protocol/group/selector.go        切换节点时回调

脚本是幂等的：已打过补丁的文件会跳过；锚点匹配不到会报错退出，
避免上游结构变化后静默产出错误的内核。
"""

import pathlib
import subprocess
import sys

SRS_GO = '''package nekoutils

import "github.com/sagernet/sing-box/option"

var GetGeoIPHeadlessRules func(name string) ([]option.HeadlessRule, error)

var GetGeoSiteHeadlessRules func(name string) ([]option.HeadlessRule, error)
'''

CALLBACK_GO = '''package nekoutils

var Selector_OnProxySelected func(selectorTag string, tag string)
'''

RULE_SET_BLOCK = '''\t\tif strings.HasPrefix(options.LocalOptions.Path, "geoip:") {
\t\t\trules, err := nekoutils.GetGeoIPHeadlessRules(strings.TrimPrefix(options.LocalOptions.Path, "geoip:"))
\t\t\tif err != nil {
\t\t\t\treturn nil, err
\t\t\t}
\t\t\terr = ruleSet.reloadRules(rules)
\t\t\tif err != nil {
\t\t\t\treturn nil, err
\t\t\t}
\t\t\treturn ruleSet, nil
\t\t}
\t\tif strings.HasPrefix(options.LocalOptions.Path, "geosite:") {
\t\t\trules, err := nekoutils.GetGeoSiteHeadlessRules(strings.TrimPrefix(options.LocalOptions.Path, "geosite:"))
\t\t\tif err != nil {
\t\t\t\treturn nil, err
\t\t\t}
\t\t\terr = ruleSet.reloadRules(rules)
\t\t\tif err != nil {
\t\t\t\treturn nil, err
\t\t\t}
\t\t\treturn ruleSet, nil
\t\t}
'''

SELECTOR_BLOCK = '''\tif nekoutils.Selector_OnProxySelected != nil {
\t\tnekoutils.Selector_OnProxySelected(s.Tag(), tag)
\t}
'''



VLESS_ENCRYPTION_DIR = pathlib.Path(__file__).resolve().parent / "singbox-vless-encryption"

VLESS_OPTION_FIELD = (
    "\t// Encryption 是 VLESS 内部的后量子加密层（mlkem768x25519plus），\n"
    "\t// 位于传输层之下、与 TLS/REALITY 无关。空或 \"none\" 表示关闭。\n"
    "\tEncryption string `json:\"encryption,omitempty\"`\n"
)

VLESS_FIELD_BLOCK = (
    "\t// VLESS 后量子加密层（mlkem768x25519plus），未配置时为 nil\n"
    "\tencryption *encryption.ClientInstance\n"
)

VLESS_NEW_BLOCK = (
    "\t// VLESS Encryption：层在 VLESS 内部、传输之下，与 TLS/REALITY 无关\n"
    "\tif options.Encryption != \"\" && options.Encryption != \"none\" {\n"
    "\t\tencryptionConfig, err := parseClientEncryption(options.Encryption)\n"
    "\t\tif err != nil {\n"
    "\t\t\treturn nil, E.Cause(err, \"parse encryption\")\n"
    "\t\t}\n"
    "\t\toutbound.encryption = &encryption.ClientInstance{}\n"
    "\t\tif err := outbound.encryption.Init(encryptionConfig.keys, encryptionConfig.xorMode, encryptionConfig.seconds, encryptionConfig.padding); err != nil {\n"
    "\t\t\treturn nil, E.Cause(err, \"initialize encryption\")\n"
    "\t\t}\n"
    "\t}\n"
)


def fail(message: str) -> None:
    print("patch-singbox: " + message, file=sys.stderr)
    sys.exit(1)


def patch_rule_set_local(root: pathlib.Path) -> None:
    path = root / "route" / "rule" / "rule_set_local.go"
    text = path.read_text(encoding="utf-8")
    if "nekoutils.GetGeoIPHeadlessRules" in text:
        print("patch-singbox: rule_set_local.go 已打过补丁，跳过")
        return

    anchor = (
        "\t} else {\n"
        '\t\tfilePath := filemanager.BasePath(ctx, strings.ReplaceAll(options.LocalOptions.Path, C.RuleSetTagPlaceholder, tag))'
    )
    if anchor not in text:
        fail("rule_set_local.go 锚点未匹配，上游结构可能已变化")

    text = text.replace(
        anchor,
        "\t} else {\n" + RULE_SET_BLOCK
        + "\t\tfilePath := filemanager.BasePath(ctx, strings.ReplaceAll(options.LocalOptions.Path, C.RuleSetTagPlaceholder, tag))",
        1,
    )
    const_import = '\tC "github.com/sagernet/sing-box/constant"'
    if const_import not in text:
        fail("rule_set_local.go 找不到 constant import 锚点")
    text = text.replace(
        const_import,
        const_import + '\n\t"github.com/sagernet/sing-box/nekoutils"',
        1,
    )
    path.write_text(text, encoding="utf-8")
    print("patch-singbox: rule_set_local.go patched")


def patch_selector(root: pathlib.Path) -> None:
    path = root / "protocol" / "group" / "selector.go"
    text = path.read_text(encoding="utf-8")
    if "Selector_OnProxySelected" in text:
        print("patch-singbox: selector.go 已打过补丁，跳过")
        return

    anchor = "\ts.interruptGroup.Interrupt(s.interruptExternalConnections)"
    if anchor not in text:
        fail("selector.go 锚点未匹配，上游结构可能已变化")

    text = text.replace(anchor, SELECTOR_BLOCK + anchor, 1)
    log_import = '\t"github.com/sagernet/sing-box/log"'
    if log_import not in text:
        fail("selector.go 找不到 log import 锚点")
    text = text.replace(
        log_import,
        log_import + '\n\t"github.com/sagernet/sing-box/nekoutils"',
        1,
    )
    path.write_text(text, encoding="utf-8")
    print("patch-singbox: selector.go patched")



def patch_vless_encryption(root: pathlib.Path) -> None:
    """移植 VLESS Encryption（mlkem768x25519plus，来自 qingwan 的 1.15 定制内核）。

    上游 sing-box（含 1.15.0-alpha.13 / dev-next）至今未实现该特性，
    这是 Xray 2025 引入的协议层后量子加密。实现文件为纯 Go 无第三方依赖。
    """
    if not VLESS_ENCRYPTION_DIR.is_dir():
        fail("找不到 singbox-vless-encryption 目录，仓库不完整")

    # 1) 拷贝实现文件
    vless_dir = root / "protocol" / "vless"
    if not vless_dir.is_dir():
        fail("protocol/vless 目录不存在，上游结构可能已变化")
    enc_dir = vless_dir / "encryption"
    enc_dir.mkdir(parents=True, exist_ok=True)
    (vless_dir / "lx_encryption.go").write_bytes(
        (VLESS_ENCRYPTION_DIR / "lx_encryption.go").read_bytes()
    )
    for src in sorted((VLESS_ENCRYPTION_DIR / "encryption").glob("*.go")):
        (enc_dir / src.name).write_bytes(src.read_bytes())

    # 2) option/vless.go：新增 encryption 字段
    path = root / "option" / "vless.go"
    text = path.read_text(encoding="utf-8")
    if 'json:"encryption,omitempty"' in text:
        print("patch-singbox: option/vless.go 已打过补丁，跳过")
    else:
        anchor = '\tNetwork NetworkList `json:"network,omitempty"`\n'
        if anchor not in text:
            fail("option/vless.go 锚点未匹配，上游结构可能已变化")
        text = text.replace(anchor, anchor + VLESS_OPTION_FIELD, 1)
        path.write_text(text, encoding="utf-8")
        print("patch-singbox: option/vless.go patched")

    # 3) protocol/vless/outbound.go：结构体字段 / 初始化 / 两处握手接线
    path = root / "protocol" / "vless" / "outbound.go"
    text = path.read_text(encoding="utf-8")
    if "wrapEncryption" in text:
        print("patch-singbox: protocol/vless/outbound.go 已打过补丁，跳过")
        return

    import_anchor = '\t"github.com/sagernet/sing-box/option"\n'
    if import_anchor not in text:
        fail("outbound.go 找不到 option import 锚点")
    text = text.replace(
        import_anchor,
        import_anchor + '\t"github.com/sagernet/sing-box/protocol/vless/encryption"\n',
        1,
    )

    field_anchor = "\tpacketAddr      bool\n\txudp            bool\n"
    if field_anchor not in text:
        fail("outbound.go 结构体锚点未匹配，上游结构可能已变化")
    text = text.replace(field_anchor, field_anchor + VLESS_FIELD_BLOCK, 1)

    new_anchor = (
        "\toutbound.multiplexDialer, err = mux.NewClientWithOptions("
        "(*vlessDialer)(outbound), logger, common.PtrValueOrDefault(options.Multiplex))"
    )
    if new_anchor not in text:
        fail("outbound.go NewOutbound 锚点未匹配，上游结构可能已变化")
    text = text.replace(new_anchor, VLESS_NEW_BLOCK + new_anchor, 1)

    dial_anchor = "\tif err != nil {\n\t\treturn nil, err\n\t}\n\tswitch N.NetworkName(network) {"
    if dial_anchor not in text:
        fail("outbound.go DialContext 锚点未匹配，上游结构可能已变化")
    text = text.replace(
        dial_anchor,
        "\tif err != nil {\n\t\treturn nil, err\n\t}\n"
        "\tconn, err = h.wrapEncryption(ctx, conn)\n"
        "\tif err != nil {\n\t\treturn nil, err\n\t}\n"
        "\tswitch N.NetworkName(network) {",
        1,
    )

    packet_anchor = "\tif err != nil {\n\t\tcommon.Close(conn)\n\t\treturn nil, err\n\t}\n\tif h.xudp {"
    if packet_anchor not in text:
        fail("outbound.go ListenPacket 锚点未匹配，上游结构可能已变化")
    text = text.replace(
        packet_anchor,
        "\tif err != nil {\n\t\tcommon.Close(conn)\n\t\treturn nil, err\n\t}\n"
        "\tconn, err = h.wrapEncryption(ctx, conn)\n"
        "\tif err != nil {\n\t\tcommon.Close(conn)\n\t\treturn nil, err\n\t}\n"
        "\tif h.xudp {",
        1,
    )

    path.write_text(text, encoding="utf-8")
    print("patch-singbox: protocol/vless/outbound.go patched")


XHTTP_BRIDGE_DIR = pathlib.Path(__file__).resolve().parent / "singbox-xhttp"
XHTTP_VERSION = "0.1.6"

XHTTP_OPTION_TYPES = '''
// V2RayXHTTPOptions 是 Xray XHTTP（split-http）传输的配置。
// 字段名与 App 侧 SingBoxOptions.XHTTPOptions 的序列化结果保持一致，
// 实际传输实现由 github.com/justinwoo280/sing-xhttp 提供。
type V2RayXHTTPOptions struct {
    Mode                 string               `json:"mode,omitempty"`
    Host                 string               `json:"host,omitempty"`
    Path                 string               `json:"path,omitempty"`
    Headers              badoption.HTTPHeader `json:"headers,omitempty"`
    UplinkHTTPMethod     string               `json:"uplink_http_method,omitempty"`
    NoGRPCHeader         bool                 `json:"no_grpc_header,omitempty"`
    NoSSEHeader          bool                 `json:"no_sse_header,omitempty"`
    XPaddingBytes        *V2RayXHTTPRange     `json:"x_padding_bytes,omitempty"`
    ScMaxEachPostBytes   *V2RayXHTTPRange     `json:"sc_max_each_post_bytes,omitempty"`
    ScMaxBufferedPosts   int32                `json:"sc_max_buffered_posts,omitempty"`
    ScMinPostsIntervalMs *V2RayXHTTPRange     `json:"sc_min_posts_interval_ms,omitempty"`
    ScStreamUpServerSecs *V2RayXHTTPRange     `json:"sc_stream_up_server_secs,omitempty"`
    XPaddingObfsMode     bool                 `json:"x_padding_obfs_mode,omitempty"`
    XPaddingPlacement    string               `json:"x_padding_placement,omitempty"`
    XPaddingKey          string               `json:"x_padding_key,omitempty"`
    XPaddingHeader       string               `json:"x_padding_header,omitempty"`
    XPaddingMethod       string               `json:"x_padding_method,omitempty"`
    SessionPlacement     string               `json:"session_placement,omitempty"`
    SessionKey           string               `json:"session_key,omitempty"`
    SessionIDTable       string               `json:"session_id_table,omitempty"`
    SessionIDLength      *V2RayXHTTPRange     `json:"session_id_length,omitempty"`
    SeqPlacement         string               `json:"seq_placement,omitempty"`
    SeqKey               string               `json:"seq_key,omitempty"`
    UplinkDataPlacement  string               `json:"uplink_data_placement,omitempty"`
    UplinkDataKey        string               `json:"uplink_data_key,omitempty"`
    UplinkChunkSize      *V2RayXHTTPRange     `json:"uplink_chunk_size,omitempty"`
    ServerMaxHeaderBytes int32                `json:"server_max_header_bytes,omitempty"`
    // App 侧可能下发但当前实现不使用的字段，保留以免严格解码失败
    CongestionController any                    `json:"congestion_controller,omitempty"`
    Cwnd                 any                    `json:"cwnd,omitempty"`
    Download             any                    `json:"download,omitempty"`
    Xmux                 *V2RayXHTTPXmuxOptions `json:"xmux,omitempty"`
}

type V2RayXHTTPRange struct {
    From int32 `json:"from,omitempty"`
    To   int32 `json:"to,omitempty"`
}

type V2RayXHTTPXmuxOptions struct {
    MaxConcurrency   *V2RayXHTTPRange `json:"max_concurrency,omitempty"`
    MaxConnections   *V2RayXHTTPRange `json:"max_connections,omitempty"`
    CMaxReuseTimes   *V2RayXHTTPRange `json:"c_max_reuse_times,omitempty"`
    HMaxRequestTimes *V2RayXHTTPRange `json:"h_max_request_times,omitempty"`
    HMaxReusableSecs *V2RayXHTTPRange `json:"h_max_reusable_secs,omitempty"`
    HKeepAlivePeriod int32            `json:"h_keep_alive_period,omitempty"`
}
'''


def patch_xhttp(root: pathlib.Path) -> None:
    """接入 Xray XHTTP（split-http）传输。

    上游 sing-box 没有该传输。这里只补三处注册点，传输实现来自
    github.com/justinwoo280/sing-xhttp（不依赖 sing-box，接口结构一致），
    另加一层 option → xhttp.Options 的适配文件 transport/v2ray/xhttp.go。
    """
    bridge = XHTTP_BRIDGE_DIR / "xhttp.go"
    if not bridge.is_file():
        fail("找不到 singbox-xhttp/xhttp.go，仓库不完整")

    # 1) constant/v2ray.go：类型常量
    path = root / "constant" / "v2ray.go"
    text = path.read_text(encoding="utf-8")
    if "V2RayTransportTypeXHTTP" in text:
        print("patch-singbox: constant/v2ray.go 已打过补丁，跳过")
    else:
        anchor = '\tV2RayTransportTypeHTTPUpgrade = "httpupgrade"\n'
        if anchor not in text:
            fail("constant/v2ray.go 锚点未匹配，上游结构可能已变化")
        text = text.replace(anchor, anchor + '\tV2RayTransportTypeXHTTP = "xhttp"\n', 1)
        path.write_text(text, encoding="utf-8")
        print("patch-singbox: constant/v2ray.go patched")

    # 2) option/v2ray_transport.go：选项类型 + 三个 switch 分支
    path = root / "option" / "v2ray_transport.go"
    text = path.read_text(encoding="utf-8")
    if "V2RayXHTTPOptions" in text:
        print("patch-singbox: option/v2ray_transport.go 已打过补丁，跳过")
    else:
        edits = [
            (
                '\tHTTPUpgradeOptions V2RayHTTPUpgradeOptions `json:"-"`\n',
                '\tHTTPUpgradeOptions V2RayHTTPUpgradeOptions `json:"-"`\n'
                '\tXHTTPOptions        V2RayXHTTPOptions        `json:"-"`\n',
            ),
            (
                'enum:"http,ws,quic,grpc,httpupgrade"',
                'enum:"http,ws,quic,grpc,httpupgrade,xhttp"',
            ),
            (
                '\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = o.HTTPUpgradeOptions\n',
                '\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = o.HTTPUpgradeOptions\n'
                '\tcase C.V2RayTransportTypeXHTTP:\n\t\tv = o.XHTTPOptions\n',
            ),
            (
                '\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = &o.HTTPUpgradeOptions\n',
                '\tcase C.V2RayTransportTypeHTTPUpgrade:\n\t\tv = &o.HTTPUpgradeOptions\n'
                '\tcase C.V2RayTransportTypeXHTTP:\n\t\tv = &o.XHTTPOptions\n',
            ),
            (
                '\t\t\t{Value: C.V2RayTransportTypeHTTPUpgrade, StructType: reflect.TypeFor[V2RayHTTPUpgradeOptions]()},\n',
                '\t\t\t{Value: C.V2RayTransportTypeHTTPUpgrade, StructType: reflect.TypeFor[V2RayHTTPUpgradeOptions]()},\n'
                '\t\t\t{Value: C.V2RayTransportTypeXHTTP, StructType: reflect.TypeFor[V2RayXHTTPOptions]()},\n',
            ),
        ]
        for old, new in edits:
            if old not in text:
                fail("option/v2ray_transport.go 锚点未匹配: " + old.strip()[:48])
            text = text.replace(old, new, 1)
        text = text.rstrip("\n") + "\n" + XHTTP_OPTION_TYPES
        path.write_text(text, encoding="utf-8")
        print("patch-singbox: option/v2ray_transport.go patched")

    # 3) transport/v2ray/transport.go：服务端/客户端分支
    path = root / "transport" / "v2ray" / "transport.go"
    text = path.read_text(encoding="utf-8")
    if "V2RayTransportTypeXHTTP" in text:
        print("patch-singbox: transport/v2ray/transport.go 已打过补丁，跳过")
    else:
        server_anchor = (
            "\tcase C.V2RayTransportTypeHTTPUpgrade:\n"
            "\t\treturn v2rayhttpupgrade.NewServer(ctx, logger, options.HTTPUpgradeOptions, tlsConfig, handler)\n"
        )
        client_anchor = (
            "\tcase C.V2RayTransportTypeHTTPUpgrade:\n"
            "\t\treturn v2rayhttpupgrade.NewClient(ctx, dialer, serverAddr, options.HTTPUpgradeOptions, tlsConfig)\n"
        )
        if server_anchor not in text:
            fail("transport/v2ray/transport.go 服务端锚点未匹配")
        if client_anchor not in text:
            fail("transport/v2ray/transport.go 客户端锚点未匹配")
        text = text.replace(
            server_anchor,
            server_anchor
            + "\tcase C.V2RayTransportTypeXHTTP:\n"
            + "\t\treturn NewXHTTPServer(ctx, logger, options.XHTTPOptions, tlsConfig, handler)\n",
            1,
        )
        text = text.replace(
            client_anchor,
            client_anchor
            + "\tcase C.V2RayTransportTypeXHTTP:\n"
            + "\t\treturn NewXHTTPClient(ctx, dialer, serverAddr, options.XHTTPOptions, tlsConfig)\n",
            1,
        )
        path.write_text(text, encoding="utf-8")
        print("patch-singbox: transport/v2ray/transport.go patched")

    # 4) 适配层文件
    (root / "transport" / "v2ray" / "xhttp.go").write_bytes(bridge.read_bytes())

    # 5) go.mod：声明外部依赖（go.sum 由构建时的 -mod=mod 补全）
    path = root / "go.mod"
    text = path.read_text(encoding="utf-8")
    if "justinwoo280/sing-xhttp" not in text:
        anchor = "\nrequire (\n"
        if anchor not in text:
            fail("go.mod 找不到 require 块")
        text = text.replace(
            anchor,
            anchor + "\tgithub.com/justinwoo280/sing-xhttp v" + XHTTP_VERSION + "\n",
            1,
        )
        path.write_text(text, encoding="utf-8")
        print("patch-singbox: go.mod patched")

    # 6) 尽力格式化改动的文件（gofmt 不可用时跳过）
    for target in (
        root / "constant" / "v2ray.go",
        root / "option" / "v2ray_transport.go",
        root / "transport" / "v2ray" / "transport.go",
        root / "transport" / "v2ray" / "xhttp.go",
    ):
        try:
            subprocess.run(["gofmt", "-w", str(target)], capture_output=True, check=False)
        except OSError:
            break


def main() -> None:
    if len(sys.argv) < 2:
        fail("缺少 sing-box 源码目录参数")
    root = pathlib.Path(sys.argv[1])
    if not (root / "go.mod").is_file():
        fail(f"{root} 不像是 sing-box 源码目录")

    nekoutils = root / "nekoutils"
    nekoutils.mkdir(exist_ok=True)
    (nekoutils / "srs.go").write_text(SRS_GO, encoding="utf-8")
    (nekoutils / "callback.go").write_text(CALLBACK_GO, encoding="utf-8")

    patch_rule_set_local(root)
    patch_selector(root)
    patch_vless_encryption(root)
    patch_xhttp(root)
    print("patch-singbox: done")


if __name__ == "__main__":
    main()
