#!/bin/sh
# Copy the voxy lite-shading programs into an installed shaderpack.
# Re-run after every Euphoria Patches / Complementary upgrade: the patcher generates a NEW
# pack folder and these files do not travel with it (it does not delete them either, so the
# old folder still has a copy to fall back on).
#
#   sh deploy.sh "/e/minecraft/.../shaderpacks/ComplementaryUnbound_r5.8.1 + EuphoriaPatches_1.9.3"
#
# Also needs voxy built with voxy_opaque_lite.glsl / voxy_translucent_lite.glsl registered in
# MixinShaderPackSourceNames, otherwise the files are never looked up.

set -e
PACK="$1"
if [ -z "$PACK" ]; then echo "usage: deploy.sh <shaderpack folder>"; exit 1; fi
SRC="$(dirname "$0")/complementary"
DST="$PACK/shaders"
if [ ! -d "$DST/program" ]; then echo "not a shaderpack folder: $DST"; exit 1; fi

for d in program world0 world1 world-1 world_space; do
    if [ -d "$DST/$d" ]; then
        cp "$SRC/$d"/*.glsl "$DST/$d/"
        echo "  -> $d"
    else
        echo "  !! missing dimension folder $d, skipped (that dimension falls back to the standard program)"
    fi
done
echo "done. turn on voxy lodLiteShading and check the log for 'External opaque LITE shader patch applied'."
