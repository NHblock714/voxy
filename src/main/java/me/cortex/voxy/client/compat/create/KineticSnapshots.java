package me.cortex.voxy.client.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

//Frozen-snapshot store for the moving parts of placed kinetic machine blocks (rotating shafts, cogs,
//gearboxes...). The static shell of a machine is voxelised into the LOD like any block, but its moving
//part is a Flywheel instance the LOD never sees - the distance cull hides it beyond the render distance
//and the machine turns into a bare shell out there. This records each machine the moment it leaves the
//live path (crossing the render-distance sphere, its chunk unloading, or the sweep finding it) and
//draws a frozen copy through the distant pipeline, occluded by LOD depth like the track meshes.
//
//A snapshot is just blockstate + frozen angle + light: rebake pulls the state's baked json model - the
//very model Create's backend-off BER spins - and emits it under the spin transform, exactly how the
//track renderer bakes bezier segments. Blocks whose json is empty (pure-partial machines) yield no
//snapshot and stay hidden-only. Batched into one DistantMesh per 16^3 section, so a large base costs
//one draw per section rather than one per shaft. Queues are concurrent because the cull transitions
//fire from Flywheel's (potentially parallel) frame plan; all map/GL work happens on the client tick.
public final class KineticSnapshots {
    private KineticSnapshots() {}

    private static final int CAPTURES_PER_TICK = 64;
    //Shared by the queue drain and the sweep: a capture is a full BER pass plus light sampling, and an
    //unbounded batch of them (first sweep contact with a dense factory) is a frame hitch. The queue
    //drains first; the sweep spends the remainder.
    private static int captureBudget;
    private static final int REBAKES_PER_TICK = 4;
    //Sections dirtied by a removal, serviced before the general dirty scan: a removal is usually a
    //reveal (the live path taking the position back), and until the rebake lands the frozen copy
    //draws under the live spinning machine - the general scan can be a backlog of sweep recaptures
    //behind. Overflow stays queued ahead of the scan for the next tick.
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet PRIORITY_REBAKE =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    //One frozen machine part: what block, at what angle, in what light. Geometry is not stored -
    //rebake pulls the baked models and transforms them, the same way the track renderer bakes bezier
    //segments. (The json model is the rotating model Create's backend-off BER spins; capturing the
    //BER's vertex stream instead died on catnip's SuperByteBuffer being empty outside the render
    //pass.) bakeJson covers the chunk-hidden rotating json; bearingFacing != null additionally bakes
    //the bearing's partials (shaft half + top disc at its own frozen angle).
    //chains: one row per chain-conveyor connection - [startOffX,startOffY,startOffZ (from the block
    //centre), yawDeg, pitchDeg, chainLength, sky2, block2]; non-null marks the block as a chain
    //conveyor (shaft + wheel + per-connection guard + chain strap get baked).
    record Snap(net.minecraft.world.level.block.state.BlockState state,
                net.minecraft.core.Direction.Axis axis, float angleRad, int sky, int block,
                boolean bakeJson, net.minecraft.core.Direction shaftHalfFacing,
                net.minecraft.core.Direction bearingFacing, float bearingTopAngleRad, boolean woodenTop,
                float[][] chains, float[] bnbChain, float[] generic, boolean gantryCarriage) {

        //A snap with no geometry sources is a negative-capture record: this state at this position
        //yields nothing to bake. Only the SKIP path stores these - a real capture always carries at
        //least one source - so emptiness IS the marker, no extra field. rebake emits nothing for one
        //(every emit branch is gated on the fields that are null here), so it costs a map entry and
        //nothing downstream.
        boolean hollow() {
            return !this.bakeJson && this.shaftHalfFacing == null && this.bearingFacing == null
                    && this.chains == null && this.bnbChain == null && this.generic == null
                    && !this.gantryCarriage;
        }
    }

    private static final boolean BNB_LOADED = net.neoforged.fml.ModList.get().isLoaded("bits_n_bobs");

    //The thread currently running a capture (null = none). The visualization gate answers false while
    //a capture runs so the kinetic BERs take their full backend-off pass into our consumer (see
    //MixinVisualizationManagerImpl). This must stay thread-scoped - Flywheel may query the gate from
    //its worker threads, which must not see the render thread's capture - but a volatile-thread compare
    //is cheaper on that very hot query path than a ThreadLocalMap lookup.
    private static volatile Thread captureThread = null;

    public static boolean isCapturingOnThisThread() {
        return captureThread == Thread.currentThread();
    }

    static final class Bucket {
        final Map<BlockPos, Snap> geoms = new HashMap<>();
        DistantMesh mesh;
        boolean dirty;

        void close() {
            if (this.mesh != null) {
                this.mesh.free();
                this.mesh = null;
            }
        }
    }

    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Bucket> SECTIONS =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static final Queue<BlockPos> CAPTURE_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Queue<BlockPos> REMOVE_QUEUE = new ConcurrentLinkedQueue<>();
    private static ResourceLocation dim;
    //Snapshotted bearing positions, for cleanup when the sweep finds their block gone. The top disc
    //freezes at the cull transition, and DistantContraptionManager freezes the driven contraption at
    //the same reach boundary - the two halves stop in the same instant and stay aligned. Tick-thread.
    private static final java.util.Set<BlockPos> BEARING_POSITIONS = new java.util.HashSet<>();

    //Diagnostics for /voxy debug trains + /voxy debug kinetics
    public static volatile int sectionCount;
    public static volatile int snapshotCount;
    private static final java.util.ArrayDeque<String> RECENT_CAPTURES = new java.util.ArrayDeque<>();

    private static void logCapture(String line) {
        synchronized (RECENT_CAPTURES) {
            if (RECENT_CAPTURES.size() >= 10) {
                RECENT_CAPTURES.removeFirst();
            }
            RECENT_CAPTURES.addLast(line);
        }
    }

    private static int hollowIn(Bucket bucket) {
        int n = 0;
        for (Snap s : bucket.geoms.values()) {
            if (s.hollow()) n++;
        }
        return n;
    }

