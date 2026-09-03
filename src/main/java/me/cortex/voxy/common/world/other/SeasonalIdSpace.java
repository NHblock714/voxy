package me.cortex.voxy.common.world.other;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.ConcurrentHashMap;

//Render-only block-id space layered on top of Mapper's real ids. Real ids grow up from 0, legacy
//ingest-time snow is the complement (0xFFFFF - id) growing down from the top, seasonal model ids
//grow up from 0x80000, and 0xFFFFF is the frozen-water sentinel (air is never encoded, so its
//complement slot is free). None of these are ever persisted: stored sections only ever contain
//real ids and legacy complements from old archives.
//
//Lives in common, not client: Mapper's decode path has to resolve legacy complement ids from old
//archives even on a dedicated server, and a client-class reference here would be a dist crash the
//first time such an id is touched.
public final class SeasonalIdSpace {
    public static final int MAX_BLOCK_ID = 0xFFFFF;
    public static final int VIRTUAL_ICE_ID = MAX_BLOCK_ID;
    public static final int FIRST_SEASONAL_ID = 0x80000;

    private SeasonalIdSpace() {}

    public record Entry(int originalBlockId, ResourceLocation modelId, boolean snowy) { }

    //Concurrent maps so the mesh-worker hit paths (getOrCreate on every seasonal-model voxel of
    //a canopy section, decode on every legacy complement id) never take the class lock; the lock
    //only serialises id allocation
    private static final ConcurrentHashMap<Entry, Integer> KEY_TO_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Entry> ID_TO_ENTRY = new ConcurrentHashMap<>();
    private static int nextId = FIRST_SEASONAL_ID;

    //Lazy: StateEntry's constructor probes the block, which needs registries up
    private static Mapper.StateEntry virtualIceEntry;

    public static synchronized Mapper.StateEntry virtualIceEntry() {
        if (virtualIceEntry == null) {
            virtualIceEntry = new Mapper.StateEntry(VIRTUAL_ICE_ID, Blocks.ICE.defaultBlockState());
        }
        return virtualIceEntry;
    }

    //Ids are handed out monotonically and never recycled within a session: they sit inside built
    //mesh data and ModelFactory's idMappings, which only ever reset with the renderer. The model
    //is referenced by stable ResourceLocation, so a resource reload does not invalidate entries.
    //Refuses to grow into the top-down complement region, and refuses to hand out anything once
    //the real-id region has grown into the seasonal range (decode would then read seasonal ids
    //as real ones).
    public static int getOrCreate(Mapper mapper, int originalBlockId,
                                  ResourceLocation modelId, boolean snowy) {
        var key = new Entry(originalBlockId, modelId, snowy);
        Integer existing = KEY_TO_ID.get(key);
        if (existing != null) return existing;
        synchronized (SeasonalIdSpace.class) {
            existing = KEY_TO_ID.get(key);
            if (existing != null) return existing;
            int count = mapper.getBlockStateCount();
            if (count >= FIRST_SEASONAL_ID) return originalBlockId;
            if (nextId >= MAX_BLOCK_ID - count) return originalBlockId;
            int id = nextId++;
            //Entry before key: a decoder that can see the id must be able to resolve it
            ID_TO_ENTRY.put(id, key);
            KEY_TO_ID.put(key, id);
            return id;
        }
    }

    public static Entry get(int blockId) {
        return ID_TO_ENTRY.get(blockId);
    }

    //Real id behind any render-only encoding; render-only ids that do not resolve come back
    //unchanged (the caller's array access then fails the same way it does for any unknown id).
    //Order matters: the ice sentinel first (its complement is 0 = air), then seasonal ids, then
    //the legacy complement range.
    public static int decode(Mapper mapper, int blockId) {
        int count = mapper.getBlockStateCount();
        if (blockId < count) return blockId;
        if (blockId == VIRTUAL_ICE_ID) return blockId;
        var seasonal = get(blockId);
        if (seasonal != null) return seasonal.originalBlockId();
        int complement = MAX_BLOCK_ID - blockId;
        if (complement >= 0 && complement < count) return complement;
        return blockId;
    }

    public static boolean resolvesToState(Mapper mapper, int blockId) {
        return blockId == VIRTUAL_ICE_ID || decode(mapper, blockId) < mapper.getBlockStateCount();
    }
}
