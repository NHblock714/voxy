/////////////////////////////////////
// Complementary Shaders by EminGT //
/////////////////////////////////////

// Voxy LOD shading, cut down. Loaded in place of /program/voxy_opaque.glsl while voxy's
// lodLiteShading option is on.
//
// The cuts follow /program/dh_terrain.glsl, which is what the pack itself shades far terrain
// with: no per material IPBR tree (:128-150), materialMask/smoothnessD flat (:207), dither
// constant (:200). The gbuffer layout, the DoLighting call and the lmCoord/emission packing in
// gbufferData6.a must stay byte for byte identical to the standard program - deferred1, taa and
// composite5 all read those fields and cannot tell which program wrote them.

#define VOXY_PATCH
#define texture2DLod textureLod
#define texture2D texture

mat4 gbufferModelView = vxModelView;
mat4 gbufferModelViewInverse = vxModelViewInv;
mat4 gbufferPreviousModelView = vxModelViewPrev;
mat4 gbufferProjection = vxProj;
mat4 gbufferProjectionInverse = vxProjInv;
mat4 gbufferPreviousProjection = vxProjPrev;

//Common//
#include "/lib/common.glsl"
#include "/lib/shaderSettings/materials.glsl"
#include "/lib/shaderSettings/shockwave.glsl"
#include "/lib/shaderSettings/interactiveFoliage.glsl"
#include "/lib/shaderSettings/emissionMult.glsl"
#include "/lib/shaderSettings/wavingBlocks.glsl"
//#define NIGHT_DESATURATION

//LOD Downgrade Level//
// 1 - only what cannot be seen at LOD range: no per material IPBR tree, no held light, no vanilla
//     AO term, no lightning flash. The materials the IPBR tree lit or shaped differently enough to
//     read as a step across the LOD boundary are restored by hand further down.
// 2 - 1 plus no screenspace shadow reprojection on LOD. This one is NOT free: SHADOW_QUALITY -1
//     also switches on the no-shadow ambient compensation at mainLighting.glsl:655-659, which
//     vanilla terrain never gets (it compiles with SHADOW_QUALITY 2). Away from noon that is sun
//     light / 1.6 and ambient up to * 1.6 on LOD only, i.e. a brightness step at the LOD boundary
//     every morning and evening, strongest on upward faces. It also opens :219-228, giving distant
//     grass and leaves a different subsurface model. Measure with it, ship with it only if the
//     boundary looks acceptable to you at dawn.
// Keep this the same value in voxy_translucent_lite.glsl.
#define LOD_LITE_LEVEL 1

// The settings header is pulled in here so the overrides below survive
// /lib/lighting/mainLighting.glsl:1 including it again (it is include guarded).
#include "/lib/shaderSettings/mainLighting.glsl"

// Held light falls off with the 8th power of distance: heldLighting.glsl:42 adds 6.0 to the
// player distance, :75 takes the 4th power, :78 squares it again. At the first LOD ring
// (>= vanilla render distance) it is ~1e-13, i.e. tens of orders below the 8 bit output step,
// and mainLighting.glsl:765 folds it in as sqrt(pow2(blockLighting) + heldLighting), which for
// heldLighting == 0 is exactly blockLighting.
#undef HELD_LIGHTING_MODE
#define HELD_LIGHTING_MODE 0

// mainLighting.glsl:544 samples noisetex at vec2(frameTimeCounter * 0.06) - a coordinate with
// no per pixel term, so every LOD pixel pays a texture fetch for one number per frame. Blocklight
// at LOD range is a fraction of skylight; losing its flicker is not visible against near terrain.
#undef BLOCKLIGHT_FLICKERING
#define BLOCKLIGHT_FLICKERING 0

// mainLighting.glsl:793 reads vanillaAO from glColor.a. On voxy glColor is parameters.tinting,
// and every tint the model factory uploads is OR'd with 0xFF000000 (ModelFactory.java:552, :818,
// :966, BiomeBlendPalette.java:96), plus untinted quads use vec4(1) - so glColor.a is exactly 1.0
// and the whole block collapses to vanillaAO = 1.0. Removing it is a bit exact no-op.
#undef VANILLAAO_I
#define VANILLAAO_I 0