    //Full state dump for /voxy debug kinetics: queues, sweep, every bucket near the camera, and the
    //last capture attempts with their renderer class and vertex counts - enough to tell whether the
    //pipeline captured nothing, captured garbage, or captured fine and the draw side dropped it.
    public static String debugDump(double camX, double camY, double camZ) {
        var sb = new StringBuilder();
        sb.append("sections=").append(SECTIONS.size())
                .append(" snapshots=").append(snapshotCount)
                .append(" capQ=").append(CAPTURE_QUEUE.size())
                .append(" remQ=").append(REMOVE_QUEUE.size())
                .append(" sweepCursor=").append(sweepCursor)
                .append(" dim=").append(dim);
        sb.append("\nrecent captures:");
        synchronized (RECENT_CAPTURES) {
            if (RECENT_CAPTURES.isEmpty()) {
                sb.append(" <none>");
            }
            for (String line : RECENT_CAPTURES) {
                sb.append("\n  ").append(line);
            }
        }
        sb.append("\nbuckets within 96 blocks:");
        int shown = 0;
        for (var entry : SECTIONS.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            double ox = (BlockPos.getX(key) << 4) + 8, oy = (BlockPos.getY(key) << 4) + 8, oz = (BlockPos.getZ(key) << 4) + 8;
            double dx = ox - camX, dy = oy - camY, dz = oz - camZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > 96 && shown > 0) {
                continue;
            }
            var bucket = entry.getValue();
            sb.append("\n  section(").append(BlockPos.getX(key) << 4).append(',')
                    .append(BlockPos.getY(key) << 4).append(',').append(BlockPos.getZ(key) << 4)
                    .append(") dist=").append((int) dist)
                    .append(" entries=").append(bucket.geoms.size() - hollowIn(bucket))
                    .append(hollowIn(bucket) > 0 ? "+" + hollowIn(bucket) + "skip" : "")
                    .append(" mesh=").append(bucket.mesh != null ? "yes" : "NULL")
                    .append(" dirty=").append(bucket.dirty);
            if (++shown >= 12) {
                sb.append("\n  ... (").append(SECTIONS.size() - shown).append(" more)");
                break;
            }
        }
        return sb.toString();
    }

    //Called from the cull mixins' beginFrame (any thread): position crossing out of the live path.
    //Ship-borne positions are excluded - ships render natively (one connected drivetrain), and a
    //frozen snapshot at plot-grid coordinates would just be dead weight past the draw radius.
    public static void queueCapture(BlockPos pos) {
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return;
        }
        CAPTURE_QUEUE.add(pos.immutable());
    }

    //Called on return to the live path or when a fresh visual spawns for the position
    public static void queueRemove(BlockPos pos) {
        REMOVE_QUEUE.add(pos.immutable());
    }

    //Drivetrain tick-alignment (bearing disc vs the structure it spins): each half freezes when IT
    //crosses the boundary, so the two hold different ticks' angles and read as permanently meshed
    //wrong. Whichever side freezes triggers the other to re-freeze on the same tick: the contraption
    //manager calls recaptureAt on ITS freeze tick, and a self-initiated bearing capture posts the
    //anchor here for the manager to consume and refresh its frozen pose.
    private static final java.util.Set<BlockPos> ANCHOR_RECAPTURED = new java.util.HashSet<>();
    private static boolean inManagerRecapture;

    public static void recaptureAt(BlockPos pos) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return;
        }
        if (!(mc.level.getBlockEntity(pos) instanceof KineticBlockEntity kbe)) {
            return;
        }
        //Manager-driven: don't post the anchor back, or next tick's consume would re-refresh the
        //structure one tick after this capture and reintroduce the offset
        inManagerRecapture = true;
        try {
            capture(mc.level, kbe);
        } finally {
            inManagerRecapture = false;
        }
    }

    public static boolean consumeAnchorRecapture(BlockPos pos) {
        return ANCHOR_RECAPTURED.remove(pos);
    }


    //Distance alone does not bound what is held: a dense factory inside the render radius can hold more
    //than a sparse world's entire radius does. Furthest bucket first, and the whole bucket goes - unlike
    //a contraption, a kinetic snapshot's source is larger than the mesh it builds (measured at 1.17x,
    //nearly all of it recorded vertex data that cannot be rebuilt from the block state), so keeping the
    //source to rebuild from would cost more than the mesh it freed. The sweep captures it again when the
    //player comes back.
    private static void enforceGpuBudget(double camX, double camY, double camZ) {
        long budget = (long) VoxyConfig.CONFIG.distantKineticGpuBudgetMiB * 1024L * 1024L;
        if (budget <= 0) {
            return;
        }
        long resident = 0;
        for (var bucket : SECTIONS.values()) {
            if (bucket.mesh != null) {
                resident += bucket.mesh.gpuByteSize();
            }
        }
        long target = (budget * 9L) / 10L;
        while (resident > budget) {
            long furthestKey = 0;
            double furthestDistSq = -1;
            boolean found = false;
            for (var entry : SECTIONS.long2ObjectEntrySet()) {
                if (entry.getValue().mesh == null) {
                    continue;
                }
                long key = entry.getLongKey();
                double dx = ((BlockPos.getX(key) << 4) + 8) - camX;
                double dy = ((BlockPos.getY(key) << 4) + 8) - camY;
                double dz = ((BlockPos.getZ(key) << 4) + 8) - camZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > furthestDistSq) {
                    furthestDistSq = distSq;
                    furthestKey = key;
                    found = true;
                }
            }
            if (!found) {
                break;
            }
            var bucket = SECTIONS.remove(furthestKey);
            if (bucket == null) {
                break;
            }
            resident -= bucket.mesh == null ? 0 : bucket.mesh.gpuByteSize();
            for (BlockPos snapPos : bucket.geoms.keySet()) {
                BEARING_POSITIONS.remove(snapPos);
            }
            bucket.close();
            me.cortex.voxy.commonImpl.PerfStats.kineticSnapshotEvicted.increment();
            if (resident <= target) {
                break;
            }
        }
    }

    public static it.unimi.dsi.fastutil.longs.Long2ObjectMap<Bucket> sections() {
        return SECTIONS;
    }

    public static void clearAll() {
        for (Bucket bucket : SECTIONS.values()) {
            bucket.close();
        }
        SECTIONS.clear();
        CAPTURE_QUEUE.clear();
        REMOVE_QUEUE.clear();
        BEARING_POSITIONS.clear();
        ANCHOR_RECAPTURED.clear();
        PRIORITY_REBAKE.clear();
        KineticCull.cachedReachSq = -1;
        sectionCount = 0;
        snapshotCount = 0;
    }

    //Client tick (render thread): drain the transition queues, capture, and rebake dirty sections
    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            if (!SECTIONS.isEmpty()) {
                    clearAll();
            }
                return;
            }
            var levelDim = level.dimension().location();
            if (!levelDim.equals(dim)) {
                clearAll();
                dim = levelDim;
            }

            BlockPos pos;
            while ((pos = REMOVE_QUEUE.poll()) != null) {
                Bucket bucket = SECTIONS.get(sectionKey(pos));
                Snap removed = bucket == null ? null : bucket.geoms.remove(pos);
                if (removed != null && !removed.hollow()) {
                    bucket.dirty = true;
                    PRIORITY_REBAKE.add(sectionKey(pos));
            }
                BEARING_POSITIONS.remove(pos);
            }


            var cam = mc.gameRenderer.getMainCamera().getPosition();
            double reach = mc.options.getEffectiveRenderDistance() * 16.0;
            double reachSq = reach * reach;
            KineticCull.cachedReachSq = reachSq;
            captureBudget = CAPTURES_PER_TICK;
            //The queue leaves the sweep a floor of the budget: a busy factory refills the queue every
            //tick, and a sweep that never gets a slot parks its cursor on the same chunk forever (the
            //mid-slice rewind) - ghost reclaim and recapture both stall behind it.
            while (captureBudget > 16 && (pos = CAPTURE_QUEUE.poll()) != null) {
                //Raced back inside the live path between the queue and this tick: the visual draws it
                if (pos.distToCenterSqr(cam.x, cam.y, cam.z) < reachSq) {
                    continue;
            }
                if (level.getBlockEntity(pos) instanceof KineticBlockEntity be) {
                    capture(level, be);
                    captureBudget--;
            }
            }

            sweep(mc, level, cam.x, cam.y, cam.z, reachSq);

            int rebaked = 0;
            var prio = PRIORITY_REBAKE.iterator();
            while (prio.hasNext() && rebaked < REBAKES_PER_TICK) {
                Bucket bucket = SECTIONS.get(prio.nextLong());
                prio.remove();
                if (bucket != null && bucket.dirty) {
                    bucket.dirty = false;
                    rebake(bucket);
                    rebaked++;
                }
            }
            for (Bucket bucket : SECTIONS.values()) {
                if (!bucket.dirty || rebaked >= REBAKES_PER_TICK) {
                    continue;
            }
                bucket.dirty = false;
                rebake(bucket);
                rebaked++;
            }
            //Distance-bound the snapshot store. Leave-behinds in unloaded chunks are kept frozen on
            //purpose, but the sweep only visits LOADED chunks, so without this a long session across a
            //machine-heavy world accumulates a snapshot (VAO+VBO+heap verts) for every kinetic BE ever
            //passed, and the per-frame draw loop iterates all of them. The renderer only draws snapshots
            //within the kinetic render distance; anything past it is pure waste. Evict buckets beyond that
            //(plus 2 chunks of hysteresis) so the resident set is a spatial working set, not cumulative.
            double maxDist = cfg.createRenderDistance(cfg.distantKineticMaxChunks) + 32.0;
            double maxDistSq = maxDist * maxDist;
            SECTIONS.long2ObjectEntrySet().removeIf(entry -> {
                long key = entry.getLongKey();
                double scx = (BlockPos.getX(key) << 4) + 8;
                double scy = (BlockPos.getY(key) << 4) + 8;
                double scz = (BlockPos.getZ(key) << 4) + 8;
                double dx = scx - cam.x, dy = scy - cam.y, dz = scz - cam.z;
                if (dx * dx + dy * dy + dz * dz > maxDistSq) {
                    Bucket bucket = entry.getValue();
                    for (BlockPos snapPos : bucket.geoms.keySet()) {
                        BEARING_POSITIONS.remove(snapPos);
                    }
                    bucket.close();
                    me.cortex.voxy.commonImpl.PerfStats.kineticSnapshotEvicted.increment();
                    return true;
            }
                return false;
            });
            enforceGpuBudget(cam.x, cam.y, cam.z);

            //Prune sections emptied by removals
            SECTIONS.values().removeIf(bucket -> {
                if (bucket.geoms.isEmpty()) {
                    bucket.close();
                    return true;
            }
                return false;
            });
            sectionCount = SECTIONS.size();
        }

        //Rotating cursor over the loaded-chunk disk. The cull-transition capture only fires from a live
        //visual's beginFrame - but the raycast culler (nowheel) DELETES occluded visuals outright, so a
        //machine it culled near the view-distance boundary never crosses our transition and got no
        //snapshot ("nothing in the transition band, snapshots only appear once the chunk unloads"). This
        //sweep walks a slice of the loaded disk each tick and captures any kinetic BE that is beyond the
        //reach and not yet snapshotted, visual or no visual. Full disk coverage in ~1s at 48 chunks/tick.
        private static int sweepCursor;
        private static final int SWEEP_CHUNKS_PER_TICK = 48;

        private static void sweep(Minecraft mc, ClientLevel level, double camX, double camY, double camZ, double reachSq) {
            int radius = mc.options.getEffectiveRenderDistance() + 2;
            int diameter = radius * 2 + 1;
            int total = diameter * diameter;
            int centerX = ((int) Math.floor(camX)) >> 4;
            int centerZ = ((int) Math.floor(camZ)) >> 4;
            //Hysteresis band: capture starts past the reach, reclaim only 16 blocks inside it, so a
            //camera hovering on the boundary doesn't churn capture/remove/rebake every sweep pass
            double innerReach = Math.max(0, Math.sqrt(reachSq) - 16.0);
            double innerSq = innerReach * innerReach;
            for (int i = 0; i < SWEEP_CHUNKS_PER_TICK; i++) {
                int idx = Math.floorMod(sweepCursor++, total);
                int cx = centerX + (idx % diameter) - radius;
                int cz = centerZ + (idx / diameter) - radius;
            var chunk = level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk)) {
                continue;
            }
            //A loaded chunk is the authority on its own blocks: any snapshot here whose kinetic BE is
            //gone (machine broken or disassembled into something else) is a ghost - drop it. Unloaded
            //chunks are never touched, so leave-behinds stay frozen.
            //
            //Skipped while nothing is snapshotted at all: this is 24 lookups per chunk (the full build
            //height) times 48 chunks a tick, and in a world with no frozen machines every one of them
            //misses. That is the common case - snapshots only exist where the player has flown past
            //running machinery and left it behind.
            if (!SECTIONS.isEmpty()) {
                for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                    long bucketKey = BlockPos.asLong(cx, sy, cz);
                    Bucket bucket = SECTIONS.get(bucketKey);
                    if (bucket == null) {
                        continue;
                    }
                    boolean wasDirty = bucket.dirty;
                    bucket.geoms.keySet().removeIf(snapPos -> {
                        if ((snapPos.getX() >> 4) != cx || (snapPos.getZ() >> 4) != cz) {
                            return false;
                        }
                        if (level.getBlockEntity(snapPos) instanceof KineticBlockEntity) {
                            return false;
                        }
                        bucket.dirty = true;
                        BEARING_POSITIONS.remove(snapPos);
                        return true;
                    });
                    //A ghost dropped here floats over loaded terrain until the rebake lands
                    if (bucket.dirty && !wasDirty) {
                        PRIORITY_REBAKE.add(bucketKey);
                    }
                }
            }
            //Ship membership is decided by chunk, so it is the same answer for every machine in this
            //chunk - resolving it per block entity re-walks sable's plot lookup for each one.
            boolean chunkIsShipBorne = me.cortex.voxy.client.compat.ShipBorne.isShipBorne(
                    (double) (cx << 4), (double) (cz << 4));
            for (var be : levelChunk.getBlockEntities().values()) {
                if (!(be instanceof KineticBlockEntity kbe)) {
                    continue;
                }
                BlockPos bePos = kbe.getBlockPos();
                double distSq = bePos.distToCenterSqr(camX, camY, camZ);
                if (distSq <= reachSq || chunkIsShipBorne) {
                    //Machines whose animation lives only in a BER have no visual, so nothing queues a
                    //remove when the player comes back inside - the frozen copy overlaps the live
                    //spinning render wherever the boundary section still draws (the turntable ghost).
                    //The sweep is the reclaim authority for them: live BE well inside the live domain,
                    //drop its snapshot. (Visual-owning machines already remove on return; this is a
                    //no-op for them.)
                    if (distSq <= innerSq) {
                        Bucket bucket = SECTIONS.get(sectionKey(bePos));
                        Snap removed = bucket == null ? null : bucket.geoms.remove(bePos);
                        if (removed != null) {
                            if (!removed.hollow()) {
                                bucket.dirty = true;
                                PRIORITY_REBAKE.add(sectionKey(bePos));
                            }
                            BEARING_POSITIONS.remove(bePos);
                        }
                    }
                    continue;
                }
                if (hasCurrentSnap(bePos, kbe.getBlockState())) {
                    continue;
                }
                if (captureBudget <= 0) {
                    //Budget spent mid-slice: re-run this chunk next tick instead of waiting out a full
                    //pass. Already-captured positions dedup on re-entry, so the redo is cheap.
                    sweepCursor--;
                    return;
                }
                capture(level, kbe);
                captureBudget--;
            }
        }
    }

    //Does the distant pipeline actually draw geometry for this position - a hollow record or an
    //unbaked bucket draws nothing, and standing the live render down against one of those leaves a
    //hole instead of a hand-over.
    static boolean drawsSnapAt(BlockPos pos) {
        Bucket bucket = SECTIONS.get(sectionKey(pos));
        if (bucket == null || bucket.mesh == null) {
            return false;
        }
        Snap snap = bucket.geoms.get(pos);
        return snap != null && !snap.hollow();
    }

    //Present-and-current test for the dedup sites: a snap (hollow or real) for the same interned
    //BlockState needs no recapture; a state change at the position invalidates either kind.
    private static boolean hasCurrentSnap(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        Bucket bucket = SECTIONS.get(sectionKey(pos));
        if (bucket == null) {
            return false;
        }
        Snap prior = bucket.geoms.get(pos);
        return prior != null && prior.state() == state;
    }

    //Horizontal path: the chunk is unloading and its BEs are about to vanish - capture immediately.
    //Runs on the main thread from the unload event, while the block entities are still reachable.
    public static void captureUnloadingChunk(ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            return;
        }
        if (level != Minecraft.getInstance().level) {
            return; //leaving the dimension entirely - clearAll handles it
        }
        for (var be : chunk.getBlockEntities().values()) {
            if (!(be instanceof KineticBlockEntity kbe)) {
                continue;
            }
            //The sweep has usually been here already - an unloading chunk lives at the disk edge. Only
            //the never-captured residue pays a capture, so the burst is a handful of machines, not the
            //chunk. Cannot defer past the event: the block entities are gone after it.
            if (hasCurrentSnap(kbe.getBlockPos(), kbe.getBlockState())) {
                continue;
            }
            capture(level, kbe);
        }
    }

    private static void capture(ClientLevel level, KineticBlockEntity be) {
        BlockPos pos = be.getBlockPos();
        try {
            var state = be.getBlockState();
            var model = Minecraft.getInstance().getModelManager().getBlockModelShaper().getBlockModel(state);

            //Machines whose json is a static shell keep their partials here: bearings get the shaft
            //half (toward the back) plus the top disc at the bearing's own frozen angle; creative
            //motors get the shaft half toward their FACING (their getRotatedModel).
            net.minecraft.core.Direction shaftHalfFacing = null;
            net.minecraft.core.Direction bearingFacing = null;
            float bearingTopAngle = 0.0f;
            boolean woodenTop = false;
            if (be instanceof com.simibubi.create.content.contraptions.bearing.IBearingBlockEntity bearing
                    && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                bearingFacing = state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING);
                shaftHalfFacing = bearingFacing.getOpposite();
                //partialTicks = 1, matching the contraption snapshot's applyLocalTransforms(1.0f) so
                //the frozen disc and the frozen structure land on the same interpolated angle
                bearingTopAngle = (float) Math.toRadians(bearing.getInterpolatedAngle(1.0f));
                woodenTop = bearing.isWoodenTop();
            }

            //Chain conveyors: shaft + wheel partials plus every chain connection (start offset, yaw,
            //pitch, length, far-end light) so the strap geometry survives at LOD range
            float[][] chains = null;
            if (be instanceof com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity conveyor) {
                var list = new java.util.ArrayList<float[]>();
                var center = net.minecraft.world.phys.Vec3.atCenterOf(pos);
                for (BlockPos rel : conveyor.connections) {
                    var stats = conveyor.connectionStats.get(rel);
                    if (stats == null) {
                        continue;
                    }
                    var diff = stats.end().subtract(stats.start());
                    double yaw = Math.toDegrees(net.minecraft.util.Mth.atan2(diff.x, diff.z));
                    double pitch = Math.toDegrees(net.minecraft.util.Mth.atan2(diff.y,
                            diff.multiply(1.0, 0.0, 1.0).length()));
                    var startOff = stats.start().subtract(center);
                    int farLight = DistantLightSampler.samplePeek(level,
                            pos.getX() + rel.getX(), pos.getY() + rel.getY() + 1, pos.getZ() + rel.getZ());
                    list.add(new float[]{(float) startOff.x, (float) startOff.y, (float) startOff.z,
                            (float) yaw, (float) pitch, stats.chainLength(),
                            DistantLightSampler.sky(farLight), DistantLightSampler.block(farLight)});
                }
                chains = list.toArray(new float[0][]);
            }

            //bits_n_bobs cogwheel chains: the controlling wheel carries the whole loop's strap geometry
            float[] bnbChain = null;
            if (BNB_LOADED) {
                try {
                    bnbChain = BnbChainSnapshots.capture(level, be);
                } catch (Throwable ignored) {
                    //the addon's internals moved - the wheel itself still snapshots via its json
                }
            }

            //Sample above the block, not inside it: the machine itself is a solid voxel and reads 0
            //(pitch-black moving parts whenever the voxel store already has the chunk - the same trap
            //the track renderer hit). Fall back to the block's own cell if the space above reads dark.
            int light = DistantLightSampler.samplePeek(level, pos.getX(), pos.getY() + 1, pos.getZ());
            if (DistantLightSampler.sky(light) == 0 && DistantLightSampler.block(light) == 0) {
                light = DistantLightSampler.samplePeek(level, pos.getX(), pos.getY(), pos.getZ());
            }
            int skyLight = DistantLightSampler.sky(light), blockLight = DistantLightSampler.block(light);

            //Generic full-pass capture: run the machine's actual BER with the visualization gate
            //bypassed - the complete backend-off pass (rotating model AND machine-specific moving
            //parts: press heads, fan blades, addon gears) streams into the consumer at freeze pose.
            //Succeeds -> replaces the json/partial special cases below; fails -> they take over.
            //Chain straps stay in their own fields either way (they draw on non-atlas render types
            //the consumer discards).
            float[] generic = null;
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer instanceof KineticBlockEntityRenderer<?>) {
                int sx = pos.getX() & ~15, sy = pos.getY() & ~15, sz = pos.getZ() & ~15;
                var consumer = new Capture(pos.getX() - sx, pos.getY() - sy, pos.getZ() - sz, skyLight, blockLight);
                captureThread = Thread.currentThread();
                try {
                    @SuppressWarnings("unchecked")
                    var raw = (net.minecraft.client.renderer.blockentity.BlockEntityRenderer<KineticBlockEntity>) renderer;
                    raw.render(be, 1.0f, new com.mojang.blaze3d.vertex.PoseStack(), consumer, light,
                            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
                    consumer.flush();
                    generic = consumer.toArray();
                    if (generic.length < 9 * 4) {
                        generic = null;
                    }
                } catch (Throwable e) {
                    generic = null;
                } finally {
                    captureThread = null;
                }
            }
            if (generic != null) {
                shaftHalfFacing = null;
                bearingFacing = null;
            }

            //Only jsons the chunk cannot see are rotating parts (Create hides them from the chunk
            //layers and spins them in the BER): those get baked into the snapshot. A json the chunk
            //renders (a bearing's static base) is already in the voxel LOD - baking it again would
            //double it, spun to a nonsense angle.
            boolean bakeJson = generic == null && !chunkVisible(model, state)
                    && !(model.getQuads(null, null, net.minecraft.util.RandomSource.create(42)).isEmpty()
                    && model.getQuads(null, net.minecraft.core.Direction.UP, net.minecraft.util.RandomSource.create(42)).isEmpty());
            //The carriage's body json is chunk-visible, so a failed generic capture would fall to the
            //SKIP verdict and the pinion + inner shaft would simply not exist at LOD range. Those two
            //parts are reproducible from state and position alone (bakeGantryCarriage), so the record
            //stays real instead.
            boolean gantryCarriage = generic == null
                    && be instanceof com.simibubi.create.content.contraptions.gantry.GantryCarriageBlockEntity;
            if (generic == null && !bakeJson && shaftHalfFacing == null && bearingFacing == null
                    && chains == null && bnbChain == null && !gantryCarriage) {
                //Nothing to bake for this state - record that verdict so the sweep's dedup holds and
                //the classification (renderer probe, chunkVisible, light sampling) runs once, not once
                //per sweep pass. The bucket lifecycle - ghost reclaim, distance and GPU eviction,
                //dimension clear - covers the record without a parallel structure. The stored state is
                //the retry key: a different block at this position re-evaluates.
                Bucket bucket = SECTIONS.computeIfAbsent(sectionKey(pos), k -> new Bucket());
                Snap prior = bucket.geoms.put(pos.immutable(),
                        new Snap(state, null, 0.0f, 0, 0, false, null, null, 0.0f, false, null, null, null, false));
                if (prior != null && !prior.hollow()) {
                    bucket.dirty = true; //the old state had geometry; rebuild the mesh without it
                }
                logCapture(pos.toShortString() + " SKIP " + state.getBlock().getDescriptionId()
                        + " (chunk-visible or empty json, no partials)");
                return;
            }
            net.minecraft.core.Direction.Axis axis = null;
            try {
                axis = KineticBlockEntityRenderer.getRotationAxisOf(be);
            } catch (Throwable ignored) {
            }
            //Phase at render time zero, not at the capture tick. Neighbouring shafts of one drivetrain
            //are captured on different ticks (the sweep spreads work, the budget splits chunks), and a
            //per-capture time term gives each segment its own frozen moment - a connected line reads as
            //broken. The offset term alone is the drivetrain's pose at one shared instant, so every
            //segment agrees; the cost is a small phase snap where a moving part crosses the LOD
            //boundary, invisible at that distance next to adjacent segments disagreeing. The bearing
            //top disc keeps its freeze-tick angle (bearingTopAngleRad) - that one must match the
            //contraption pose frozen with it.
            float angle = axis != null
                    ? KineticBlockEntityRenderer.getRotationOffsetForPosition(be, pos, axis) % 360.0f / 180.0f * (float) Math.PI
                    : 0.0f;
            Bucket bucket = SECTIONS.computeIfAbsent(sectionKey(pos), k -> new Bucket());
            bucket.geoms.put(pos.immutable(), new Snap(state, axis, angle,
                    skyLight, blockLight,
                    bakeJson, shaftHalfFacing, bearingFacing, bearingTopAngle, woodenTop, chains, bnbChain, generic,
                    gantryCarriage));
            bucket.dirty = true;
            if (bearingFacing != null) {
                BEARING_POSITIONS.add(pos.immutable());
                if (!inManagerRecapture) {
                    ANCHOR_RECAPTURED.add(pos.immutable());
                }
            }
            logCapture(pos.toShortString() + " OK block=" + state.getBlock().getDescriptionId()
                    + " generic=" + (generic != null ? generic.length / 9 + "v" : "no")
                    + " json=" + bakeJson
                    + " angle=" + String.format("%.1f", Math.toDegrees(angle)));
        } catch (Throwable e) {
            logCapture(pos.toShortString() + " ERROR " + e);
            //A broken state must not take the tick down; that block simply keeps the hidden-only cull
        }
    }

    static void rotateAboutAxis(org.joml.Matrix4f transform, net.minecraft.core.Direction.Axis axis, float angleRad) {
        if (axis == null || angleRad == 0.0f) {
            return;
        }
        transform.rotate(angleRad,
                axis == net.minecraft.core.Direction.Axis.X ? 1 : 0,
                axis == net.minecraft.core.Direction.Axis.Y ? 1 : 0,
                axis == net.minecraft.core.Direction.Axis.Z ? 1 : 0);
    }

    //Gantry carriage moving parts (inner shaft + pinion), computed from state and position alone so
    //the frozen contraption bake - where the entity and its block entities are long gone - and the
    //placed-block fallback share one implementation. Angles are the offset-only t=0 convention every
    //frozen drivetrain part uses. Matrix order is GantryCarriageRenderer.renderSafe's SuperByteBuffer
    //call order (first call outermost); the pinion's visual position, axis flip and 9/16 pivot are
    //that renderer's own.
    static void bakeGantryCarriage(DistantMeshBuilder builder, org.joml.Matrix4f transform,
                                   net.minecraft.world.level.block.state.BlockState state,
                                   BlockPos parityPos, float lx, float ly, float lz, int sky, int block) {
        var rotationAxis = ((com.simibubi.create.content.kinetics.base.IRotate) state.getBlock())
                .getRotationAxis(state);
        var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();

        var shaftModel = shaper.getBlockModel(KineticBlockEntityRenderer.shaft(rotationAxis));
        float shaftAngle = rad(com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
                .rotationOffset(state, rotationAxis, parityPos) % 360.0f);
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, rotationAxis, shaftAngle);
        transform.translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(shaftModel, transform, sky, block);

        var facing = state.getValue(com.simibubi.create.content.contraptions.gantry.GantryCarriageBlock.FACING);
        boolean alongFirst = state.getValue(com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE);
        var visualPos = facing.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE
                ? parityPos : parityPos.relative(facing.getOpposite());
        float angleDeg = com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual
                .rotationOffset(state, rotationAxis, visualPos) % 360.0f;
        //The axis that is neither the rotation axis nor the facing axis decides the spin sign
        var gantryAxis = net.minecraft.core.Direction.Axis.Z;
        for (var candidate : net.minecraft.core.Direction.Axis.values()) {
            if (candidate != rotationAxis && candidate != facing.getAxis()) {
                gantryAxis = candidate;
                break;
            }
        }
        if ((gantryAxis == net.minecraft.core.Direction.Axis.X && facing == net.minecraft.core.Direction.UP)
                || (gantryAxis == net.minecraft.core.Direction.Axis.Y
                && (facing == net.minecraft.core.Direction.NORTH || facing == net.minecraft.core.Direction.EAST))) {
            angleDeg = -angleDeg;
        }
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                .rotateY(rad(horizontalAngle(facing)))
                .rotateX(rad(facing == net.minecraft.core.Direction.UP ? 0.0f
                        : facing == net.minecraft.core.Direction.DOWN ? 180.0f : 90.0f))
                .rotateY(rad((alongFirst ^ (facing.getAxis() == net.minecraft.core.Direction.Axis.X)) ? 0.0f : 90.0f))
                .translate(0.0f, -9.0f / 16.0f, 0.0f)
                .rotateX(rad(-angleDeg))
                .translate(0.0f, 9.0f / 16.0f, 0.0f)
                .translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.GANTRY_COGS.get(), transform, sky, block);
    }

    //Shaft half at the machine's frozen kinetic angle: rotateToFace(facing) alignment as
    //CachedBuffers.partialFacing bakes it, kinetic spin outside it about the facing axis. Matrix order
    //is the SuperByteBuffer call order (first call outermost). Covers bearings (facing = back of the
    //bearing) and creative motors (facing = the FACING property).
    private static void bakeShaftHalf(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                      float lx, float ly, float lz) {
        var facing = snap.shaftHalfFacing();
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, facing.getAxis(), snap.angleRad());
        transform.rotateY(rad(horizontalAngle(facing)))
                .rotateX(rad(verticalAngle(facing)))
                .translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.SHAFT_HALF.get(), transform,
                snap.sky(), snap.block());
    }

    //Reproduces BearingRenderer.renderSafe's top disc: spin by the bearing's own frozen angle about
    //the facing axis, then align to the facing (catnip angle helpers inlined).
    private static void bakeBearingTop(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                       float lx, float ly, float lz) {
        var facing = snap.bearingFacing();
        var opposite = facing.getOpposite();
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, facing.getAxis(), snap.bearingTopAngleRad());
        if (facing.getAxis().isHorizontal()) {
            transform.rotateY(rad(horizontalAngle(opposite)));
        }
        transform.rotateX(rad(-90.0f - verticalAngle(facing)))
                .translate(-0.5f, -0.5f, -0.5f);
        var top = snap.woodenTop()
                ? com.simibubi.create.AllPartialModels.BEARING_TOP_WOODEN
                : com.simibubi.create.AllPartialModels.BEARING_TOP;
        builder.transformedModel(top.get(), transform, snap.sky(), snap.block());
    }

    //Chain conveyor: the wheel shaft (kinetic spin), the wheel disc, and per connection the guard
    //plate plus the chain strap. Reproduces ChainConveyorRenderer's backend-off pass at freeze pose;
    //the strap is its far-mip variant (static UVs, thin radius), vertices hand-built the way
    //renderPart lays them out, textured from the vanilla chain sprite in the block atlas.
    private static void bakeChainConveyor(DistantMeshBuilder builder, org.joml.Matrix4f transform, Snap snap,
                                          float lx, float ly, float lz) {
        transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
        rotateAboutAxis(transform, snap.axis(), snap.angleRad());
        transform.translate(-0.5f, -0.5f, -0.5f);
        builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_SHAFT.get(), transform,
                snap.sky(), snap.block());

        transform.identity().translate(lx, ly, lz);
        builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_WHEEL.get(), transform,
                snap.sky(), snap.block());

        var chainSprite = net.minecraft.client.Minecraft.getInstance()
                .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/chain"));
        var vertex = new org.joml.Vector3f();
        for (float[] chain : snap.chains()) {
            float yawDeg = chain[3], pitchDeg = chain[4], length = chain[5];
            int sky2 = (int) chain[6], block2 = (int) chain[7];

            transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                    .rotateY(rad(yawDeg))
                    .translate(-0.5f, -0.5f, -0.5f);
            builder.transformedModel(com.simibubi.create.AllPartialModels.CHAIN_CONVEYOR_GUARD.get(), transform,
                    snap.sky(), snap.block());

            //ChainConveyorRenderer.renderChains transform chain, then renderChain's (0.5, 0, 0.5)
            transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f)
                    .translate(chain[0], chain[1], chain[2])
                    .rotateY(rad(yawDeg))
                    .rotateX(rad(90.0f - pitchDeg))
                    .rotateY(rad(45.0f))
                    .translate(0.0f, 0.5f, 0.0f)
                    .translate(-0.5f, -0.5f, -0.5f)
                    .translate(0.5f, 0.0f, 0.5f);

            //Far-mip strap: radius 1/16, static texture window, two crossed double-sided ribbons
            float radius = 0.0625f;
            float minU = chainSprite.getU(0.1875f), maxU = chainSprite.getU(0.25f);
            float minV = chainSprite.getV(0.0f), maxV = chainSprite.getV(0.0625f);
            chainQuad(builder, transform, vertex, length, 0, radius, 0, -radius, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, 0, -radius, 0, radius, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, radius, 0, -radius, 0, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
            chainQuad(builder, transform, vertex, length, -radius, 0, radius, 0, minU, maxU, minV, maxV, snap.sky(), snap.block(), sky2, block2);
        }
    }

    //One chain ribbon quad in ChainConveyorRenderer.renderQuad's vertex order: top(light2), bottom,
    //bottom, top - light blends from the near wheel to the far one along the strap
    private static void chainQuad(DistantMeshBuilder builder, org.joml.Matrix4f transform, org.joml.Vector3f vertex,
                                  float length, float x0, float z0, float x1, float z1,
                                  float minU, float maxU, float minV, float maxV,
                                  int sky1, int block1, int sky2, int block2) {
        emitChainVertex(builder, transform, vertex, x0, length, z0, maxU, minV, sky2, block2);
        emitChainVertex(builder, transform, vertex, x0, 0.0f, z0, maxU, maxV, sky1, block1);
        emitChainVertex(builder, transform, vertex, x1, 0.0f, z1, minU, maxV, sky1, block1);
        emitChainVertex(builder, transform, vertex, x1, length, z1, minU, minV, sky2, block2);
    }

    private static void emitChainVertex(DistantMeshBuilder builder, org.joml.Matrix4f transform, org.joml.Vector3f vertex,
                                        float x, float y, float z, float u, float v, int sky, int block) {
        vertex.set(x, y, z);
        transform.transformPosition(vertex);
        builder.rawVertex(vertex.x, vertex.y, vertex.z, u, v, sky, block, 1.0f, 1);
    }

    //catnip AngleHelper, inlined
    private static float horizontalAngle(net.minecraft.core.Direction facing) {
        if (facing.getAxis().isVertical()) {
            return 0.0f;
        }
        float angle = facing.toYRot();
        return facing.getAxis() == net.minecraft.core.Direction.Axis.X ? -angle : angle;
    }

    private static float verticalAngle(net.minecraft.core.Direction facing) {
        return facing == net.minecraft.core.Direction.UP ? -90.0f
                : facing == net.minecraft.core.Direction.DOWN ? 90.0f : 0.0f;
    }

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    //Does the chunk actually render this model? Two independent gates, both required:
    //renderShape != MODEL means the chunk never draws the json no matter what the model answers
    //(bits_n_bobs cogs: ANIMATED + a plain json - the json is the BER's spin model, snapshot owns it),
    //and a MODEL-shaped block can still hide its json from the chunk layers per render type
    //(Create cogs). Only a MODEL-shaped block whose model answers the chunk layers is chunk-visible.
    static boolean chunkVisible(net.minecraft.client.resources.model.BakedModel model,
                                net.minecraft.world.level.block.state.BlockState state) {
        if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) {
            return false;
        }
        var random = net.minecraft.util.RandomSource.create(42);
        for (var layer : new net.minecraft.client.renderer.RenderType[]{
                net.minecraft.client.renderer.RenderType.solid(),
                net.minecraft.client.renderer.RenderType.cutoutMipped(),
                net.minecraft.client.renderer.RenderType.cutout()}) {
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                random.setSeed(42);
                if (!model.getQuads(state, direction, random,
                        net.neoforged.neoforge.client.model.data.ModelData.EMPTY, layer).isEmpty()) {
                    return true;
                }
            }
            random.setSeed(42);
            if (!model.getQuads(state, null, random,
                    net.neoforged.neoforge.client.model.data.ModelData.EMPTY, layer).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void rebake(Bucket bucket) {
        if (bucket.mesh != null) {
            bucket.mesh.free();
            bucket.mesh = null;
        }
        var builder = new DistantMeshBuilder();
        try {
            var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
            var transform = new org.joml.Matrix4f();
            for (var entry : bucket.geoms.entrySet()) {
                BlockPos pos = entry.getKey();
                Snap snap = entry.getValue();
                float lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
                if (snap.generic() != null) {
                    //Full-pass capture, already section-local
                    float[] v = snap.generic();
                    for (int i = 0; i + 9 <= v.length; i += 9) {
                        builder.rawVertex(v[i], v[i + 1], v[i + 2], v[i + 3], v[i + 4],
                                (int) v[i + 5], (int) v[i + 6], v[i + 7], (int) v[i + 8]);
                    }
                }
                if (snap.bakeJson()) {
                    var model = shaper.getBlockModel(snap.state());
                    //Section-local block offset, then the frozen spin about the block centre. The
                    //baked model already carries the blockstate's axis alignment (variant rotations).
                    transform.identity().translate(lx, ly, lz).translate(0.5f, 0.5f, 0.5f);
                    rotateAboutAxis(transform, snap.axis(), snap.angleRad());
                    transform.translate(-0.5f, -0.5f, -0.5f);
                    builder.transformedModel(model, transform, snap.sky(), snap.block());
                }
                if (snap.gantryCarriage()) {
                    bakeGantryCarriage(builder, transform, snap.state(), pos, lx, ly, lz,
                            snap.sky(), snap.block());
                }
                if (snap.shaftHalfFacing() != null) {
                    bakeShaftHalf(builder, transform, snap, lx, ly, lz);
                }
                if (snap.bearingFacing() != null) {
                    bakeBearingTop(builder, transform, snap, lx, ly, lz);
                }
                if (snap.chains() != null) {
                    bakeChainConveyor(builder, transform, snap, lx, ly, lz);
                }
                if (snap.bnbChain() != null) {
                    float[] v = snap.bnbChain();
                    for (int i = 0; i + 9 <= v.length; i += 9) {
                        builder.rawVertex(v[i] + lx, v[i + 1] + ly, v[i + 2] + lz, v[i + 3], v[i + 4],
                                (int) v[i + 5], (int) v[i + 6], v[i + 7], (int) v[i + 8]);
                    }
                }
            }
            bucket.mesh = builder.build();
        } catch (Throwable e) {
            builder.discard();
        }
        int count = 0;
        for (Bucket b : SECTIONS.values()) {
            for (Snap s2 : b.geoms.values()) {
                if (!s2.hollow()) {
                    count++;
                }
            }
        }
        snapshotCount = count;
    }

    private static long sectionKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }


    //Discards render passes the snapshot must not carry (non-block-atlas layers: chain straps on
    //standalone textures, glowing overlays)
    private static final com.mojang.blaze3d.vertex.VertexConsumer NOOP = new com.mojang.blaze3d.vertex.VertexConsumer() {
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) { return this; }
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) { return this; }
    };

    //Vertex capture fed by the machine's own renderer: section-local offset applied here, per-BE light
    //baked into the vertices, shade/face derived from the streamed normal. Package-visible: the ship
    //kinetic renderer drives the same capture with a zero offset.
    static final class Capture implements net.minecraft.client.renderer.MultiBufferSource, com.mojang.blaze3d.vertex.VertexConsumer {
        private final float ox, oy, oz;
        private final int sky, block;
        private final java.util.ArrayList<float[]> verts = new java.util.ArrayList<>();
        private boolean pending;
        private float x, y, z, u, v, nx, ny, nz;

        Capture(float ox, float oy, float oz, int sky, int block) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.sky = sky;
            this.block = block;
        }

        float[] toArray() {
            float[] out = new float[this.verts.size() * 9];
            for (int i = 0; i < this.verts.size(); i++) {
                System.arraycopy(this.verts.get(i), 0, out, i * 9, 9);
            }
            return out;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer getBuffer(net.minecraft.client.renderer.RenderType type) {
            String name = type.toString();
            if (name.contains("solid") || name.contains("cutout")) {
                return this;
            }
            return NOOP;
        }

        void flush() {
            if (!this.pending) {
                return;
            }
            float shade;
            int face;
            if (this.ny > 0.6f) {
                shade = 1.0f;
                face = 1;
            } else if (this.ny < -0.6f) {
                shade = 0.5f;
                face = 0;
            } else if (Math.abs(this.nz) > Math.abs(this.nx)) {
                shade = 0.8f;
                face = this.nz > 0 ? 3 : 2;
            } else {
                shade = 0.6f;
                face = this.nx > 0 ? 5 : 4;
            }
            this.verts.add(new float[]{this.x + this.ox, this.y + this.oy, this.z + this.oz,
                    this.u, this.v, this.sky, this.block, shade, face});
            this.pending = false;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            this.flush();
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0;
            this.v = 0;
            this.nx = 0;
            this.ny = 1;
            this.nz = 0;
            this.pending = true;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }
    }
}
