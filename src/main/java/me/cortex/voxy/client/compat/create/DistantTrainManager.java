package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriagePose;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.CarriageShapePayload;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.ShapeBogey;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.TrainPosesPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

//Client-side state for distant trains: baked carriage meshes keyed by shape id plus the latest two
//pose samples per carriage for interpolation. All access happens on the render/main thread.
public final class DistantTrainManager {
    private DistantTrainManager() {}

    //Wheel rotation is accumulated client side from interpolated bogey movement, so it stays smooth
    //regardless of the pose sample rate.
    public static final class BogeyAnim {
        public float wheelAngle;
        public double lastX, lastY, lastZ;
        public boolean hasLast;
    }

    public static final class CarriageTrack {
        public long shapeId;
        public CarriagePose prev;
        public CarriagePose cur;
        public long curTimeNanos;
        public long sampleIntervalNanos = 250_000_000L;
        public BogeyAnim[] bogeyAnims;
        //Voxel-store light sample at the carriage position, refreshed periodically while it moves
        public int lightPacked = -1;
        public long lightSampledAtMs;
        //Last time the carriage's section read compiled, for the handover hysteresis
        public long lastCompiledMs;
    }

    public static final class TrainState {
        public ResourceLocation dimension;
        public final Map<Integer, CarriageTrack> carriages = new HashMap<>();
    }

    public static final class ShapeEntry {
        private final CarriageMeshBaker.BakedCarriage mesh;
        private final float initialYaw;
        private final List<ShapeBogey> bogeys;
        //Refreshed at pose arrival and at draw lookup. The sweep may only close shapes nothing has
        //referenced for a while, because a shapeId embeds the train UUID: a disassembled train's
        //shapes are unreachable forever, and only re-entry of the same live train re-references one
        //- the server re-sends on window re-entry, so a closed mesh is recoverable, an unreferenced
        //one is not.
        volatile long lastReferencedMs;

        ShapeEntry(CarriageMeshBaker.BakedCarriage mesh, float initialYaw, List<ShapeBogey> bogeys) {
            this.mesh = mesh;
            this.initialYaw = initialYaw;
            this.bogeys = bogeys;
            this.lastReferencedMs = System.currentTimeMillis();
        }

        public CarriageMeshBaker.BakedCarriage mesh() { return this.mesh; }
        public float initialYaw() { return this.initialYaw; }
        public List<ShapeBogey> bogeys() { return this.bogeys; }

        void close() {
            this.mesh.close();
        }
    }

    private static final Map<UUID, TrainState> TRAINS = new ConcurrentHashMap<>();
    private static final Map<Long, ShapeEntry> SHAPES = new ConcurrentHashMap<>();

    //Diagnostics for /voxy debug trains
    public static volatile int shapesReceived;
    public static volatile int bakesFailed;

    public static void handleShape(CarriageShapePayload payload) {
        shapesReceived++;
        try {
            //The server re-sends a shape whenever a train re-enters a player's window, and that
            //resend is load-bearing (sweepShapes closes unreferenced entries after 60s and there
            //is no client request packet) - but re-BAKING an already-resident shape is not: that
            //is a full mesh bake + VBO churn on the main thread twice per train pass. Same id =
            //same immutable shape, so refresh the sweep clock and keep the mesh.
            var resident = SHAPES.get(payload.shapeId());
            if (resident != null) {
                resident.lastReferencedMs = System.currentTimeMillis();
                return;
            }
            //Rebuild copycat ModelData from the material slices the server attached to the shape
            java.util.Map<net.minecraft.core.BlockPos, net.neoforged.neoforge.client.model.data.ModelData> blockEntityData = null;
            for (var block : payload.blocks()) {
                if (block.renderNbt().isEmpty()) {
                    continue;
                }
                var data = me.cortex.voxy.commonImpl.compat.CreateCopycatCompat
                        .materialFromContraptionNbt(block.state(), block.renderNbt().get());
                if (data != null) {
                    if (blockEntityData == null) {
                        blockEntityData = new java.util.HashMap<>();
                    }
                    blockEntityData.put(new net.minecraft.core.BlockPos(block.x(), block.y(), block.z()), data);
                }
            }
            var baked = CarriageMeshBaker.bake(payload.blocks(), blockEntityData);
            if (baked != null) {
                SHAPES.put(payload.shapeId(), new ShapeEntry(baked, payload.initialYaw(), payload.bogeys()));
            } else {
                bakesFailed++;
            }
        } catch (Throwable e) {
            //enqueueWork futures swallow exceptions; surface them ourselves
            bakesFailed++;
            me.cortex.voxy.common.Logger.error("Distant train shape handling failed (shapeId="
                    + Long.toHexString(payload.shapeId()) + ")", e);
        }
    }

