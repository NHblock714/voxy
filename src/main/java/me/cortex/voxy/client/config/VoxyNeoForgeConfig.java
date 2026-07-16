package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge config integration for Voxy.
 * Provides a built-in config screen accessible from the Mods menu.
 *
 * This wraps the existing VoxyConfig and syncs values between the two systems.
 */
@EventBusSubscriber(modid = "voxy", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class VoxyNeoForgeConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // General settings
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable Voxy LOD rendering system")
            .define("enabled", true);

    private static final ModConfigSpec.BooleanValue ENABLE_RENDERING = BUILDER
            .comment("Enable LOD terrain rendering (can be disabled while keeping data ingestion)")
            .define("enableRendering", true);

    private static final ModConfigSpec.BooleanValue INGEST_ENABLED = BUILDER
            .comment("Enable automatic chunk data ingestion for LOD generation")
            .define("ingestEnabled", true);

    // Performance settings
    private static final ModConfigSpec.IntValue SECTION_RENDER_DISTANCE = BUILDER
            .comment("LOD section render distance (multiplied by 32 for actual chunk distance)",
                     "Example: 16 = 512 chunks render distance")
            .defineInRange("sectionRenderDistance", 16, 2, 64);

    private static final ModConfigSpec.IntValue SERVICE_THREADS = BUILDER
            .comment("Number of background threads for LOD processing",
                     "Default is based on CPU core count.")
            .defineInRange("serviceThreads", Math.max((int)(CpuLayout.getCoreCount() / 1.5), 1), 1, CpuLayout.getCoreCount());

    private static final ModConfigSpec.DoubleValue SUB_DIVISION_SIZE = BUILDER
            .comment("Subdivision size for LOD rendering (28-256)",
                     "Lower = more detailed LODs but more GPU load")
            .defineInRange("subDivisionSize", 28.0, 28.0, 256.0);

    // Visual settings
    private static final ModConfigSpec.BooleanValue USE_ENVIRONMENTAL_FOG = BUILDER
            .comment("Apply environmental fog to LOD terrain")
            .define("useEnvironmentalFog", true);

    // Advanced settings
    private static final ModConfigSpec.BooleanValue DONT_USE_SODIUM_BUILDER_THREADS = BUILDER
            .comment("Don't share threads with Sodium's chunk builder")
            .define("dontUseSodiumBuilderThreads", false);

    // LOD boundary buffer (overdraw/overlap)
    private static final ModConfigSpec.IntValue LOD_BOUNDARY_BUFFER = BUILDER
            .comment("LOD boundary overlap in blocks (like DH's overdraw prevention)",
                     "Controls how much LODs overlap with vanilla chunk edges.",
                     "Higher values = more overlap = smoother transitions but slight overdraw.",
                     "0 = exact match (may have gaps), 1 = minimal overlap, 2-4 = smoother for fast flight")
            .defineInRange("lodBoundaryBuffer", 1, 0, 4);

    // World curvature (experimental)
    private static final ModConfigSpec.IntValue EARTH_CURVE_RATIO = BUILDER
            .comment("World curvature effect - simulates standing on a spherical planet",
                     "0 = disabled (flat world)",
                     "1 = real Earth curvature (6371km radius)",
                     "Higher values = more extreme curvature (smaller planet effect)",
                     "Valid range: 0 (off), or 50-5000. Values 1-49 are auto-corrected to 50.",
                     "Inspired by Distant Horizons' earth curvature feature")
            .defineInRange("earthCurveRatio", 0, 0, 5000);

    // Debug settings
    private static final ModConfigSpec.BooleanValue RENDER_STATISTICS = BUILDER
            .comment("Show render statistics in F3 debug screen",
                     "Displays LOD traversal counts, visible sections, and quad counts")
            .define("renderStatistics", false);

    // Create mod integration: render distant trains/tracks/contraptions and cull placed kinetic parts.
    // The *MaxChunks caps are in chunks; 0 = follow voxy's LOD radius (2 * sectionRenderDistance chunks).
    // Lowering a cap renders that integration nearer to save GPU; the train cap also shrinks the
    // server's pose-stream window on the integrated server (less bandwidth).
    private static final ModConfigSpec.BooleanValue DISTANT_TRAINS = BUILDER
            .comment("Render Create trains beyond the vanilla view distance, in the LOD")
            .define("distantTrains", true);

    private static final ModConfigSpec.IntValue DISTANT_TRAIN_MAX_CHUNKS = BUILDER
            .comment("Max distance to render distant trains, in chunks. 0 = follow the LOD radius.",
                     "Lower renders trains nearer; on the integrated server it also shrinks the",
                     "server's train pose-stream window, cutting bandwidth.")
            .defineInRange("distantTrainMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_TRACKS = BUILDER
            .comment("Render the Create track network beyond the view distance, in the LOD")
            .define("distantTracks", true);

    private static final ModConfigSpec.IntValue DISTANT_TRACK_MAX_CHUNKS = BUILDER
            .comment("Max distance to render the distant track network, in chunks. 0 = follow the LOD radius.")
            .defineInRange("distantTrackMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_CONTRAPTIONS = BUILDER
            .comment("Render snapshots of Create contraptions (bearings/pistons/gantries/mounted)",
                     "beyond the view distance, in the LOD")
            .define("distantContraptions", true);

    private static final ModConfigSpec.IntValue DISTANT_CONTRAPTION_MAX_CHUNKS = BUILDER
            .comment("Max distance to render distant contraptions, in chunks. 0 = follow the LOD radius.")
            .defineInRange("distantContraptionMaxChunks", 0, 0, 192);

    private static final ModConfigSpec.BooleanValue DISTANT_KINETICS = BUILDER
            .comment("Cull placed kinetic machine moving parts (rotating shafts/gears) beyond the render",
                     "distance so they stop floating over the LOD. Off = Create draws them natively.")
            .define("distantKinetics", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * Register the config with NeoForge.
     * Call this during mod construction.
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC, "voxy-client.toml");
    }

    /**
     * Sync NeoForge config values to VoxyConfig.
     *
     * This is only used when the NeoForge config screen / TOML file is reloaded.
     * The Sodium video options save to voxy-config.json, so startup loading must not
     * blindly copy the default TOML values back into VoxyConfig or the in-game video
     * settings will be reset every time Minecraft starts.
     */
    private static void syncToVoxyConfig() {
        VoxyConfig.CONFIG.enabled = ENABLED.get();
        VoxyConfig.CONFIG.enableRendering = ENABLE_RENDERING.get();
        VoxyConfig.CONFIG.ingestEnabled = INGEST_ENABLED.get();
        VoxyConfig.CONFIG.sectionRenderDistance = SECTION_RENDER_DISTANCE.get();
        VoxyConfig.CONFIG.serviceThreads = SERVICE_THREADS.get();
        VoxyConfig.CONFIG.subDivisionSize = SUB_DIVISION_SIZE.get().floatValue();
        VoxyConfig.CONFIG.useEnvironmentalFog = USE_ENVIRONMENTAL_FOG.get();
        VoxyConfig.CONFIG.dontUseSodiumBuilderThreads = DONT_USE_SODIUM_BUILDER_THREADS.get();
        VoxyConfig.CONFIG.lodBoundaryBuffer = LOD_BOUNDARY_BUFFER.get();
        VoxyConfig.CONFIG.earthCurveRatio = EARTH_CURVE_RATIO.get();
        // Create integration
        VoxyConfig.CONFIG.distantTrains = DISTANT_TRAINS.get();
        VoxyConfig.CONFIG.distantTrainMaxChunks = DISTANT_TRAIN_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantTracks = DISTANT_TRACKS.get();
        VoxyConfig.CONFIG.distantTrackMaxChunks = DISTANT_TRACK_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantContraptions = DISTANT_CONTRAPTIONS.get();
        VoxyConfig.CONFIG.distantContraptionMaxChunks = DISTANT_CONTRAPTION_MAX_CHUNKS.get();
        VoxyConfig.CONFIG.distantKinetics = DISTANT_KINETICS.get();
        // RenderStatistics is a runtime-only setting (not saved to JSON)
        RenderStatistics.enabled = RENDER_STATISTICS.get();

        // Also save to the JSON config for compatibility (this also pushes the distant-train render
        // distance to the server-side sampler bridge via VoxyConfig.syncDistantTrainConfig).
        VoxyConfig.CONFIG.save();
    }

    /**
     * Sync the already-loaded JSON config into NeoForge's in-memory config values.
     *
     * Voxy's Sodium options use voxy-config.json as the authoritative config file.
     * Without this, NeoForge's voxy-client.toml defaults can overwrite the JSON values
     * during ModConfigEvent.Loading, which makes render distance and other options
     * appear to reset after restarting the game.
     */
    private static void syncFromVoxyConfig() {
        ENABLED.set(VoxyConfig.CONFIG.enabled);
        ENABLE_RENDERING.set(VoxyConfig.CONFIG.enableRendering);
        INGEST_ENABLED.set(VoxyConfig.CONFIG.ingestEnabled);
        SECTION_RENDER_DISTANCE.set((int) VoxyConfig.CONFIG.sectionRenderDistance);
        SERVICE_THREADS.set(VoxyConfig.CONFIG.serviceThreads);
        SUB_DIVISION_SIZE.set((double) VoxyConfig.CONFIG.subDivisionSize);
        USE_ENVIRONMENTAL_FOG.set(VoxyConfig.CONFIG.useEnvironmentalFog);
        DONT_USE_SODIUM_BUILDER_THREADS.set(VoxyConfig.CONFIG.dontUseSodiumBuilderThreads);
        LOD_BOUNDARY_BUFFER.set(VoxyConfig.CONFIG.lodBoundaryBuffer);
        EARTH_CURVE_RATIO.set(VoxyConfig.CONFIG.earthCurveRatio);
        // Create integration
        DISTANT_TRAINS.set(VoxyConfig.CONFIG.distantTrains);
        DISTANT_TRAIN_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantTrainMaxChunks);
        DISTANT_TRACKS.set(VoxyConfig.CONFIG.distantTracks);
        DISTANT_TRACK_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantTrackMaxChunks);
        DISTANT_CONTRAPTIONS.set(VoxyConfig.CONFIG.distantContraptions);
        DISTANT_CONTRAPTION_MAX_CHUNKS.set(VoxyConfig.CONFIG.distantContraptionMaxChunks);
        DISTANT_KINETICS.set(VoxyConfig.CONFIG.distantKinetics);
        // RenderStatistics remains NeoForge/TOML-only.
        RenderStatistics.enabled = RENDER_STATISTICS.get();
    }

    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncFromVoxyConfig();
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncToVoxyConfig();
        }
    }

    // Getters for direct access (optional, can use VoxyConfig.CONFIG instead)
    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static boolean isRenderingEnabled() {
        return ENABLE_RENDERING.get();
    }

    public static boolean isIngestEnabled() {
        return INGEST_ENABLED.get();
    }

    public static int getSectionRenderDistance() {
        return SECTION_RENDER_DISTANCE.get();
    }

    public static int getServiceThreads() {
        return SERVICE_THREADS.get();
    }

    public static float getSubDivisionSize() {
        return SUB_DIVISION_SIZE.get().floatValue();
    }

    public static boolean useEnvironmentalFog() {
        return USE_ENVIRONMENTAL_FOG.get();
    }

    public static boolean dontUseSodiumBuilderThreads() {
        return DONT_USE_SODIUM_BUILDER_THREADS.get();
    }

    public static int getLodBoundaryBuffer() {
        return LOD_BOUNDARY_BUFFER.get();
    }

    public static boolean isRenderStatisticsEnabled() {
        return RENDER_STATISTICS.get();
    }

    public static int getEarthCurveRatio() {
        return EARTH_CURVE_RATIO.get();
    }
}
