#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"

pushd .. >/dev/null

#### sing-box ####
if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/hawkff/sing-box.git
fi
pushd sing-box >/dev/null
git remote set-url origin https://github.com/hawkff/sing-box.git
git fetch origin --tags --force
git checkout "$COMMIT_SING_BOX"
# python3 ../NekoBoxForAndroid/buildScript/lib/core/patch_sing_box_awg.py "$(pwd)"
python3 ../NekoBoxForAndroid/buildScript/lib/core/patch_sing_box_balancers.py "$(pwd)"
popd >/dev/null

#### libneko ####
if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/hawkff/libneko.git
fi
pushd libneko >/dev/null
git remote set-url origin https://github.com/hawkff/libneko.git
git fetch origin --tags --force
git checkout "$COMMIT_LIBNEKO"
popd >/dev/null

#### wireguard-go (amneziawg-go mirror) ####
if [ ! -d "wireguard-go" ]; then
  git clone --no-checkout https://github.com/sagernet/wireguard-go.git wireguard-go
fi
pushd wireguard-go >/dev/null
git checkout "$COMMIT_WIREGUARD_GO"
popd >/dev/null

popd >/dev/null
