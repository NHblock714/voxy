package me.cortex.voxy.commonImpl;

import java.util.concurrent.atomic.LongAdder;

//Counters for the fork's own optimizations, so their effect can be seen live (/voxy perf) instead of
//guessed at. LongAdder because most of these are incremented from many ingest/render threads and read
//rarely - it beats AtomicLong under that write contention and the read cost (sum) only happens on the
//command. Purely diagnostic: nothing here feeds behaviour, so it can be read/reset at any time.
public final class PerfStats {
    private PerfStats() {}

    //--- ingest hot path ---
    //Biome id resolution: a hit skipped a ResourceLocation.toString() + registry lookup (64x/section)
    public static final LongAdder biomeCacheHit = new LongAdder();
    public static final LongAdder biomeCacheMiss = new LongAdder();
    //Copycat material key: a hit skipped writeBlockState + toString for that block
    public static final LongAdder copycatKeyHit = new LongAdder();
    public static final LongAdder copycatKeyMiss = new LongAdder();

    //--- create client ---
    //Kinetic leave-behind snapshots dropped by the distance bound (each freed a VAO/VBO + heap verts)
    public static final LongAdder kineticSnapshotEvicted = new LongAdder();
    //Per-tick 64KB re-bakes avoided for contraptions that resolved to no drawable mesh
    public static final LongAdder contraptionRebakeSkipped = new LongAdder();
    //Traversal "request already in flight" warns suppressed after the first few
    public static final LongAdder nodeWarnSuppressed = new LongAdder();

    //--- distant train server ---
    //Carriage pose reuse across players in a dimension: a hit skipped a buildBogeyPoses + trig
    public static final LongAdder trainPoseCacheHit = new LongAdder();
    public static final LongAdder trainPoseCacheMiss = new LongAdder();
    //Heavy Contraption.fromNBT shape builds pushed to a later tick by the per-round budget
    public static final LongAdder trainShapeBuildDeferred = new LongAdder();

    private static String ratio(String name, LongAdder hit, LongAdder miss) {
        long h = hit.sum();
        long m = miss.sum();
        long total = h + m;
        double pct = total == 0 ? 0.0 : (100.0 * h / total);
        return String.format("  %-22s hit=%,d miss=%,d (%.2f%% hit, %,d saved)", name, h, m, pct, h);
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("Voxy optimization stats:\n");
        sb.append(ratio("biome-id cache", biomeCacheHit, biomeCacheMiss)).append('\n');
        sb.append(ratio("copycat material cache", copycatKeyHit, copycatKeyMiss)).append('\n');
        sb.append(ratio("train pose reuse", trainPoseCacheHit, trainPoseCacheMiss)).append('\n');
        sb.append(String.format("  %-22s %,d", "kinetic snapshots evicted", kineticSnapshotEvicted.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "contraption rebakes skipped", contraptionRebakeSkipped.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "train shapes deferred", trainShapeBuildDeferred.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "node warns suppressed", nodeWarnSuppressed.sum()));
        return sb.toString();
    }

    public static void reset() {
        for (LongAdder a : new LongAdder[]{biomeCacheHit, biomeCacheMiss, copycatKeyHit, copycatKeyMiss,
                kineticSnapshotEvicted, contraptionRebakeSkipped, nodeWarnSuppressed,
                trainPoseCacheHit, trainPoseCacheMiss, trainShapeBuildDeferred}) {
            a.reset();
        }
    }
}