// With IPBR on, /lib/materials/materialHandling/terrainMaterials.glsl:12 pulls in
// terrainIPBR.glsl - 3646 lines, ~15 levels of nested if, ~500 material ids - and on a fill bound
// pass that is branch divergence and register pressure on every LOD pixel. With IPBR off the same
// header falls through to the pack's own compact cascade (terrainMaterials.glsl:26-92): foliage,
// leaves, vines, lava and the automatic emission range, which is the same set dh_terrain.glsl
// keeps. Cost: no per material smoothness/highlight on distant stone, ore, metal and glass;
// emission comes from the light level test and the material fixups below instead of ~200 hand
// written assignments.
#undef IPBR

// mainLighting.glsl:127-137 runs getLightningPos + lightningFlashEffect (two length(), three
// exp(), a normalize and a mat3 multiply) on every pixel of every frame, then multiplies the
// result by isLightningActive(), which is zero except during a strike. Cost: LOD terrain does
// not light up during thunderstorm flashes while near terrain does.
#undef RAIN_ATMOSPHERE

// Cloud shadows stay with the pack. Halving cloudShadows.glsl:81-93 is not worth it: its 8 taps
// sit on a 0.005 uv ring, 0.64 texel apart on a 128x128 noisetex, so they share one cache line
// and only 4 TMU issues are on the table - against a local copy of pack code to re-diff on every
// pack update.

#if LOD_LITE_LEVEL >= 2
    // mainLighting.glsl:415 - with SHADOW_QUALITY at its normal value the VOXY_PATCH branch at
    // :419-449 reprojects last frame's screenspace shadows: four mat4 x vec4, two divides and a
    // dependent texture2D(colortex18). At -1 it takes :416 instead. The shadowMult half of that
    // does match what vanilla terrain past shadowDistance * 0.9166667 (117 blocks here) gets from
    // :411 - but the compile time consequences at :219-228 and :655-659 do not apply to vanilla
    // terrain at any distance. See the level notes at the top of this file.
    #undef SHADOW_QUALITY
    #define SHADOW_QUALITY -1
#endif

//////////Fragment Shader//////////Fragment Shader//////////Fragment Shader//////////
#ifdef FRAGMENT_SHADER

//Pipeline//
layout(location = 0) out vec4 gbufferData0;
layout(location = 1) out vec4 gbufferData6;
#ifdef SS_BLOCKLIGHT
    layout(location = 2) out vec4 gbufferData9;
#endif

//Common Variables//
vec3 sunVec = GetSunVector();
vec3 upVec = normalize(gbufferModelView[1].xyz);
vec3 eastVec = normalize(gbufferModelView[0].xyz);
vec3 northVec = normalize(gbufferModelView[2].xyz);

float SdotU = dot(sunVec, upVec);
float sunFactor = SdotU < 0.0 ? clamp(SdotU + 0.375, 0.0, 0.75) / 0.75 : clamp(SdotU + 0.03125, 0.0, 0.0625) / 0.0625;
float sunVisibility = clamp(SdotU + 0.0625, 0.0, 0.125) / 0.125;
float sunVisibility2 = sunVisibility * sunVisibility;
float shadowTimeVar1 = abs(sunVisibility - 0.5) * 2.0;
float shadowTimeVar2 = shadowTimeVar1 * shadowTimeVar1;
float shadowTime = shadowTimeVar2 * shadowTimeVar2;
float skyLightCheck = 0.0;
int blockLightEmission = 0;

int mat;
float NdotU;
float NdotUmax0;
vec2 lmCoord;
vec2 lmCoordM;
vec3 normal;
vec4 glColor;

#ifdef OVERWORLD
    vec3 lightVec = sunVec * ((timeAngle < 0.5325 || timeAngle > 0.9675) ? 1.0 : -1.0);
#else
    vec3 lightVec = sunVec;
#endif

