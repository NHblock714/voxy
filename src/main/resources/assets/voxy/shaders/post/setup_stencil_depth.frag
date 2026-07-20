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
//Circular vanilla->LOD handover. Radii in blocks, camera-centred and horizontal; the guard flag
//selects the second pass described below.
layout(location = 6) uniform float lodBoundaryFadeStart;
layout(location = 7) uniform float lodBoundaryFadeEnd;
layout(location = 10) uniform int boundaryGuardPass;

#import <voxy:util/depthutils.glsl>

in vec2 UV;

const int BAYER_8X8[64] = int[](
     0, 48, 12, 60,  3, 51, 15, 63,
    32, 16, 44, 28, 35, 19, 47, 31,
     8, 56,  4, 52, 11, 59,  7, 55,
    40, 24, 36, 20, 43, 27, 39, 23,
     2, 50, 14, 62,  1, 49, 13, 61,
    34, 18, 46, 30, 33, 17, 45, 29,
    10, 58,  6, 54,  9, 57,  5, 53,
    42, 26, 38, 22, 41, 25, 37, 21
);

float orderedDither8x8(ivec2 pixel) {
    ivec2 p = pixel & ivec2(7);
    return (float(BAYER_8X8[p.y * 8 + p.x]) + 0.5) * (1.0 / 64.0);
}

//Shared with the baseline path so vanilla-owned pixels keep the reprojected real depth contract.
float projectDepth(vec3 cameraRelativePosition) {
    vec4 clip = dstProjection * vec4(cameraRelativePosition, 1.0);
    float d = (clip.z / clip.w) * depthRemap.x + depthRemap.y;
    return clamp(d, 0.0, 1.0 - (2.0 / ((1 << 24) - 1)));
}
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

    //invSrcProjection is inverse(vanillaProjection * modelView), so view.xyz is camera-relative WORLD
    //space - xz is a true horizontal distance and does not tilt with pitch.
    float horizontalDistance = length(view.xz);
    bool fadeEnabled = lodBoundaryFadeEnd > lodBoundaryFadeStart;
    float lodCoverage = 0.0;
    float ditherValue = 1.0;
    if (fadeEnabled && horizontalDistance > lodBoundaryFadeStart) {
        lodCoverage = smoothstep(lodBoundaryFadeStart, lodBoundaryFadeEnd, horizontalDistance);
        ditherValue = orderedDither8x8(ivec2(gl_FragCoord.xy));
    }

    if (boundaryGuardPass != 0) {
        //Second pass, stencil writes masked off. Only the dithered LOD-won pixels inside the band get
        //here, and they are given the vanilla surface depth pushed slightly further out. Leaving them
        //at the cleared FAR would tell HiZ the column is empty and would stop the pre-translucent hook
        //geometry (distant trains, tracks) from being occluded by the hill in front of it - that hook
        //ignores stencil and relies purely on this depth.
        if (!fadeEnabled
                || horizontalDistance <= lodBoundaryFadeStart
                || horizontalDistance >= lodBoundaryFadeEnd
                || ditherValue >= lodCoverage) {
            discard;
        }
        float rayLength = max(length(view.xyz), 1.0);
        gl_FragDepth = projectDepth(view.xyz * (1.0 + min(2.0 / rayLength, 0.125)));
        return;
    }

    if (fadeEnabled
            && (horizontalDistance >= lodBoundaryFadeEnd
                || (horizontalDistance > lodBoundaryFadeStart && ditherValue < lodCoverage))) {
        //LOD owns this pixel: leave the cleared stencil (1) and depth in place, exactly as the
        //empty-sky branch above does.
        discard;
    }

    //Clamp inside (empty, nearest]: geometry closer than the destination near plane lands at 0
    //(fully occluding, same as the old sentinel), and covered pixels may never reach the FAR
    //"empty" value
    gl_FragDepth = projectDepth(view.xyz);
}