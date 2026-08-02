package me.cortex.voxy.client.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

//Deferred re-ingest of the vanilla sections under a contraption event. The server broadcasts the
//block changes one server tick after the packet that announces the event, so ingesting immediately
//would read the pre-event sections; a short delay reads the settled state. Covers what the bulk
//section packet cannot: a section where the machine occupies a single block gets a lone block-update
//packet, and a placement (non-air) has no per-block ingest trigger at all. Bounded drain per tick; a
//section whose chunk is not client-loaded is dropped - no packet means no data, and the stored LOD
//is the leave-behind by design.
public final class SectionReingestQueue {
    private SectionReingestQueue() {}

    private static final int SECTIONS_PER_TICK = 16;

    private record Pending(long sectionPos, long fireTick) {}

    private static final java.util.ArrayDeque<Pending> QUEUE = new java.util.ArrayDeque<>();
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet QUEUED =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private static long clientTick;

    public static void scheduleBox(ClientLevel level, AABB box, int delayTicks) {
        int minX = SectionPos.blockToSectionCoord((int) Math.floor(box.minX));
        int maxX = SectionPos.blockToSectionCoord((int) Math.floor(box.maxX));
        int minY = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord((int) Math.floor(box.minY)));
        int maxY = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord((int) Math.floor(box.maxY)));
        int minZ = SectionPos.blockToSectionCoord((int) Math.floor(box.minZ));
        int maxZ = SectionPos.blockToSectionCoord((int) Math.floor(box.maxZ));
        long fire = clientTick + delayTicks;
        for (int sx = minX; sx <= maxX; sx++) {
            for (int sy = minY; sy <= maxY; sy++) {
                for (int sz = minZ; sz <= maxZ; sz++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    if (QUEUED.add(key)) {
                        QUEUE.addLast(new Pending(key, fire));
                    }
                }
            }
        }
    }

    public static void tick(Minecraft mc) {
        clientTick++;
        var level = mc.level;
        if (level == null) {
            if (!QUEUE.isEmpty()) {
                clear();
            }
            return;
        }
        int done = 0;
        while (done < SECTIONS_PER_TICK) {
            Pending head = QUEUE.peekFirst();
            if (head == null || head.fireTick() > clientTick) {
                return;
            }
            QUEUE.pollFirst();
            QUEUED.remove(head.sectionPos());
            done++;
            reingest(level, head.sectionPos());
        }
    }

    //Same recipe as the per-block trigger in MixinClientLevel: live section reference plus copied
    //light layers, through the shared ingest pipeline.
    private static void reingest(ClientLevel level, long sectionPos) {
        if (me.cortex.voxy.commonImpl.VoxyCommon.getInstance() == null
                || !me.cortex.voxy.client.config.VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var wi = me.cortex.voxy.commonImpl.WorldIdentifier.of(level);
        if (wi == null) {
            return;
        }
        var csp = SectionPos.of(sectionPos);
        var chunk = level.getChunk(csp.x(), csp.z(), ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk levelChunk)) {
            return;
        }
        var section = levelChunk.getSection(level.getSectionIndexFromSectionY(csp.y()));
        var lp = level.getLightEngine();
        var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
        var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
        me.cortex.voxy.common.world.service.VoxelIngestService.rawIngest(wi, levelChunk, section,
                csp.x(), csp.y(), csp.z(), blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
    }

    public static void clear() {
        QUEUE.clear();
        QUEUED.clear();
    }
}