    public static void handlePoses(TrainPosesPayload payload) {
        if (payload.carriages().isEmpty()) {
            removeTrain(payload.trainId());
            return;
        }
        var state = TRAINS.computeIfAbsent(payload.trainId(), k -> new TrainState());
        state.dimension = payload.dimension();
        long now = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        for (var pose : payload.carriages()) {
            var track = state.carriages.computeIfAbsent(pose.carriageIndex(), k -> new CarriageTrack());
            if (track.cur != null) {
                track.sampleIntervalNanos = Math.max(50_000_000L, Math.min(2_000_000_000L, now - track.curTimeNanos));
            }
            track.prev = track.cur != null ? track.cur : pose;
            track.cur = pose;
            track.curTimeNanos = now;
            track.shapeId = pose.shapeId();
            var entry = SHAPES.get(pose.shapeId());
            if (entry != null) {
                entry.lastReferencedMs = nowMs;
            }
        }
    }

    public static void removeTrain(UUID trainId) {
        //Poses only: trains drift in and out of the window constantly and the server sends each
        //shape once per session, so baked meshes stay cached until logout or replacement.
        TRAINS.remove(trainId);
    }

    public static void clearAll() {
        TRAINS.clear();
        for (var baked : SHAPES.values()) {
            baked.close();
        }
        SHAPES.clear();
    }

    public static Map<UUID, TrainState> trains() {
        return TRAINS;
    }

    public static ShapeEntry shape(long shapeId) {
        var entry = SHAPES.get(shapeId);
        if (entry != null) {
            entry.lastReferencedMs = System.currentTimeMillis();
        }
        return entry;
    }

    private static long lastSweepMs;

    //Baked shapes were kept for the whole session ("the server sends each shape once"), but a
    //shapeId embeds the train UUID and every disassembly mints a new one - on a pack whose trains
    //assemble and disassemble all day, that is an unbounded GL ratchet nothing ever walks back.
    //Orphans (nothing referenced them for a minute) close outright; past the GPU budget the oldest
    //referenced shapes go too, except anything in active use. The server backstop re-sends a shape
    //on window re-entry, so a closed mesh costs one resend, an unreferenced one costs VRAM forever.
    public static void sweepShapes() {
        long now = System.currentTimeMillis();
        if (now - lastSweepMs < 1000) {
            return;
        }
        lastSweepMs = now;
        var it = SHAPES.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().lastReferencedMs > 60_000) {
                entry.getValue().close();
                it.remove();
                me.cortex.voxy.commonImpl.PerfStats.trainShapeSweepClosed.increment();
            }
        }
        long budget = (long) me.cortex.voxy.client.config.VoxyConfig.CONFIG.distantTrainGpuBudgetMiB * 1024L * 1024L;
        if (budget <= 0) {
            return;
        }
        long resident = 0;
        for (var entry : SHAPES.values()) {
            resident += entry.mesh().mesh.gpuByteSize();
        }
        if (resident <= budget) {
            return;
        }
        long target = (budget * 9L) / 10L;
        var byAge = new java.util.ArrayList<>(SHAPES.entrySet());
        byAge.sort(java.util.Comparator.comparingLong(e -> e.getValue().lastReferencedMs));
        for (var entry : byAge) {
            if (resident <= target) {
                break;
            }
            //In active use this second: keep drawing even over budget rather than flickering a
            //visible train
            if (now - entry.getValue().lastReferencedMs < 2000) {
                continue;
            }
            var removed = SHAPES.remove(entry.getKey());
            if (removed != null) {
                resident -= removed.mesh().mesh.gpuByteSize();
                removed.close();
                me.cortex.voxy.commonImpl.PerfStats.trainShapeSweepClosed.increment();
            }
        }
    }

    public static long shapesGpuBytes() {
        long total = 0;
        for (var entry : SHAPES.values()) {
            total += entry.mesh().mesh.gpuByteSize();
        }
        return total;
    }

    public static int meshCount() {
        return SHAPES.size();
    }

    public static java.util.Set<Long> meshKeys() {
        return SHAPES.keySet();
    }

    public static boolean isEmpty() {
        return TRAINS.isEmpty();
    }
}
