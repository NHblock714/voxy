#version 330 core

layout(binding = 0) uniform sampler2D depthTex;
layout(location = 1) uniform vec2 scaleFactor;
//inverse(vanillaProjection * modelView) and voxy's MVP: covered pixels get their source depth
//reprojected into this buffer's depth space instead of a flat NEAR stamp
layout(location = 2) uniform mat4 invSrcProjection;
layout(location = 3) uniform mat4 dstProjection;
//ndc-z <-> window-z maps of rasterized geometry, set from GL_CLIP_DEPTH_MODE (default clip
//control: window = 0.5*ndc+0.5 - the projection convention alone does not change the
//fixed-function map). xy: dst ndc->window (scale, bias); zw: src window->ndc (scale, bias).
layout(location = 4) uniform vec4 depthRemap;

#import <voxy:util/depthutils.glsl>

in vec2 UV;
void main() {
    float depth = texture(depthTex, UV*scaleFactor).r;
    //"Empty" must be a tolerance test, not an exact compare: shader pipelines write sky/far depth
    //that is close to but not exactly FAR, and an exact match paints the vanilla-coverage sentinel
    //over the whole sky - LOD terrain then never occludes anything drawn on top of it. With the
    //source near plane at 0.05, 1-d ~= near/dist puts 1e-5 at ~5000 blocks - far beyond anything
    //the source can legitimately have drawn.
    if (abs(depth - FAR) < 1e-5) {
        discard;
    }
    //Covered pixels carry the real (reprojected) surface depth rather than an infinitely-near
    //sentinel, so geometry drawn into this buffer before the sentinel is restored (distant
    //trains/tracks) resolves against the actual scene per-pixel instead of being occluded by
    //anything the source ever touched. HiZ builds from these values too, which keeps node culling
    //sane even if a pixel above slips through the emptiness test. Assumes non-reversed depth
    //(reverse-z is unimplemented pipeline-wide).
    vec4 view = invSrcProjection * vec4(UV*2.0-1.0, depth*depthRemap.z + depthRemap.w, 1.0);
    view /= view.w;
    vec4 clip = dstProjection * vec4(view.xyz, 1.0);
    float d = (clip.z/clip.w) * depthRemap.x + depthRemap.y;
    //Clamp inside (empty, nearest]: geometry closer than the destination near plane lands at 0
    //(fully occluding, same as the old sentinel), and covered pixels may never reach the FAR
    //"empty" value
    gl_FragDepth = clamp(d, 0.0, 1.0 - (2.0/((1<<24)-1)));
}