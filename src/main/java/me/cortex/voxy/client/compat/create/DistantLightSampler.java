package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.Level;

//Samples light from voxy's own voxel store - every LOD voxel carries sky+block light, making the
//store a ready-made light cache for places far outside loaded chunks. Samples the voxel above the
//given position (the air that carries the ambient light), walking up mip levels until data exists.
public final class DistantLightSampler {
    public static final int FALLBACK = 15; //plain full skylight, block light 0

    private DistantLightSampler() {}

    //Returns packed voxel light (block << 4 | sky), or FALLBACK when no data is stored
    public static int sample(Level level, int x, int y, int z) {
        try {
            var engine = WorldIdentifier.ofEngineNullable(level);
            if (engine == null) {
                return FALLBACK;
            }
            int ay = y + 1;
            for (int lvl = 0; lvl <= 4; lvl++) {
                var section = engine.acquireIfExists(lvl, x >> (5 + lvl), ay >> (5 + lvl), z >> (5 + lvl));
                if (section == null) {
                    continue;
                }
                try {
                    int lx = (x >> lvl) & 31, ly = (ay >> lvl) & 31, lz = (z >> lvl) & 31;
                    //get() never materialises. Note the uniform value may legitimately be 0, which the
                    //check below treats as "no data, try a coarser level" - so it must be returned as-is.
                    long voxel = section.get(lx | (lz << 5) | (ly << 10));
                    if (voxel == 0) {
                        continue; //void, try a coarser level
                    }
                    return Mapper.getLightId(voxel);
                } finally {
                    section.release();
                }
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK;
    }

    //Cache-only variant for render and client-tick callers: a cold sample() is up to five synchronous
    //storage reads on the calling thread, which those threads must never pay. A miss returns the
    //fallback and schedules a background warm-up, so the caller's next periodic re-sample hits - the
    //consumers all refresh on second-scale timers, and one second of fallback light on a cold cache is
    //below notice.
    public static int samplePeek(Level level, int x, int y, int z) {
        try {
            var engine = WorldIdentifier.ofEngineNullable(level);
            if (engine == null) {
                return FALLBACK;
            }
            int ay = y + 1;
            for (int lvl = 0; lvl <= 4; lvl++) {
                var section = engine.acquireIfCached(lvl, x >> (5 + lvl), ay >> (5 + lvl), z >> (5 + lvl));
                if (section == null) {
                    continue;
                }
                try {
                    int lx = (x >> lvl) & 31, ly = (ay >> lvl) & 31, lz = (z >> lvl) & 31;
                    long voxel = section.get(lx | (lz << 5) | (ly << 10));
                    if (voxel == 0) {
                        continue;
                    }
                    return Mapper.getLightId(voxel);
                } finally {
                    section.release();
                }
            }
            warmUp(engine, x >> 5, ay >> 5, z >> 5);
        } catch (Throwable ignored) {
        }
        return FALLBACK;
    }

    //One background thread pulls missed sections into the cache. Deduplicated so a stalled load cannot
    //stack requests, ref-held so the engine cannot be freed under the load.
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet WARMING = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static final java.util.concurrent.ExecutorService WARM_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "Voxy distant light warmup");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private static void warmUp(me.cortex.voxy.common.world.WorldEngine engine, int sx, int sy, int sz) {
        long key = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(0, sx, sy, sz);
        synchronized (WARMING) {
            if (!WARMING.add(key)) {
                return;
            }
        }
        try {
            engine.acquireRef();
        } catch (RuntimeException e) {
            //World tearing down - nothing to warm
            synchronized (WARMING) {
                WARMING.remove(key);
            }
            return;
        }
        WARM_POOL.execute(() -> {
            try {
                if (engine.isLive()) {
                    var section = engine.acquireIfExists(key);
                    if (section != null) {
                        section.release();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    engine.releaseRef();
                } catch (RuntimeException ignored) {
                }
                synchronized (WARMING) {
                    WARMING.remove(key);
                }
            }
        });
    }

    public static int sky(int packed) {
        return packed & 0xF;
    }

    public static int block(int packed) {
        return (packed >> 4) & 0xF;
    }
}
