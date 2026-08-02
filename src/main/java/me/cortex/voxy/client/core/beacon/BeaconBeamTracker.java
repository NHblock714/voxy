package me.cortex.voxy.client.core.beacon;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.core.BlockPos;

//Which beacons a section change can affect. A beam is a pure function of the level-0 voxels in its
//column plus index membership, so between those two signals - the engine's section-dirty callback and
//the index's membership diff - a beam that never gets marked here never needs re-solving.
//
//onSectionDirty runs inside markDirty on the ingest workers: it may take one volatile read, one map
//probe and a few compares, nothing more, or it backpressures ingest. The column map is therefore an
//immutable snapshot swapped on membership change (rare), and only the small dirty/removed sets take a
//lock.
public final class BeaconBeamTracker {
    //32-block column key of a level-0 section
    private static long columnKey(int sx, int sz) {
        return ((long) sx << 32) | (sz & 0xFFFFFFFFL);
    }

    //column -> packed beacon BlockPos longs. A beacon registers in every column its 3x3 base touches,
    //so a base edge re-ingested from the neighbouring column still lands on it.
    private static volatile Long2ObjectMap<long[]> columnToBeacons = Long2ObjectMaps.emptyMap();

    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();
    private static final LongOpenHashSet REMOVED = new LongOpenHashSet();
    private static final Object MUTATION_LOCK = new Object();

    private BeaconBeamTracker() {
    }

    //Rebuilds the column map from the engine's index and hooks its membership diff. Render thread,
    //once per engine.
    public static void bind(WorldEngine engine) {
        synchronized (MUTATION_LOCK) {
            DIRTY.clear();
            REMOVED.clear();
            var fresh = new Long2ObjectOpenHashMap<long[]>();
            engine.getBeaconIndex().forEach((x, y, z) -> registerColumns(fresh, x, y, z));
            columnToBeacons = fresh;
        }
        engine.getBeaconIndex().setListener(new me.cortex.voxy.common.world.other.BeaconIndex.ChangeListener() {
            @Override
            public void onBeaconAdded(int x, int y, int z) {
                synchronized (MUTATION_LOCK) {
                    var next = new Long2ObjectOpenHashMap<>(columnToBeacons);
                    registerColumns(next, x, y, z);
                    columnToBeacons = next;
                    DIRTY.add(BlockPos.asLong(x, y, z));
                }
            }

            @Override
            public void onBeaconRemoved(int x, int y, int z) {
                synchronized (MUTATION_LOCK) {
                    var next = new Long2ObjectOpenHashMap<>(columnToBeacons);
                    unregisterColumns(next, x, y, z);
                    columnToBeacons = next;
                    long pos = BlockPos.asLong(x, y, z);
                    DIRTY.remove(pos);
                    REMOVED.add(pos);
                }
            }
        });
    }

    public static void reset(WorldEngine engine) {
        if (engine != null) {
            engine.getBeaconIndex().setListener(null);
        }
        synchronized (MUTATION_LOCK) {
            DIRTY.clear();
            REMOVED.clear();
            columnToBeacons = Long2ObjectMaps.emptyMap();
        }
    }

    //The ingest-worker filter. One volatile read and one probe for the overwhelmingly common case of a
    //section nowhere near a beacon.
    public static void onSectionDirty(WorldSection section, int flags) {
        if (section.lvl != 0 || (flags & WorldEngine.UPDATE_TYPE_BLOCK_BIT) == 0) {
            return;
        }
        long[] beacons = columnToBeacons.get(columnKey(section.x, section.z));
        if (beacons == null) {
            return;
        }
        for (long pos : beacons) {
            int by = BlockPos.getY(pos);
            //The beam reads from one below the beacon (its base) up to the scan roof
            int minSection = (by - 1) >> 5;
            int maxSection = (by + BeaconBeamSolver.MAX_SCAN_HEIGHT) >> 5;
            if (section.y >= minSection && section.y <= maxSection) {
                synchronized (MUTATION_LOCK) {
                    DIRTY.add(pos);
                }
            }
        }
    }

    //Queued from anywhere a beam needs a re-solve without a voxel change (first sight, mapper retry)
    public static void queueDirty(long pos) {
        synchronized (MUTATION_LOCK) {
            DIRTY.add(pos);
        }
    }

    /** Moves the pending sets into the caller's lists. Render thread. */
    public static void drain(LongArrayList dirtyOut, LongArrayList removedOut) {
        synchronized (MUTATION_LOCK) {
            if (!DIRTY.isEmpty()) {
                dirtyOut.addAll(DIRTY);
                DIRTY.clear();
            }
            if (!REMOVED.isEmpty()) {
                removedOut.addAll(REMOVED);
                REMOVED.clear();
            }
        }
    }

    private static void registerColumns(Long2ObjectOpenHashMap<long[]> map, int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        forEachBaseColumn(x, z, key -> {
            long[] existing = map.get(key);
            if (existing == null) {
                map.put(key, new long[]{pos});
                return;
            }
            for (long p : existing) {
                if (p == pos) {
                    return;
                }
            }
            long[] grown = java.util.Arrays.copyOf(existing, existing.length + 1);
            grown[existing.length] = pos;
            map.put(key, grown);
        });
    }

    private static void unregisterColumns(Long2ObjectOpenHashMap<long[]> map, int x, int y, int z) {
        long pos = BlockPos.asLong(x, y, z);
        forEachBaseColumn(x, z, key -> {
            long[] existing = map.get(key);
            if (existing == null) {
                return;
            }
            if (existing.length == 1) {
                if (existing[0] == pos) {
                    map.remove(key);
                }
                return;
            }
            int at = -1;
            for (int i = 0; i < existing.length; i++) {
                if (existing[i] == pos) {
                    at = i;
                    break;
                }
            }
            if (at == -1) {
                return;
            }
            long[] shrunk = new long[existing.length - 1];
            System.arraycopy(existing, 0, shrunk, 0, at);
            System.arraycopy(existing, at + 1, shrunk, at, existing.length - 1 - at);
            map.put(key, shrunk);
        });
    }

    private interface ColumnConsumer {
        void accept(long columnKey);
    }

    private static void forEachBaseColumn(int x, int z, ColumnConsumer consumer) {
        int minCx = (x - 1) >> 5, maxCx = (x + 1) >> 5;
        int minCz = (z - 1) >> 5, maxCz = (z + 1) >> 5;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                consumer.accept(columnKey(cx, cz));
            }
        }
    }
}
