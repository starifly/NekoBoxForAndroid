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
    print("patch-singbox: done")


if __name__ == "__main__":
    main()
