#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"

# 记住仓库根：下面要 pushd 到仓库父目录去 clone 依赖（../sing-box、../libneko）
REPO_ROOT="$(pwd)"
pushd ..

#### sing-box 内核源码
# 默认取官方仓库；想换回 starifly fork 可用 SING_BOX_REPO 覆盖
SING_BOX_REPO="${SING_BOX_REPO:-https://github.com/SagerNet/sing-box.git}"

if [ ! -d "sing-box" ]; then
  git clone --no-checkout "$SING_BOX_REPO"
fi
pushd sing-box
git fetch origin "$COMMIT_SING_BOX" 2>/dev/null || git fetch origin
git checkout -f "$COMMIT_SING_BOX"
popd

#### 应用 NekoBox 定制补丁（geoip/geosite 钩子 + 选择器回调）
# 必须用绝对路径：当前目录已是仓库父目录
python3 "$REPO_ROOT/buildScript/lib/core/patch-singbox.py" sing-box

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/starifly/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
