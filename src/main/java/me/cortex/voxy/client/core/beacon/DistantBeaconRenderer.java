package me.cortex.voxy.client.core.beacon;

import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.compat.create.DistantVisibility;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

//Beacon beams out where vanilla's block entity renderer has long since stopped. Only the solid inner
//core: vanilla draws that with no transparency and a depth write, so it belongs in the opaque pass and
//needs no blending stage of its own. The outer glow is translucent and is not drawn here.
public final class DistantBeaconRenderer implements LodPipelineHooks.Renderer {
    //Vanilla's BeaconRenderer stops here, and it measures horizontally - getViewDistance is squared
    //against dx/dz only, so a beacon directly overhead is still drawn
    private static final double VANILLA_BEAM_RANGE = 256.0;
    private static final ResourceLocation BEAM_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/entity/beacon_beam.png");

    private static final float CORE_RADIUS = 0.2f;

    public static int lastFrameBeamsDrawn;
    //Re-solves per frame. A solve is a bounded column walk, but the first sight of a beacon-heavy
    //world queues them all at once - the budget turns that into a short ramp instead of one frame.
    private static final int SOLVES_PER_FRAME = 4;
    private static final long LOOKUP_RETRY_MS = 5000;

    //Beacon pos -> its current verdict. A beam is re-solved only when its column's voxels changed,
    //its index entry changed, or it crossed the LOD range - never on a timer. EMPTY is a cached
    //verdict too: a beacon under a roof is the ordinary case, and re-proving it dark on a timer is
    //the bulk of the work a change-driven solve avoids.
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BeaconState> states =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final class BeaconState {
        DistantMesh mesh;
        double topY;
        //Mapper did not know an id mid-solve; provisional, retried after a backoff
        long retryAtMs;
    }

    private final List<Built> built = new ArrayList<>();
    private boolean builtStale;
    private long lastFilterMs = -1;
    private me.cortex.voxy.common.world.WorldEngine boundEngine;
    private final it.unimi.dsi.fastutil.longs.LongArrayList drainDirty = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private final it.unimi.dsi.fastutil.longs.LongArrayList drainRemoved = new it.unimi.dsi.fastutil.longs.LongArrayList();

