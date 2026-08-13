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
    //Sweep captures cut short by the per-tick wall-clock budget (work deferred, not lost)
    public static final LongAdder kineticDeadlineCut = new LongAdder();
    //Ingest jobs replaced in place by a newer job for the same section (the pileup dedup)
    public static final LongAdder ingestSuperseded = new LongAdder();
    //Arrival vs drain. A sustained gap between them is the signature of a producer (pregen) feeding
    //faster than the store can absorb - equal counts mean ingest is not the bottleneck at all
    public static final LongAdder ingestEnqueued = new LongAdder();
    public static final LongAdder ingestProcessed = new LongAdder();
    //Saves that bypassed the soft cap because the caller holds a lock and cannot block. Bounded by
    //the distinct-live-dirty-section count (a section can only be in the queue once), but the count
    //is what decides whether that path is worth hardening
    public static final LongAdder saveEnqueuedNonBlocking = new LongAdder();
    //Store writes that failed and re-queued their sections. A climbing count with flat progress is
    //a full disk or a dying store, which reads to a player as "memory filled and never came down"
    public static final LongAdder saveCommitFailed = new LongAdder();
    public static final LongAdder saveDroppedAfterRetries = new LongAdder();
    //Baked train shapes closed by the orphan/budget sweep (each freed a VAO/VBO)
    public static final LongAdder trainShapeSweepClosed = new LongAdder();
    //Oldest pending ingest dropped by the hard fuse under a storage stall
    public static final LongAdder ingestOverflowDropped = new LongAdder();
    //Same-block state flips whose recapture the sweep deferred inside the cooldown window
    public static final LongAdder kineticStateRecaptureDeferred = new LongAdder();
    public static final LongAdder contraptionSnapshotEvicted = new LongAdder();
    //Per-tick 64KB re-bakes avoided for contraptions that resolved to no drawable mesh
    public static final LongAdder contraptionRebakeSkipped = new LongAdder();
    //Traversal "request already in flight" warns suppressed after the first few
    public static final LongAdder nodeWarnSuppressed = new LongAdder();
    //--- distant beacons ---
    //Solves aborted at a section not in the section cache - each abort keeps a synchronous RocksDB
    //column walk (up to ~24 loads) off the render thread; the beam keeps its previous shape until
    //the background warm lands and re-queues it
    public static final LongAdder beaconSolveDeferred = new LongAdder();
    //Sections the background warmer read on behalf of those solves (repeats and already-cached keys
    //included: the job re-touches the whole column so the retry cannot miss)
    public static final LongAdder beaconSolveCacheMiss = new LongAdder();

    //--- distant train server ---
    //Carriage pose reuse across players in a dimension: a hit skipped a buildBogeyPoses + trig
    public static final LongAdder trainPoseCacheHit = new LongAdder();
    public static final LongAdder trainPoseCacheMiss = new LongAdder();
    //Heavy Contraption.fromNBT shape builds pushed to a later tick by the per-round budget
    public static final LongAdder trainShapeBuildDeferred = new LongAdder();

    //--- world section uniform mode ---
    //Sections that stayed uniform for their whole life (each saved a 256KiB alloc + memset)
    public static final LongAdder sectionUniformKept = new LongAdder();
    //Sections that had to allocate a real array (the denominator for the uniform hit rate)
    public static final LongAdder sectionMaterialized = new LongAdder();

    //Sections ingested with no owning chunk, i.e. handed over by a server-side LOD sender rather than
    //loaded by this client. The one unambiguous sign that bridge is alive - terrain simply looking
    //fuller cannot tell a working sender from the client having flown there earlier.
    public static final LongAdder sectionIngestedChunkless = new LongAdder();
    public static final LongAdder sectionIngestedWithChunk = new LongAdder();
    //Materialise calls that found another thread had already done it (contention, but no wasted work)
    public static final LongAdder sectionMaterializeContended = new LongAdder();
    //Neighbour face slices filled from a uniform value instead of copied out of an array
    public static final LongAdder neighborFaceUniformFill = new LongAdder();
    //Ingest writes whose values all matched the uniform value, so the section stayed uniform
    public static final LongAdder sectionUniformWriteSkipped = new LongAdder();
    //Sections whose translucent or double-sided quad bucket filled up and had quads dropped. Non-zero
    //means geometry is missing from those sections - see the guard in RenderDataFactory.
    public static final LongAdder quadBucketOverflow = new LongAdder();

    //Section array pool health: misses allocate 256KiB each, overflows discard to GC - both
    //nonzero together means the pool cap sits below the churn amplitude
    public static final LongAdder sectionArrayPoolMiss = new LongAdder();
    public static final LongAdder sectionArrayPoolOverflow = new LongAdder();
    //Sections dumped wholesale from a tracker's LRU (dimension hop) - the step-change source
    public static final LongAdder trackerCacheDumped = new LongAdder();

    //--- section saving ---
    //Batched section writes: sections/commits is the headline (>1 means batching is working at all)
    public static final LongAdder saveBatchCommits = new LongAdder();
    public static final LongAdder saveBatchSections = new LongAdder();

    private static String ratio(String name, LongAdder hit, LongAdder miss) {
        long h = hit.sum();
        long m = miss.sum();
        long total = h + m;
        double pct = total == 0 ? 0.0 : (100.0 * h / total);
        return String.format("  %-22s hit=%,d miss=%,d (%.2f%% hit)", name, h, m, pct);
    }

    public static String report() {
        StringBuilder sb = new StringBuilder("Voxy optimization stats:\n");
        sb.append(ratio("biome-id cache", biomeCacheHit, biomeCacheMiss)).append('\n');
        sb.append(ratio("copycat material cache", copycatKeyHit, copycatKeyMiss)).append('\n');
        sb.append(ratio("train pose reuse", trainPoseCacheHit, trainPoseCacheMiss)).append('\n');
        sb.append(String.format("  %-22s %,d", "kinetic snapshots evicted", kineticSnapshotEvicted.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "kinetic deadline cuts", kineticDeadlineCut.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "ingest jobs superseded", ingestSuperseded.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "ingest overflow drops", ingestOverflowDropped.sum())).append('\n');
        sb.append(String.format("  %-22s %,d in / %,d out", "ingest arrival/drain",
                ingestEnqueued.sum(), ingestProcessed.sum())).append('\n');
        sb.append(String.format("  %-22s %,d nonblocking, %,d failed, %,d dropped", "save enqueue",
                saveEnqueuedNonBlocking.sum(), saveCommitFailed.sum(), saveDroppedAfterRetries.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "train shapes swept", trainShapeSweepClosed.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "kinetic recaptures deferred", kineticStateRecaptureDeferred.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "contraption snaps evicted", contraptionSnapshotEvicted.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "contraption rebakes skipped", contraptionRebakeSkipped.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "train shapes deferred", trainShapeBuildDeferred.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "node warns suppressed", nodeWarnSuppressed.sum())).append('\n');
        sb.append(String.format("  %-22s %,d (warmed %,d sections)", "beacon solves deferred",
                beaconSolveDeferred.sum(), beaconSolveCacheMiss.sum())).append('\n');
        long commits = saveBatchCommits.sum();
        long batched = saveBatchSections.sum();
        sb.append(String.format("  %-22s %,d sections in %,d commits (avg %.1f/commit)",
                "save batching", batched, commits, commits == 0 ? 0.0 : (double) batched / commits)).append('\n');
        long uniform = sectionUniformKept.sum();
        long materialized = sectionMaterialized.sum();
        long totalSections = uniform + materialized;
        sb.append(String.format("  %-22s uniform=%,d materialized=%,d (%.1f%% uniform, %,d MiB saved)",
                "section uniform mode", uniform, materialized,
                totalSections == 0 ? 0.0 : (100.0 * uniform / totalSections),
                (uniform * 256L) / 1024)).append('\n');
        sb.append(String.format("  %-22s %,d (contended %,d)",
                "neighbour uniform fill", neighborFaceUniformFill.sum(), sectionMaterializeContended.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "uniform writes skipped", sectionUniformWriteSkipped.sum())).append('\n');
        sb.append(String.format("  %-22s from server=%,d from this client=%,d",
                "section ingest source", sectionIngestedChunkless.sum(), sectionIngestedWithChunk.sum())).append('\n');
        sb.append(String.format("  %-22s miss=%,d overflow=%,d dumped=%,d", "array pool",
                sectionArrayPoolMiss.sum(), sectionArrayPoolOverflow.sum(), trackerCacheDumped.sum())).append('\n');
        sb.append(String.format("  %-22s %,d", "quad bucket overflows", quadBucketOverflow.sum()));
        return sb.toString();
    }

    //Curated window-delta view for the full report: lifetime sums are unreadable across a 30s
    //capture window, a delta names what actually moved
    public static java.util.LinkedHashMap<String, Long> snapshotForDelta() {
        var m = new java.util.LinkedHashMap<String, Long>();
        m.put("ingest enqueued", ingestEnqueued.sum());
        m.put("ingest processed", ingestProcessed.sum());
        m.put("ingest superseded", ingestSuperseded.sum());
        m.put("ingest overflow-dropped", ingestOverflowDropped.sum());
        m.put("save enqueued nonblocking", saveEnqueuedNonBlocking.sum());
        m.put("save batch commits", saveBatchCommits.sum());
        m.put("save batch sections", saveBatchSections.sum());
        m.put("save commit failures", saveCommitFailed.sum());
        m.put("sections uniform-kept", sectionUniformKept.sum());
        m.put("sections materialized", sectionMaterialized.sum());
        m.put("ingest from server (chunkless)", sectionIngestedChunkless.sum());
        m.put("ingest from client chunks", sectionIngestedWithChunk.sum());
        m.put("quad bucket overflows", quadBucketOverflow.sum());
        m.put("kinetic deadline cuts", kineticDeadlineCut.sum());
        m.put("train shapes swept", trainShapeSweepClosed.sum());
        m.put("array pool misses", sectionArrayPoolMiss.sum());
        m.put("array pool overflows", sectionArrayPoolOverflow.sum());
        m.put("tracker cache dumped", trackerCacheDumped.sum());
        return m;
    }

    public static void reset() {
        for (LongAdder a : new LongAdder[]{biomeCacheHit, biomeCacheMiss, copycatKeyHit, copycatKeyMiss,
                kineticSnapshotEvicted, contraptionSnapshotEvicted, contraptionRebakeSkipped, nodeWarnSuppressed,
                beaconSolveDeferred, beaconSolveCacheMiss,
                ingestEnqueued, ingestProcessed, saveEnqueuedNonBlocking, saveCommitFailed, saveDroppedAfterRetries,
                trainPoseCacheHit, trainPoseCacheMiss, trainShapeBuildDeferred,
                saveBatchCommits, saveBatchSections,
                sectionUniformKept, sectionMaterialized, sectionMaterializeContended, neighborFaceUniformFill, sectionUniformWriteSkipped,
                sectionIngestedChunkless, sectionIngestedWithChunk, quadBucketOverflow,
                sectionArrayPoolMiss, sectionArrayPoolOverflow, trackerCacheDumped}) {
            a.reset();
        }
    }
}
