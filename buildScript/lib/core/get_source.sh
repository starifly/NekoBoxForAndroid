#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/hawkff/sing-box.git
fi
pushd sing-box
# Ensure we point at the hawkff fork (a cached checkout may predate the switch
# from starifly) and that the pinned commit is present before checking it out.
git remote set-url origin https://github.com/hawkff/sing-box.git
git fetch origin --tags --force
git checkout "$COMMIT_SING_BOX"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/hawkff/libneko.git
fi
pushd libneko
# Ensure we point at the hawkff mirror (a cached checkout may predate the switch
# to the project org) and that the pinned commit is present before checkout.
git remote set-url origin https://github.com/hawkff/libneko.git
git fetch origin --tags --force
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
