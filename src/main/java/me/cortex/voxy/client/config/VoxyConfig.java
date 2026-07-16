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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

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
    // Create: cull placed kinetic machine moving parts (rotating shafts/gears/machine animations)
    // beyond the render distance so they stop floating over the LOD. Off = Create draws them natively.
    public boolean distantKinetics = true;
    // Create: hide kinetic moving parts that are provably invisible (all open faces covered by opaque
    // blocks; encased blocks only need their two axis ends covered). Pure render savings, active even
    // with voxy rendering off; complements the raycast culler, which cannot catch this case.
    public boolean kineticEnclosedCulling = true;
    // Create distant-integration render caps, in CHUNKS. 0 = follow voxy's LOD radius
    // (2 * sectionRenderDistance chunks). A lower value renders that integration nearer, cutting GPU
    // load; for trains it also shrinks the server pose-stream window (less bandwidth) on the integrated
    // server. Clamped to the LOD radius - there is no LOD terrain to sit against beyond it.
    public int distantTrainMaxChunks = 0;
    public int distantTrackMaxChunks = 0;
    public int distantContraptionMaxChunks = 0;
    // Aero/sable: render simulated contraptions within this % of voxy's LOD render distance.
    public int simulatedContraptionRenderDistancePercent = 50;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 28;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    // Scales voxy's self-defined LOD fog distance (100 = fog reaches full at voxy's render edge).
    public int fogDistancePercent = 100;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;

    // LOD boundary buffer: controls the safety margin between vanilla chunks and LOD rendering.
    public int lodBoundaryBuffer = 1;

    // World curvature effect; 0 disables it.
    public int earthCurveRatio = 0;

    public String ssaoMode;

    public boolean useEnvironmentalFog = true;

    // EclipticSeasons compat: recolor LOD terrain with seasonal snow (master switch for the eclipticseasons mixins).
    public boolean eclipticSeasonsSnowLod = true;
    // EclipticSeasons compat: re-import region LODs when the season changes.
    public boolean eclipticSeasonsLodAutoReload = false;
    // EclipticSeasons compat: rebuild the LOD renderer when the season changes.
    public boolean eclipticSeasonsReloadOnSeasonChange = false;

    // Print the build, its maintainer and the fork's repository to chat on world join. Set it here -
    // it is not carried in the config screens.
    public boolean showJoinMessage = true;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            Logger.info("Config doesnt exist, creating new");
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            this.syncSableContraptionRenderDistance();
            this.syncDistantTrainConfig();
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }

        this.syncSableContraptionRenderDistance();
        this.syncDistantTrainConfig();
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

    // Effective distant-render radius in blocks for a Create integration given its chunk cap. 0 (or
    // negative) follows voxy's LOD radius (32 * sectionRenderDistance); a positive cap is clamped to
    // it, since there is no LOD terrain to occlude against past the LOD radius.
    public double createLodRadius() {
        return 32.0 * this.sectionRenderDistance;
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