//Common Functions//
void DoFoliageColorTweaks(inout vec3 color, inout vec3 shadowMult, inout float snowMinNdotU, vec3 viewPos, vec3 nViewPos, float lViewPos, float dither) {
    #ifdef SNOWY_WORLD
        if (glColor.g - glColor.b > 0.01)
            snowMinNdotU = min(pow2(pow2(max0(color.g * 2.0 - color.r - color.b))) * 5.0, 0.1);
        else
            snowMinNdotU = min(pow2(pow2(max0(color.g * 2.0 - color.r - color.b))) * 3.0, 0.1) * 0.25;
    #endif
}

//Includes//
#include "/lib/util/spaceConversion.glsl"
#include "/lib/util/dither.glsl"

#ifdef ATM_COLOR_MULTS
    #include "/lib/colors/colorMultipliers.glsl"
#endif

#ifdef TAA
    #include "/lib/antialiasing/jitter.glsl"
#endif

#define GBUFFERS_TERRAIN
    #include "/lib/lighting/mainLighting.glsl"
#undef GBUFFERS_TERRAIN

#ifdef SNOWY_WORLD
    #include "/lib/materials/materialMethods/snowyWorld.glsl"
#endif

// /lib/misc/distantLightBokeh.glsl is not included: with IPBR off nothing calls it any more
// (its only users are terrainIPBR.glsl and froglights.glsl). The emission below is derived from
// the already mip filtered parameters.sampledColour, so it has no high frequency source to twinkle.

#if SEASONS > 0 || defined MOSS_NOISE_INTERNAL || defined SAND_NOISE_INTERNAL
    #include "/lib/materials/overlayNoise.glsl"
#endif

#ifdef SS_BLOCKLIGHT
    #include "/lib/lighting/coloredBlocklight.glsl"
#endif