    //topY is kept so the draw can frustum-test the beam: it is a tall thin column, and testing only its
    //base rejects it whenever the base is below the view while the visible part is not.
    private record Built(DistantMesh mesh, double x, double y, double z, double topY) {}

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.distantBeacons || !cfg.isRenderingEnabled()) {
            this.discard();
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            this.discard();
            return;
        }
        var engine = WorldIdentifier.ofEngineNullable(mc.level);
        if (engine == null) {
            this.discard();
            return;
        }

        if (this.boundEngine != engine) {
            //Engine changed under us (dimension switch, world reload): every cached verdict was solved
            //against the old store, and the tracker's column map with it
            this.discard();
            BeaconBeamTracker.reset(this.boundEngine);
            BeaconBeamTracker.bind(engine);
            this.boundEngine = engine;
            this.lastFilterMs = -1;
        }

        this.processChanges(engine, viewport, mc);
        this.filterIfStale(viewport);
        if (this.builtStale) {
            this.builtStale = false;
            this.built.clear();
            for (var entry : this.states.long2ObjectEntrySet()) {
                var state = entry.getValue();
                if (state.mesh != null) {
                    long pos = entry.getLongKey();
                    this.built.add(new Built(state.mesh,
                            BlockPos.getX(pos) + 0.5, BlockPos.getY(pos), BlockPos.getZ(pos) + 0.5, state.topY));
                }
            }
            lastBuiltCount = this.built.size();
        }
        if (this.built.isEmpty()) {
            lastFrameBeamsDrawn = 0;
            return;
        }

        pipeline.setupAndBindOpaque(viewport);
        LodPipelineHooks.renderStateGuarded(() -> this.draw(pipeline, viewport, depthFunc));
    }

    private void draw(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var shader = DistantShaders.forPipeline(pipeline, false);
        if (shader == null) {
            return;
        }
        shader.bind();
        DistantShaders.bindTextures();
        //Over the atlas that bindTextures just put on unit 0: the beam has its own texture and the
        //shader only ever samples one 2D image, so swapping the binding is the whole difference
        glBindTextureUnit(0, Minecraft.getInstance().getTextureManager().getTexture(BEAM_TEXTURE).getId());

        //Tag these pixels the way the other distant renderers do, then hand the stencil back: the guard
        //restores neither stencil state nor the tag the LOD passes expect
        glStencilFunc(GL_ALWAYS, 3, 0xFF);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        try {
            var transform = new Matrix4f();
            int drawn = 0;
            //Handover decided per frame rather than per rebuild. Deciding it while building meant a beam
            //that vanilla had just stopped drawing did not exist on our side until the next rebuild, so
            //crossing the boundary outward left a gap for up to the rebuild interval. Everything in LOD
            //range is built; this is the only thing that decides who draws it.
            double vanillaRange = Math.min(VANILLA_BEAM_RANGE,
                    Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0);
            double vanillaRangeSq = vanillaRange * vanillaRange;
            lastVanillaRange = (int) vanillaRange;
            int vanillaOwned = 0;
            for (var beam : this.built) {
                double bdx = beam.x - viewport.cameraX, bdz = beam.z - viewport.cameraZ;
                if (bdx * bdx + bdz * bdz < vanillaRangeSq) {
                    vanillaOwned++;
                    continue;
                }
                //A beam is a 1024-block column, so its box is tall and thin
                if (!DistantVisibility.isBoxVisible(viewport,
                        beam.x - CORE_RADIUS, beam.y, beam.z - CORE_RADIUS,
                        beam.x + CORE_RADIUS, beam.topY, beam.z + CORE_RADIUS)) {
                    continue;
                }
                transform.set(viewport.MVP).translate(
                        (float) (beam.x - viewport.cameraX),
                        (float) (beam.y - viewport.cameraY),
                        (float) (beam.z - viewport.cameraZ));
                DistantShaders.uploadTransform(transform);
                beam.mesh.draw();
                drawn++;
            }
            lastFrameBeamsDrawn = drawn;
            lastVanillaOwned = vanillaOwned;
        } finally {
            glStencilFunc(GL_EQUAL, 1, 0x1);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    //Solving walks the voxel store and baking uploads buffers, so neither may happen per frame or on a
    //timer: a beam is a function of its column's voxels and its index entry, and the tracker watches
    //both. What arrives here is only what actually changed, a budgeted few per frame.
    private void processChanges(me.cortex.voxy.common.world.WorldEngine engine, Viewport<?> viewport, Minecraft mc) {
        this.drainDirty.clear();
        this.drainRemoved.clear();
        BeaconBeamTracker.drain(this.drainDirty, this.drainRemoved);

        for (int i = 0; i < this.drainRemoved.size(); i++) {
            BeaconState state = this.states.remove(this.drainRemoved.getLong(i));
            if (state != null) {
                if (state.mesh != null) {
                    state.mesh.free();
                }
                this.builtStale = true;
            }
        }
        if (this.drainDirty.isEmpty()) {
            return;
        }

        double maxDist = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantBeaconMaxChunks);
        double maxDistSq = maxDist * maxDist;
        long now = System.currentTimeMillis();
        int solved = 0;
        try {
            for (int i = 0; i < this.drainDirty.size(); i++) {
                long pos = this.drainDirty.getLong(i);
                if (solved >= SOLVES_PER_FRAME) {
                    //Budget spent: what is left stays queued and drains over the coming frames
                    BeaconBeamTracker.queueDirty(pos);
                    continue;
                }
                int bx = BlockPos.getX(pos), by = BlockPos.getY(pos), bz = BlockPos.getZ(pos);
                double dx = (bx + 0.5) - viewport.cameraX;
                double dz = (bz + 0.5) - viewport.cameraZ;
                if (dx * dx + dz * dz > maxDistSq) {
                    //Out of range: drop whatever was held; the range filter re-queues it on approach
                    BeaconState state = this.states.remove(pos);
                    if (state != null && state.mesh != null) {
                        state.mesh.free();
                        this.builtStale = true;
                    }
                    continue;
                }
                solved++;
                this.solveOne(engine, mc, pos, bx, by, bz, now);
            }
        } catch (Throwable t) {
            Logger.error("Building distant beacon beams", t);
        }
    }

    private void solveOne(me.cortex.voxy.common.world.WorldEngine engine, Minecraft mc,
                          long pos, int bx, int by, int bz, long now) {
        var result = BeaconBeamSolver.solve(engine, bx, by, bz, mc.level == null ? by : mc.level.getMaxBuildHeight());
        BeaconState state = this.states.get(pos);
        if (state == null) {
            state = new BeaconState();
            this.states.put(pos, state);
        }
        if (state.mesh != null) {
            state.mesh.free();
            state.mesh = null;
            this.builtStale = true;
        }
        if (result.lookupFailed()) {
            //Solved against a mapper that had not registered an id yet - a wrong verdict cached now
            //would stick until the next voxel change, so hold it provisional and retry
            state.retryAtMs = now + LOOKUP_RETRY_MS;
            return;
        }
        state.retryAtMs = 0;
        if (result.segments().isEmpty()) {
            return;
        }
        var mesh = bake(result.segments(), by);
        if (mesh != null) {
            double topY = by;
            for (var seg : result.segments()) {
                topY = Math.max(topY, seg.yTop());
            }
            state.mesh = mesh;
            state.topY = topY;
            this.builtStale = true;
        }
    }

    //The only thing left on a timer, because distance is the one input with no event: membership of
    //the LOD range as the camera moves. One distance check per index entry, no acquires, no GL.
    private void filterIfStale(Viewport<?> viewport) {
        long now = System.currentTimeMillis();
        if (this.lastFilterMs != -1 && now - this.lastFilterMs < 2000) {
            return;
        }
        this.lastFilterMs = now;

        double maxDist = VoxyConfig.CONFIG.createRenderDistance(VoxyConfig.CONFIG.distantBeaconMaxChunks);
        double maxDistSq = maxDist * maxDist;
        int[] outOfRange = new int[1];
        var engine = this.boundEngine;
        if (engine == null) {
            return;
        }
        engine.getBeaconIndex().forEach((bx, by, bz) -> {
            long pos = BlockPos.asLong(bx, by, bz);
            double dx = (bx + 0.5) - viewport.cameraX;
            double dz = (bz + 0.5) - viewport.cameraZ;
            boolean inRange = dx * dx + dz * dz <= maxDistSq;
            BeaconState state = this.states.get(pos);
            if (!inRange) {
                outOfRange[0]++;
                if (state != null) {
                    this.states.remove(pos);
                    if (state.mesh != null) {
                        state.mesh.free();
                        this.builtStale = true;
                    }
                }
                return;
            }
            if (state == null) {
                //Came into range without a voxel change - first sight, or returning after eviction
                BeaconBeamTracker.queueDirty(pos);
            } else if (state.retryAtMs != 0 && now >= state.retryAtMs) {
                state.retryAtMs = 0;
                BeaconBeamTracker.queueDirty(pos);
            }
        });
        lastOutOfRange = outOfRange[0];
    }

    //Four sides of a square column per segment, textured along Y the way vanilla's beam is
    private static DistantMesh bake(List<BeaconBeamSolver.Segment> segments, int beaconY) {
        var builder = new DistantMeshBuilder();
        for (var segment : segments) {
            float y0 = segment.yBottom() - beaconY;
            float y1 = segment.yTop() - beaconY;
            float r = CORE_RADIUS;
            //V follows world height so the texture scrolls with length rather than stretching
            float v0 = segment.yBottom();
            float v1 = segment.yTop();
            int rgb = segment.colorRgb();
            //Full sky light: the beam is its own light source and must not be shaded by where it sits
            side(builder, -r, y0, -r, r, y1, -r, v0, v1, rgb, 2);
            side(builder, r, y0, r, -r, y1, r, v0, v1, rgb, 3);
            side(builder, r, y0, -r, r, y1, r, v0, v1, rgb, 4);
            side(builder, -r, y0, r, -r, y1, -r, v0, v1, rgb, 5);
        }
        return builder.isEmpty() ? null : builder.build();
    }

    private static void side(DistantMeshBuilder builder, float x0, float y0, float z0,
                             float x1, float y1, float z1, float v0, float v1, int rgb, int face) {
        builder.rawVertex(x0, y0, z0, 0.0f, v0, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x1, y0, z1, 1.0f, v0, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x1, y1, z1, 1.0f, v1, 15, 15, 1.0f, face, rgb);
        builder.rawVertex(x0, y1, z0, 0.0f, v1, 15, 15, 1.0f, face, rgb);
    }

    private void discard() {
        for (var state : this.states.values()) {
            if (state.mesh != null) {
                state.mesh.free();
            }
        }
        this.states.clear();
        this.built.clear();
        this.builtStale = false;
    }

    @net.neoforged.bus.api.SubscribeEvent
    public void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        this.discard();
        BeaconBeamTracker.reset(this.boundEngine);
        this.boundEngine = null;
        this.lastFilterMs = -1;
    }

    //Why a beacon is not drawn splits four ways and only one of them is a bug, so each rejection is
    //counted separately rather than leaving a zero to be guessed at
    public static volatile int lastVanillaOwned;
    public static volatile int lastOutOfRange;
    public static volatile int lastNoSegments;

    public static String debugDump() {
        return "distant beacons: enabled=" + VoxyConfig.CONFIG.distantBeacons
                + " built=" + lastBuiltCount
                + " drawnLastFrame=" + lastFrameBeamsDrawn
                + " handoverAt=" + lastVanillaRange
                + " (skipped: vanillaOwns=" + lastVanillaOwned
                + " outOfLodRange=" + lastOutOfRange
                + " emptyBeam=" + lastNoSegments + ")";
    }

    public static volatile int lastBuiltCount;
    public static volatile int lastVanillaRange;
}
