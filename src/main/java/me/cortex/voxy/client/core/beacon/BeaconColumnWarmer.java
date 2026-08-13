package me.cortex.voxy.client.core.beacon;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.BlockPos;

//Pulls a beacon's column sections into the section cache from a background thread, so the cache-only
//solve that aborted can complete on a later frame without the render thread touching storage.
//Mirrors DistantLightSampler's warm pool rather than sharing it: that one is per-section
//fire-and-forget, this one is per-beacon whole-column with a completion re-queue, and neither should
//reshape the other's contract. One job covers everything the solve can read - the 3x3 base layer and
//every walk section - so a fully cold column costs one round trip, not one per section.
//
//An absent section is settled by the same read: the nullOnEmpty acquire mints it as a uniform
//sky-air section that lands in the cache, so the retry's cache-only walk reads it as air. Miss ->
//warm -> hit holds for present and absent sections alike, which is what rules out a miss/warm
//livelock under VSS streaming churn.
public final class BeaconColumnWarmer {
    //Beacons with a warm in flight, so a stalled storage read cannot stack jobs for one beacon.
    //Keyed by beacon pos, which is engine-agnostic: across a dimension switch a stale in-flight key
    //can suppress one warm for the new engine, and the renderer's retry backstop re-queues it.
    private static final LongOpenHashSet WARMING = new LongOpenHashSet();
    private static final java.util.concurrent.ExecutorService WARM_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "Voxy beacon column warmup");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private BeaconColumnWarmer() {}

    //Render thread. walkTop is the same bound the aborted solve used, so the job reads exactly what
    //the retry will read.
    public static void warm(WorldEngine engine, int bx, int by, int bz, int walkTop) {
        long beaconPos = BlockPos.asLong(bx, by, bz);
        synchronized (WARMING) {
            if (!WARMING.add(beaconPos)) {
                return;
            }
        }
        try {
            engine.acquireRef();
        } catch (RuntimeException e) {
            //World tearing down - nothing to warm; the retry backstop owns the re-queue
            synchronized (WARMING) {
                WARMING.remove(beaconPos);
            }
            return;
        }
        WARM_POOL.execute(() -> {
            try {
                if (engine.isLive()) {
                    //Base layer: the 3x3 at by-1 spans at most 2x2 sections; its overlap with the
                    //column's own section repeats a key, and a repeat is a cache hit, not a read
                    int sy = (by - 1) >> 5;
                    for (int sx = (bx - 1) >> 5; sx <= (bx + 1) >> 5; sx++) {
                        for (int sz = (bz - 1) >> 5; sz <= (bz + 1) >> 5; sz++) {
                            touch(engine, sx, sy, sz);
                        }
                    }
                    for (int s = (by + 1) >> 5; s <= (walkTop >> 5); s++) {
                        touch(engine, bx >> 5, s, bz >> 5);
                    }
                }
            } catch (Throwable ignored) {
                //A failed warm leaves some sections cold; the backstop re-queues and re-warms
            } finally {
                try {
                    engine.releaseRef();
                } catch (RuntimeException ignored) {
                }
                synchronized (WARMING) {
                    WARMING.remove(beaconPos);
                }
                //The event that sets the retry cadence: re-solve on the next frame's drain, while
                //everything just read is still hot. Queued even on failure - a spurious solve of a
                //stale pos terminates in one budgeted pass, a missing re-queue costs seconds.
                BeaconBeamTracker.queueDirty(beaconPos);
            }
        });
    }

    private static void touch(WorldEngine engine, int sx, int sy, int sz) {
        me.cortex.voxy.commonImpl.PerfStats.beaconSolveCacheMiss.increment();
        var section = engine.acquireIfExists(0, sx, sy, sz);
        if (section != null) {
            section.release();
        }
    }
}