//Program//
void voxy_emitFragment(VoxyFragmentParameters parameters) {
    // Prepare
        mat = int(parameters.customId);
        lmCoord = clamp((parameters.lightMap - 0.03125) * 1.06667, vec2(0.0), vec2(0.9333, 1.0));
        lmCoordM = lmCoord;
        normal = upVec;
        switch (uint(parameters.face) >> 1u) {
            case 0u:
            normal = vxModelView[1].xyz;
            break;
            case 1u:
            normal = vxModelView[2].xyz;
            break;
            case 2u:
            normal = vxModelView[0].xyz;
            break;
        }
        if ((parameters.face & 1) == 0) {
            normal = -normal;
        }
        NdotU = dot(normal, upVec);
        NdotUmax0 = max(NdotU, 0.0);
        glColor = parameters.tinting;
    //

    #if SEASONS > 0
        skyLightCheck = pow2(1.0 - min1(lmCoord.y * 2.9 * sunVisibility)); // seasons.glsl is its only reader once IPBR is off
    #endif
    vec4 color = parameters.sampledColour * vec4(glColor.rgb, 1.0);

    vec3 screenPos = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z);
    #ifdef TAA
        vec3 viewPos = ScreenToView(vec3(TAAJitter(screenPos.xy, -0.5), screenPos.z));
    #else
        vec3 viewPos = ScreenToView(screenPos);
    #endif
    float lViewPos = length(viewPos);
    vec3 nViewPos = normalize(viewPos);
    vec3 playerPos = mat3(vxModelViewInv) * viewPos + vxModelViewInv[3].xyz;
    vec3 worldPos = playerPos + cameraPosition;

    // Constant like dh_terrain.glsl:200. The only consumer is the PERPENDICULAR_TWEAKS derivative
    // path inside the shadow sampling block (mainLighting.glsl:306), which VOXY_PATCH never compiles.
    float dither = 0.5;

    int subsurfaceMode = 0;
    bool noSmoothLighting = false, noDirectionalShading = false, noVanillaAO = false, centerShadowBias = false, noGeneratedNormals = false, doTileRandomisation = true;
    float smoothnessD = 0.0, materialMask = 0.0;
    float smoothnessG = 0.0, highlightMult = 1.0, emission = 0.0, noiseFactor = 1.0, snowFactor = 1.0, snowMinNdotU = 0.0, noPuddles = 0.0;
    vec3 geoNormal = normal, normalM = normal, shadowMult = vec3(1.0);
    vec3 worldGeoNormal = normalize(mat3(vxModelViewInv) * normal);

    bool isFoliage = false;
    float overlayNoiseIntensity = 1.0, snowNoiseIntensity = 1.0, sandNoiseIntensity = 1.0, mossNoiseIntensity = 1.0, overlayNoiseTransparentOverwrite = 0.0, overlayNoiseEmission = 1.0, IPBRMult = 1.0, lavaNoiseIntensity = LAVA_NOISE_INTENSITY, enderDragonDead = 1.0, purkinjeOverwrite = 0.0;
    vec3 dhColor = vec3(1.0);

    bool isLightSource = false;
    // Light sources. voxy stores the block's OWN light level, and getLightmapUv (voxy lod/lighting.glsl:4-7)
    // tops out at 15 * 16 / 256 = 0.9375, which the remap above clamps to 0.9333 - so the standard
    // program's "lmCoord.x > 0.99" test can never fire here, and DoAutomaticEmission's own internal
    // "> 0.99" test would return the minimum as well. Level 14 lands on 0.900, so 0.92 separates them
    // cleanly and blockLightEmission = 15 drives the function through its intended path. With the IPBR
    // tree gone this is what keeps distant lamps, glowstone and lit windows alive at night; it also
    // feeds the mat 21000-21024 automatic emission branch inside terrainMaterials.glsl.
    if (lmCoord.x > 0.92) {
        blockLightEmission = 15;
        emission = DoAutomaticEmission(noSmoothLighting, noDirectionalShading, color.rgb, lmCoord.x, blockLightEmission, 0.0);
        isLightSource = true;
        overlayNoiseIntensity = 0.0;
    }

    // Praying to god these don't cause massive issues
    vec2 atlasSize = vec2(999999999.0);
    vec2 midCoord = vec2(999999999.0);
    vec2 signMidCoordPos = vec2(999999999.0);
    vec2 absMidCoordPos = vec2(999999999.0);
    vec2 texCoord = vec2(999999999.0);

    // IPBR is undefined at the top of this file, so this resolves to the pack's compact
    // non-IPBR cascade instead of terrainIPBR.glsl.
    // Must stay OUTSIDE the GBUFFERS_TERRAIN pair below: without IPBR this header unconditionally
    // pulls in lavaEdge.glsl (terrainMaterials.glsl:75), whose body wants voxel_sampler - a sampler
    // voxy.json does not declare - and the only thing keeping that body out is lavaEdge.glsl:1
    // testing for GBUFFERS_TERRAIN.
    #include "/lib/materials/materialHandling/terrainMaterials.glsl"

    // Materials the IPBR tree treated differently enough that dropping it shows up as a step across
    // the LOD boundary rather than as missing detail. Everything here is copied from the branch it
    // replaces, so LOD and near terrain stay on the same curve.
    if (mat == 10412 || mat == 10396) { // Glowstone, Jack o'Lantern - terrainIPBR.glsl:1577-1581
        noSmoothLighting = true; noDirectionalShading = true;
        lmCoordM = vec2(0.9, 0.0);
        emission = max0(color.g - 0.3) * 4.6;
        color.rg += emission * vec2(0.15, 0.05);
    } else if (mat == 10648 || mat == 10649) { // Shroomlight - terrainIPBR.glsl:2561-2566
        noSmoothLighting = true; noDirectionalShading = true;
        lmCoordM = vec2(1.0, 0.0);
        emission = min(pow2(pow2(pow2(dot(color.rgb, color.rgb) * 0.6))), 6.0) * 0.8 + 0.5;
    } else if (mat == 10448 || mat == 10640 || mat == 10652 || mat == 10656
            || (mat >= 10560 && mat <= 10564) || (mat >= 10680 && mat <= 10691)) {
        // Sea lantern, lit redstone lamp, campfires, lanterns, froglights. The pack hand tunes each
        // of these; the automatic path with a full light level is the generic version of the same
        // thing and keeps them lit, which is the part that matters at LOD range.
        emission = DoAutomaticEmission(noSmoothLighting, noDirectionalShading, color.rgb, 1.0, 15, 0.5);
    } else if (mat == 10068 || mat == 10070) { // Lava - lava.glsl:1-4
        // The compact cascade (terrainMaterials.glsl:53-77) gives lava a flat emission of 2.0 and
        // leaves its lightmap alone, so it comes out brighter in albedo and dimmer in glow than the
        // lava a few chunks closer. Nether and the boss dimensions mapped onto world-1 are mostly
        // lava, so this one is not a subpixel difference.
        color.rgb *= 0.84;
        noDirectionalShading = true;
        lmCoordM = vec2(0.0);
        // The cascade already multiplied its flat 2.0 by LAVA_EMISSION at terrainMaterials.glsl:77,
        // so this overwrite carries the multiplier itself rather than compounding it.
        emission = (GetLuminance(color.rgb) * 7.48 + 0.5) * LAVA_EMISSION;
    } else if (mat == 10007 || mat == 10009 || mat == 10011) { // Leaves - leaves.glsl:12-21
        // The only material whose specular is strong enough to read as a brightness step: the IPBR
        // branch drives highlightMult to 2.0-6.0 where the default is 1.0, so distant canopy goes
        // noticeably flat under a low sun without this.
        float leafFactor = min1(pow2(color.g - 0.15 * (color.r + color.b)) * 2.5);
        smoothnessG = leafFactor * 0.4;
        highlightMult = (leafFactor * 4.0 + 2.0)
                      * (1.0 - pow2(pow2(clamp(1.0 + dot(normalM, nViewPos), 0.0, 1.0))));
    } else if (mat >= 10132 && mat <= 10135 && glColor.b < 0.98) { // Grass top - terrainIPBR.glsl:598
        smoothnessG = pow2(color.g);
    }

    #ifdef SNOWY_WORLD
        DoSnowyWorld(color, smoothnessG, highlightMult, smoothnessD, emission,
                     playerPos, lmCoord, snowFactor, snowMinNdotU, NdotU, subsurfaceMode);
    #endif

    #define GBUFFERS_TERRAIN
    #if SEASONS > 0
        #include "/lib/materials/seasons.glsl"
    #endif
    #if defined MOSS_NOISE_INTERNAL || defined SAND_NOISE_INTERNAL
        #include "/lib/materials/overlayNoiseApply.glsl"
    #endif
    #undef GBUFFERS_TERRAIN

    #if MONOTONE_WORLD > 0
        #if MONOTONE_WORLD == 1
            color.rgb = vec3(1.0);
        #elif MONOTONE_WORLD == 2
            color.rgb = vec3(0.0);
        #else
            color.rgb = vec3(0.5);
        #endif
    #endif

    #ifdef SS_BLOCKLIGHT
        blocklightCol = ApplyMultiColoredBlocklight(blocklightCol, screenPos, playerPos, lmCoord.x);
    #endif

    emission *= EMISSION_MULTIPLIER;

    DoLighting(color, shadowMult, playerPos, viewPos, lViewPos, geoNormal, normalM, dither,
               worldGeoNormal, lmCoordM, noSmoothLighting, noDirectionalShading, noVanillaAO,
               centerShadowBias, subsurfaceMode, smoothnessG, highlightMult, emission, purkinjeOverwrite, isLightSource,
               enderDragonDead);

    #ifdef SS_BLOCKLIGHT
        vec3 lightAlbedo = normalize(color.rgb) * min1(emission);

        #ifdef COLORED_CANDLE_LIGHT
            if (mat >= 10900 && mat <= 10922) { // Candles:Lit
                lightAlbedo = normalize(color.rgb) * lmCoord.x;
            }
        #endif
    #endif

    float skyLightFactor = GetSkyLightFactor(lmCoordM, shadowMult);

    #ifdef IRIS_FEATURE_FADE_VARIABLE
        skyLightFactor *= 0.5;
    #endif

    float entitySSBLMask = 1.0;
    #ifdef ENTITIES_ARE_LIGHT
        entitySSBLMask = 0.0;
    #endif

    // Writing to: 0,6,9 (defined in voxy.json)
    gbufferData0 = color;
    gbufferData6 = vec4(smoothnessD, materialMask, skyLightFactor, lmCoord.x + clamp01(purkinjeOverwrite) + clamp01(emission));
    #ifdef SS_BLOCKLIGHT
        gbufferData9 = vec4(lightAlbedo, entitySSBLMask);
    #endif
}

#endif
