package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class VoxyConfig {
    public enum LeafLodMode {
        FAST,
        BALANCED,
        QUALITY
    }

    public static final int MAX_CLOUD_DISTANCE = 128;
    public static final float MIN_SUBDIVISION_SIZE = 28.0f;
    public static final float MAX_SUBDIVISION_SIZE = 256.0f;

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static final VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    // Aero/sable: master switch for extending simulated-contraption rendering out to LOD distances.
    public boolean sableLodRendering = true;
    // Create: render distant trains (server-sampled poses + client-baked carriage meshes).
    public boolean distantTrains = true;
    // Create: render the track network beyond render distance as simplified rail geometry.
    public boolean distantTracks = true;
    // Create: hold a frozen client-side snapshot of contraptions (bearings/pistons/gantries/mounted)
    // the player walked past, drawn statically beyond the render distance.
    public boolean distantContraptions = true;
    //Beacon beams past vanilla's own beam range. Not a create integration - it reads the voxel store
    //directly - but it shares the distant-render hook.
    public boolean distantBeacons = true;
    //How far out beams are drawn, in CHUNKS. 0 follows voxy's LOD radius. A beam is a landmark rather
    //than scenery, which is why it reaches further than the machinery caps - but not as far as the
    //terrain radius, where a beam is a subpixel line still costing a solve over the voxel store on
    //every rebuild.
    public int distantBeaconMaxChunks = 192;
    // Create: cull placed kinetic machine moving parts (rotating shafts/gears/machine animations)
    // beyond the render distance so they stop floating over the LOD. Off = Create draws them natively.
    public boolean distantKinetics = true;
    // Create distant-integration render caps, in CHUNKS. 0 = follow voxy's LOD radius. A lower value
    // renders that integration nearer, cutting GPU load; for trains it also shrinks the server
    // pose-stream window (less bandwidth) on the integrated server. Clamped to the LOD radius - there is
    // no LOD terrain to sit against beyond it.
    //
    // These are bounded rather than following the LOD radius because that radius is a terrain distance:
    // at the default it reaches 8192 blocks, where a machine covers a pixel or two but still costs a
    // resident mesh, a draw call and the tick spent keeping it. Terrain at that range is a horizon;
    // machinery is not. Raise them if you want distant bases legible from further out.
    public int distantTrainMaxChunks = 96;
    public int distantTrackMaxChunks = 96;
    public int distantContraptionMaxChunks = 64;
    // Also bounds kinetics' eviction distance, so the resident set is a spatial working set rather than
    // everywhere within the LOD radius the player has flown past.
    public int distantKineticMaxChunks = 48;
    // Vertex memory contraption snapshots may hold at once. Over it, the furthest meshes are freed while
    // their block lists stay, so they rebuild on approach instead of being lost - a contraption's source
    // is a few kilobytes against a few hundred for its mesh. Distance alone is a poor bound because one
    // dense structure can cost as much as a hundred small ones.
    public int distantContraptionGpuBudgetMiB = 48;
    // Same for kinetics, except a kinetic snapshot is dropped whole rather than reduced to its source.
    // Its source measures larger than the mesh it produces - almost all of it a recorded vertex stream
    // that cannot be rebuilt from the block state - so holding the source to rebuild from costs more
    // than the mesh it releases. The sweep captures it again when the player returns.
    public int distantKineticGpuBudgetMiB = 32;
    // Vertex-memory ceiling for baked distant train shapes. A shapeId embeds the train UUID, so
    // every disassembly orphans its meshes; the sweep closes orphans and the budget bounds the rest.
    // The server re-sends a shape on window re-entry, so eviction costs one resend. 0 disables.
    public int distantTrainGpuBudgetMiB = 32;
    // Residency cap for LOD geometry, in MiB of the geometry buffer; 0 = uncapped. On large-VRAM
    // cards the emergency cleaner never fires, so the resident set grows for the whole session and
    // per-frame costs that scale with it ratchet up until the renderer is recreated. The cap
    // evicts least-recently-seen meshes past the limit; they re-request when looked at again.
    public int geometryResidencyCapMB = 1536;
    // Companion cap in resident SECTIONS; 0 = uncapped (the default, and the only safe default).
    // A cap below the view's working set thrashes: the cleaner evicts meshes the traversal needs
    // this frame, they are re-requested and rebuilt immediately, and the LOD visibly flickers as
    // detail drops and returns. Only set this above the resident count a settled session reaches
    // (F3 "residentSections"), as a ceiling against unbounded growth - never as a budget.
    public int geometryResidencySections = 0;
    // Selects the pack's voxy_*_lite.glsl programs (if it ships them) instead of the standard
    // ones - a pack-authored cheaper LOD lighting path for A/B testing. Off = exactly the
    // current behaviour; packs without lite files are unaffected either way. Takes effect on
    // shader reload (R) or renderer recreation.
    public boolean lodLiteShading = false;
    // EXPERIMENTAL: when the camera is still and no geometry changed, reuse the previous frame's
    // LOD command lists instead of re-running hi-z + traversal + command generation + the temporal
    // pass. Any geometry consumption, camera movement or the frame cap below forces a full build -
    // failure direction is always the current per-frame path. Off = exactly current behaviour.
    public boolean experimentalCmdListHold = false;
    // Longest run of consecutive held frames before a build is forced regardless (bounds request
    // latency, statistics staleness and hi-z age).
    public int cmdListHoldMaxFrames = 4;
    // Section voxel-array reuse pool budget, in MiB (256KiB per array; 100 = the long-standing 400
    // array cap). The pool absorbs materialise/release churn from the mesh workers - when the F3
    // counters show sustained misses AND overflows together, the churn amplitude exceeds the cap
    // and every miss is a fresh 256KiB allocation handed to the GC. Raise toward 256 on large-heap
    // clients to trade heap for GC pressure.
    public int sectionArrayPoolMiB = 100;
    // EXPERIMENTAL: emit opaque LOD draw commands near-to-far instead of the traversal's natural
    // far-to-near list order, letting early-z reject the far fill hidden behind near terrain. Pure
    // draw-order heuristic - the depth test owns correctness either way. Matters because the LOD
    // opaque pass is fill-bound (geometry density barely moves its cost); takes effect on renderer
    // recreation (rejoin world or toggle voxy rendering off/on).
    public boolean experimentalOpaqueNearFirst = false;
    // EXPERIMENTAL: when the camera is still and the sodium-visible section set is unchanged
    // (content-hashed - sodium 0.8 re-streams an identical list every frame), keep the previous
    // frame's hole-punch mask (the depth bounding buffer) instead of re-rasterising every visible
    // section's AABB. Any camera motion, set change, render-distance change or buffer clear/resize
    // re-rasterises. Under a shader pack that declares TAA, reuse disables itself: the seam is
    // only stable when the mask re-rasterises every frame with that frame's jitter (a kept mask
    // freezes its phase and flickers; an unjittered mask shimmers against the jittered terrain -
    // both field-verified). The duplicate-upload squash stays active under TAA. Off = exactly
    // current behaviour.
    public boolean experimentalChunkMaskReuse = false;
    // Vanilla-style biome colour blending for LOD water: each border voxel's tint is the box
    // average over a (2*radius+1)^2 window, radius measured in LOD voxels so every ring shows the
    // same apparent transition width. Same 0..7 range as vanilla's biomeBlendRadius; 0 = off (hard
    // edges, the pre-blend behaviour). Applies on mesh rebuild - changing it needs a renderer
    // reload (F3+A / rejoin) to repaint already-built sections.
    public int biomeBlendRadius = 2;
    // "water" = fluids only; "water_grass" = every biome-tinted block (grass and foliage too)
    public String biomeBlendScope = "water";
    // Aero/sable: render simulated contraptions within this % of voxy's LOD render distance.
    public int simulatedContraptionRenderDistancePercent = 50;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 28;
    public int skyFogDistance = 96;

    //Circular, dithered vanilla->LOD handover, replacing the chunk-square edge. Off by default here:
    //it makes opaque LOD rasterize across the whole vanilla-covered region instead of being masked out,
    //and shader packs that ship their own LOD fade (Photon and friends) double up with it.
    public boolean enableLodBoundaryFade = false;
    public int lodBoundaryFadeLength = 16;
    public int lodBoundaryInset = 8;
    public int lodBoundaryBuffer = 1;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    // Scales voxy's self-defined LOD fog distance (100 = fog reaches full at voxy's render edge).
    public int fogDistancePercent = 100;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public int renderPressure = 2;
    public int earthCurveRatio = 0;
    public String ssaoMode;
    public boolean useEnvironmentalFog = true;
    public String leafLodMode = "balanced";
    public boolean enableFarPlayerRendering = true;
    public boolean renderFarPlayerNames = true;
    public int farPlayerAnimationDistance = 1024;
    public boolean shareFarPlayerPosition = true;

    public int getFarEntityRenderDistanceBlocks() {
        return Math.clamp(Math.round(this.sectionRenderDistance * 32.0f * 16.0f), 64, 32768);
    }

    public int getRenderPressureLevel() {
        if (this.renderPressure < 0 || this.renderPressure > 4) {
            this.renderPressure = 2;
        }
        return this.renderPressure;
    }

    public LeafLodMode getLeafLodMode() {
        if (this.leafLodMode == null) {
            return LeafLodMode.BALANCED;
        }

        try {
            return LeafLodMode.valueOf(this.leafLodMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LeafLodMode.BALANCED;
        }
    }

    public void setLeafLodMode(LeafLodMode mode) {
        this.leafLodMode = mode.name().toLowerCase(Locale.ROOT);
    }

    // EclipticSeasons compat: master switch for the mesh-time seasonal view - snow cover, frozen
    // water and seasonal block models are decided per remesh against the current solar term, and
    // stored voxels stay season neutral.
    public boolean eclipticSeasonsSnowLod = true;
    // EclipticSeasons compat: re-judge snow over the STORED section data when the season changes,
    // writing complement ids back into the store. The mesh-time view needs no store rewrite; this
    // exists only to normalise archives that already carry baked complement ids. Walks the whole
    // section store on a background thread.
    public boolean eclipticSeasonsLodAutoReload = false;
    // EclipticSeasons compat: rebuild the LOD renderer when the season changes. The rebuild
    // re-meshes everything, and each remesh re-judges the season - so with the mesh-time view this
    // is the channel that makes a season change actually appear at LOD range (snow, models AND the
    // per-biome tint rows, which only re-capture on a renderer rebuild). Off would leave distant
    // colours frozen at the join-time season for the whole session.
    public boolean eclipticSeasonsReloadOnSeasonChange = true;

    // Print the build, its maintainer and the fork's repository to chat on world join.
    public boolean showJoinMessage = true;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) {
            return SSAO.SSAOMode.AUTO;
        }

        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SSAO.SSAOMode.AUTO;
        }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (!VoxyCommon.isAvailable()) {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }

        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                VoxyConfig config = GSON.fromJson(reader, VoxyConfig.class);
                if (config != null) {
                    config.sanitize();
                    config.save();
                    return config;
                }
                Logger.error("Failed to load Voxy config; resetting it");
            } catch (IOException | RuntimeException e) {
                Logger.error("Could not load Voxy config; resetting it", e);
                backupInvalidConfig(path);
            }
        }

        Logger.info("Config does not exist; creating a new one");
        var config = new VoxyConfig();
        config.save();
        return config;
    }

    public void sanitize() {
        //The one guard no path can bypass: a zero here reaches the traversal as far²=0 and ALL
        //LOD terrain disappears with no error, surviving restarts. Fractional section counts are
        //legitimate (4.5 sections = 144 chunks) and must survive the clamp.
        this.sectionRenderDistance = Math.clamp(this.sectionRenderDistance, 2.0f, 64.0f);
        this.subDivisionSize = Math.clamp(this.subDivisionSize, MIN_SUBDIVISION_SIZE, MAX_SUBDIVISION_SIZE);
        //The fog percentage is measured against the LOD radius. It was once measured against a
        //sixteenth of it, so anyone who raised the slider to see past that is carrying a value that now
        //means sixteen times too far - and no fog at all. Scale those down rather than clamping them to
        //the new maximum, which would be a different setting than they chose.
        if (this.fogDistancePercent > 200) {
            this.fogDistancePercent = Math.max(5, Math.round(this.fogDistancePercent / 16.0f));
        }
        this.fogDistancePercent = Math.clamp(this.fogDistancePercent, 5, 200);
        this.skyFogDistance = Math.clamp(this.skyFogDistance, 0, 1024);
        this.lodBoundaryFadeLength = Math.clamp(this.lodBoundaryFadeLength, 8, 64);
        this.lodBoundaryInset = Math.clamp(this.lodBoundaryInset, 8, 32);
        this.lodBoundaryBuffer = Math.clamp(this.lodBoundaryBuffer, 0, 4);
        this.cloudDistance = Math.clamp(this.cloudDistance, 0, MAX_CLOUD_DISTANCE);
        this.fogIntensity = Math.clamp(this.fogIntensity, 0.0f, 1.0f);
        this.fogDensity = Math.clamp(this.fogDensity, 0.0f, 1.0f);
        this.setLeafLodMode(this.getLeafLodMode());
        this.farPlayerAnimationDistance = Math.clamp(this.farPlayerAnimationDistance, 0, 32768);
        this.biomeBlendRadius = Math.clamp(this.biomeBlendRadius, 0, 7);
        this.geometryResidencyCapMB = Math.max(this.geometryResidencyCapMB, 0);
        this.geometryResidencySections = Math.max(this.geometryResidencySections, 0);
        this.cmdListHoldMaxFrames = Math.clamp(this.cmdListHoldMaxFrames, 2, 60);
        this.sectionArrayPoolMiB = Math.clamp(this.sectionArrayPoolMiB, 25, 1024);
        if (!"water".equals(this.biomeBlendScope) && !"water_grass".equals(this.biomeBlendScope)) {
            this.biomeBlendScope = "water";
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            this.syncSableContraptionRenderDistance();
            this.syncDistantTrainConfig();
            return;
        }

        this.sanitize();
        Path path = getConfigPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(this));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Logger.error("Failed to write Voxy config", e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }

        this.syncSableContraptionRenderDistance();
        this.syncDistantTrainConfig();
    }

    private static void backupInvalidConfig(Path path) {
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".invalid");
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to back up invalid Voxy config", e);
        }
    }

    // Aero/sable: push the live render-distance/percent to the sable contraption-LOD calculator.
    // SableContraptionRenderDistance has no sable-type references, so this is safe even without sable.
    public void syncSableContraptionRenderDistance() {
        SableContraptionRenderDistance.updateClientConfig(
                this.isRenderingEnabled() && this.sableLodRendering,
                this.sectionRenderDistance,
                this.simulatedContraptionRenderDistancePercent
        );
    }

    // Create: push the distant-train enable flag + render distance to the server-side sampler bridge,
    // so the pose-stream window matches what the client actually draws (integrated server bandwidth).
    public void syncDistantTrainConfig() {
        DistantTrainConfig.updateClientConfig(
                this.isRenderingEnabled() && this.distantTrains,
                this.createRenderDistance(this.distantTrainMaxChunks)
        );
    }

    // Effective distant-render radius in BLOCKS for a Create integration given its chunk cap. 0 (or
    // negative) follows voxy's LOD radius; a positive cap is clamped to it, since there is no LOD
    // terrain to occlude against past the LOD radius.
    //
    // sectionRenderDistance counts 32-chunk sections, so the radius is 32*16*srd blocks - the same
    // expression getFarEntityRenderDistanceBlocks and HierarchicalOcclusionTraverser use. Dropping the
    // 16 gives the radius in chunks, which every Create distant renderer would then read as blocks and
    // stop drawing at a sixteenth of the LOD range while the terrain under it kept going.
    public double createLodRadius() {
        return 32.0 * 16.0 * this.sectionRenderDistance;
    }

    public double createRenderDistance(int maxChunks) {
        double lod = this.createLodRadius();
        return maxChunks > 0 ? Math.min(maxChunks * 16.0, lod) : lod;
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
