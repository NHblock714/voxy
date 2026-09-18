#!/bin/sh
# Copy the voxy lite-shading programs into an installed shaderpack.
# Re-run after every Euphoria Patches / Complementary upgrade: the patcher generates a NEW
# pack folder and these files do not travel with it (it does not delete them either, so the
# old folder still has a copy to fall back on).
#
#   sh deploy.sh "/e/minecraft/.../shaderpacks/ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5"
#
# Also needs voxy built with voxy_opaque_lite.glsl / voxy_translucent_lite.glsl registered in
# MixinShaderPackSourceNames, otherwise the files are never looked up.

set -e
PACK="$1"
if [ -z "$PACK" ]; then echo "usage: deploy.sh <shaderpack folder>"; exit 1; fi
SRC="$(dirname "$0")/complementary"
DST="$PACK/shaders"
if [ ! -d "$DST/program" ]; then echo "not a shaderpack folder: $DST"; exit 1; fi

# The lite programs hand the pack's own DoLighting call straight through - deferred1, taa and
# composite5 read the gbuffer fields it feeds and cannot tell which program produced them, so the
# call has to stay byte for byte identical on both sides. mainLighting.glsl:52 declares exactly one
# DoLighting and GLSL has no default arguments, so a pack that adds a parameter turns the copy in
# complementary/program/ into a compile error - and nothing downstream says so: the fallback in
# IrisShaderPatch swaps back to voxy_opaque.glsl only when the lite file is MISSING, never when it
# fails to compile, so the log still prints "External opaque LITE shader patch applied" over unlit
# LOD terrain. Only program/ carries the call; the world* files on both sides are #include wrappers.
dolighting() { sed -n '/DoLighting(/,/);/p' "$1" | grep -v '^[[:space:]]*//' | tr -d ' \t\n'; }
for p in opaque translucent; do
    if [ "$(dolighting "$SRC/program/voxy_${p}_lite.glsl")" != "$(dolighting "$DST/program/voxy_${p}.glsl")" ]; then
        echo "!! DoLighting call differs from $DST/program/voxy_${p}.glsl"
        echo "   port the pack's call into complementary/program/voxy_${p}_lite.glsl first; deploying now compiles to nothing"
        exit 1
    fi
done

for d in program world0 world1 world-1 world_space; do
    if [ -d "$DST/$d" ]; then
        cp "$SRC/$d"/*.glsl "$DST/$d/"
        echo "  -> $d"
    else
        echo "  !! missing dimension folder $d, skipped (that dimension falls back to the standard program)"
    fi
done
echo "done. turn on voxy lodLiteShading and check the log for 'External opaque LITE shader patch applied'."
