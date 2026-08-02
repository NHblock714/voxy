package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.sable.SableSubLevelHoldingChunkMapAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;

public final class SableLodChunkManager {
    private static final TicketType<ChunkPos> VOXY_SABLE_LOD_TICKET = TicketType.create("voxy_sable_lod", Comparator.comparingLong(ChunkPos::toLong));
    //Two ticket tiers. Distance 2 resolves to level 31 - ENTITY_TICKING - which is what keeps a
    //distant ship's parent-world contraption entity moving; it goes only on the anchor chunk holding
    //that entity. Everything else the tickets exist for - getChunkNow succeeding for the light sync,
    //the physics gate answering loaded - needs residency, not ticking, and distance 0 (level 33, FULL)
    //provides exactly that. Footprint-wide distance 2 had every chunk under every in-range ship
    //running random ticks and entity AI: crops growing and mobs pathing under scenery 4000 blocks out.
    private static final int ANCHOR_TICKET_DISTANCE = 2;
    private static final int FOOTPRINT_TICKET_DISTANCE = 0;

    private static final Map<ServerLevel, LongSet> activeChunkLoads = new WeakHashMap<>();

    //What the last rebuild saw, folded to one hash. The desired chunk set is a pure function of the
    //range, the player chunk positions, each sub-level's footprint box and anchor, and the holding
    //index - so while none of those move, the rebuild would reproduce the tickets it already placed.
    //The tickets themselves never expire (TicketType.create without timeout), so skipping is safe.
    //A hash miss (collision, or state the hash cannot see, like a holding sub-level appearing inside
    //an already-loaded holding chunk) is bounded by the forced rebuild: at most 2 seconds stale.
    private static final class LevelSignature {
        long stateHash;
        long nextForcedRebuildTick;
    }

    private static final long FORCED_REBUILD_INTERVAL_TICKS = 40L;
    private static final Map<ServerLevel, LevelSignature> SIGNATURES = new WeakHashMap<>();

    private static boolean sableUnavailable;

    private SableLodChunkManager() {
    }

    public static void updateTickets(ServerLevel level, LongSet trackedTickingChunks, LongSet trackedFullChunks, LongSet trackedHoldingChunks) {
        if (sableUnavailable) {
            clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
            return;
        }

        try {
            double horizontalRenderDistanceBlocks = SableContraptionRenderDistance.getRangeBlocks(level);
            if (horizontalRenderDistanceBlocks <= 0.0) {
                clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
                return;
            }

            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
                return;
            }

            if (level.players().isEmpty()) {
                clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
                return;
            }

            double maxHorizontalDistanceSquared = horizontalRenderDistanceBlocks * horizontalRenderDistanceBlocks;

            LevelSignature signature = SIGNATURES.computeIfAbsent(level, ignored -> new LevelSignature());
            long gameTime = level.getGameTime();
            long stateHash = computeStateHash(level, container, horizontalRenderDistanceBlocks, maxHorizontalDistanceSquared);
            if (stateHash == signature.stateHash && gameTime < signature.nextForcedRebuildTick) {
                return;
            }
            signature.stateHash = stateHash;
            signature.nextForcedRebuildTick = gameTime + FORCED_REBUILD_INTERVAL_TICKS;

            LongSet desiredTicking = new LongOpenHashSet();
            LongSet desiredFull = new LongOpenHashSet();
            LongSet desiredHoldingChunks = new LongOpenHashSet();

            //Vanilla only ticks entities and block entities within the simulation distance; between it
            //and the view distance a player can WATCH machinery that vanilla leaves frozen. A ship
            //within that band is scenery someone is looking at, so it keeps the full-footprint
            //ticking; only ships past watching range pay the anchor-only tier.
            double nearBlocks = Math.max(2, level.getServer().getPlayerList().getViewDistance()) * 16.0 + 32.0;
            double nearSq = nearBlocks * nearBlocks;

            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel.isRemoved()) {
                    continue;
                }

                BoundingBox3dc bounds = subLevel.boundingBox();
                if (bounds == null || !isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    continue;
                }

                if (isWithinHorizontalDistance(level, bounds, nearSq)) {
                    addChunkBounds(level, bounds, desiredTicking, maxHorizontalDistanceSquared);
                    continue;
                }
                addChunkBounds(level, bounds, desiredFull, maxHorizontalDistanceSquared);
                //The parent-world contraption entity lives at the logical pose; that one chunk keeps
                //entity ticking so the ship can still move. Applied to parked ships too - an entity
                //frozen at FULL can never initiate motion, so demoting by velocity would be a trap.
                var anchor = subLevel.logicalPose().position();
                desiredTicking.add(ChunkPos.asLong(Mth.floor(anchor.x()) >> 4, Mth.floor(anchor.z()) >> 4));
            }
            desiredFull.removeAll(desiredTicking);

            updateHoldingChunkLoads(level, container.getHoldingChunkMap(), desiredFull, desiredHoldingChunks, trackedHoldingChunks, maxHorizontalDistanceSquared);
            //Holding-derived footprint chunks may overlap a live anchor; the anchor tier wins
            desiredFull.removeAll(desiredTicking);

            //Adds before removes: a chunk migrating tiers (the ship moved a chunk) briefly holds both
            //tickets rather than neither, and removeRegionTicket is always called with the distance
            //its ticket was added at - ticket identity includes the level.
            addMissingTickets(level, trackedTickingChunks, desiredTicking, ANCHOR_TICKET_DISTANCE);
            addMissingTickets(level, trackedFullChunks, desiredFull, FOOTPRINT_TICKET_DISTANCE);
            removeStaleTickets(level, trackedTickingChunks, desiredTicking, ANCHOR_TICKET_DISTANCE);
            removeStaleTickets(level, trackedFullChunks, desiredFull, FOOTPRINT_TICKET_DISTANCE);

            LongSet active = new LongOpenHashSet(desiredFull);
            active.addAll(desiredTicking);
            activeChunkLoads.put(level, active);
        } catch (NoClassDefFoundError e) {
            sableUnavailable = true;
            clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable LOD compatibility after direct access failed", e);
            sableUnavailable = true;
            clearTickets(level, trackedTickingChunks, trackedFullChunks, trackedHoldingChunks);
        }
    }

    //Folds every input the desired sets depend on: range, player chunk positions, each live
    //sub-level's identity, in-range verdict, chunk box and anchor chunk, and the holding index
    //revision plus loaded-count. Order-sensitive mixing; iteration order changes force at worst one
    //spurious rebuild, never a missed one beyond the forced cap.
    private static long computeStateHash(ServerLevel level, ServerSubLevelContainer container,
                                         double rangeBlocks, double maxHorizontalDistanceSquared) {
        long h = Double.doubleToLongBits(rangeBlocks);
        for (var player : level.players()) {
            h = h * 0x9E3779B97F4A7C15L + ChunkPos.asLong(Mth.floor(player.getX()) >> 4, Mth.floor(player.getZ()) >> 4);
        }
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            var id = subLevel.getUniqueId();
            h = h * 0x9E3779B97F4A7C15L + (id == null ? 0 : id.getLeastSignificantBits() ^ id.getMostSignificantBits());
            BoundingBox3dc bounds = subLevel.boundingBox();
            if (bounds == null || !isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                h = h * 31 + 1;
                continue;
            }
            h = h * 31 + ChunkPos.asLong(Mth.floor(bounds.minX()) >> 4, Mth.floor(bounds.minZ()) >> 4);
            h = h * 31 + ChunkPos.asLong(Mth.floor(bounds.maxX()) >> 4, Mth.floor(bounds.maxZ()) >> 4);
            var anchor = subLevel.logicalPose().position();
            h = h * 31 + ChunkPos.asLong(Mth.floor(anchor.x()) >> 4, Mth.floor(anchor.z()) >> 4);
        }
        h = h * 31 + SableHoldingChunkIndexSavedData.getOrLoad(level).revision();
        var holdingMap = container.getHoldingChunkMap();
        if (holdingMap != null) {
            var loaded = ((SableSubLevelHoldingChunkMapAccessor) holdingMap).voxy$getLoadedHoldingChunks();
            h = h * 31 + (loaded == null ? 0 : loaded.size());
        }
        return h;
    }

    public static void clearTickets(ServerLevel level, LongSet trackedTickingChunks, LongSet trackedFullChunks, LongSet trackedHoldingChunks) {
        activeChunkLoads.remove(level);
        SIGNATURES.remove(level);
        clearTicketSet(level, trackedTickingChunks, ANCHOR_TICKET_DISTANCE);
        clearTicketSet(level, trackedFullChunks, FOOTPRINT_TICKET_DISTANCE);
        clearHoldingChunkLoads(level, trackedHoldingChunks);
    }

    private static void clearTicketSet(ServerLevel level, LongSet trackedChunks, int ticketDistance) {
        if (trackedChunks.isEmpty()) {
            return;
        }

        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunk);
            level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, ticketDistance, chunkPos);
            iterator.remove();
        }
    }

    //Membership in the ticketed footprint, for the light sync's dirty hook - the only chunks it ever
    //sent are the ones this manager keeps loaded. Server thread, same as every other caller here.
    public static boolean isActiveLodChunk(ServerLevel level, long chunkLong) {
        LongSet activeChunks = activeChunkLoads.get(level);
        return activeChunks != null && activeChunks.contains(chunkLong);
    }

    public static boolean shouldTreatChunkAsLoaded(ServerLevel level, int chunkX, int chunkZ) {
        if (sableUnavailable) {
            return false;
        }

        double horizontalRenderDistanceBlocks = SableContraptionRenderDistance.getRangeBlocks(level);
        if (horizontalRenderDistanceBlocks <= 0.0) {
            return false;
        }

        long chunk = ChunkPos.asLong(chunkX, chunkZ);
        LongSet activeChunks = activeChunkLoads.get(level);
        if (activeChunks != null && activeChunks.contains(chunk)) {
            return true;
        }

        return isChunkWithinHorizontalDistance(level, new ChunkPos(chunkX, chunkZ), horizontalRenderDistanceBlocks * horizontalRenderDistanceBlocks);
    }

    public static boolean isSubLevelAlreadyActive(ServerLevel level, SubLevelData data) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }

        SubLevel byUuid = container.getSubLevel(data.uuid());
        if (byUuid != null && !byUuid.isRemoved()) {
            return true;
        }

        CompoundTag fullTag = data.fullTag();
        if (fullTag == null || !fullTag.contains("plot")) {
            return false;
        }

        CompoundTag plotTag = fullTag.getCompound("plot");
        if (!plotTag.contains("plot_x") || !plotTag.contains("plot_z")) {
            return false;
        }

        SubLevel byPlot = container.getSubLevel(plotTag.getInt("plot_x"), plotTag.getInt("plot_z"));
        return byPlot != null && !byPlot.isRemoved();
    }

    private static void updateHoldingChunkLoads(
            ServerLevel level,
            SubLevelHoldingChunkMap holdingChunkMap,
            LongSet desiredChunks,
            LongSet desiredHoldingChunks,
            LongSet trackedHoldingChunks,
            double maxHorizontalDistanceSquared) {
        if (holdingChunkMap == null) {
            trackedHoldingChunks.clear();
            return;
        }

        LongSet knownHoldingChunks = getKnownHoldingChunks(level, holdingChunkMap);
        Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$getLoadedHoldingChunks();
        LongIterator iterator = knownHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            ChunkPos chunkPos = new ChunkPos(chunk);
            if (!isChunkWithinHorizontalDistance(level, chunkPos, maxHorizontalDistanceSquared)) {
                continue;
            }

            desiredHoldingChunks.add(chunk);
            trackedHoldingChunks.add(chunk);
            holdingChunkMap.updateChunkStatus(chunkPos, true);

            SubLevelHoldingChunk holdingChunk = loadedHoldingChunks == null ? null : loadedHoldingChunks.get(chunk);
            if (holdingChunk == null) {
                continue;
            }

            for (HoldingSubLevel holdingSubLevel : holdingChunk.getLoadedHoldingSubLevels()) {
                BoundingBox3dc bounds = holdingSubLevel.data().bounds();
                if (bounds != null && isWithinHorizontalDistance(level, bounds, maxHorizontalDistanceSquared)) {
                    addChunkBounds(level, bounds, desiredChunks, maxHorizontalDistanceSquared);
                }
            }
        }

        removeStaleHoldingChunkLoads(level, holdingChunkMap, trackedHoldingChunks, desiredHoldingChunks);
    }

    private static LongSet getKnownHoldingChunks(ServerLevel level, SubLevelHoldingChunkMap holdingChunkMap) {
        LongSet chunks = SableHoldingChunkIndexSavedData.getOrLoad(level).copyChunks();

        Long2ObjectMap<SubLevelHoldingChunk> loadedHoldingChunks = ((SableSubLevelHoldingChunkMapAccessor) holdingChunkMap).voxy$getLoadedHoldingChunks();
        if (loadedHoldingChunks != null) {
            chunks.addAll(loadedHoldingChunks.keySet());
        }

        return chunks;
    }

    private static void removeStaleHoldingChunkLoads(ServerLevel level, SubLevelHoldingChunkMap holdingChunkMap, LongSet trackedHoldingChunks, LongSet desiredHoldingChunks) {
        LongIterator iterator = trackedHoldingChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (desiredHoldingChunks.contains(chunk)) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(chunk);
            if (PhysicsChunkTicketManager.isChunkLoadedEnough(level, chunkPos.x, chunkPos.z)) {
                continue;
            }

            holdingChunkMap.updateChunkStatus(chunkPos, false);
            iterator.remove();
        }
    }

    private static void clearHoldingChunkLoads(ServerLevel level, LongSet trackedHoldingChunks) {
        if (trackedHoldingChunks.isEmpty()) {
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevelHoldingChunkMap holdingChunkMap = container == null ? null : container.getHoldingChunkMap();
            if (holdingChunkMap != null) {
                LongIterator iterator = trackedHoldingChunks.iterator();
                while (iterator.hasNext()) {
                    ChunkPos chunkPos = new ChunkPos(iterator.nextLong());
                    //Only hand back the chunks we were artificially holding. Reporting one that vanilla
                    //still has loaded as gone makes sable serialise and remove every sub-level standing in
                    //it, and it is only restored on the next FullChunkStatus transition - a ship right next
                    //to the player would disappear until its chunk happens to cycle. Same guard as
                    //removeStaleHoldingChunkLoads.
                    if (PhysicsChunkTicketManager.isChunkLoadedEnough(level, chunkPos.x, chunkPos.z)) {
                        continue;
                    }
                    holdingChunkMap.updateChunkStatus(chunkPos, false);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Logger.warn("Failed to release Sable holding chunk loads", e);
        } finally {
            trackedHoldingChunks.clear();
        }
    }

    private static void addMissingTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks, int ticketDistance) {
        LongIterator iterator = desiredChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (trackedChunks.add(chunk)) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().addRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, ticketDistance, chunkPos);
            }
        }
    }

    private static void removeStaleTickets(ServerLevel level, LongSet trackedChunks, LongSet desiredChunks, int ticketDistance) {
        LongIterator iterator = trackedChunks.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            if (!desiredChunks.contains(chunk)) {
                ChunkPos chunkPos = new ChunkPos(chunk);
                level.getChunkSource().removeRegionTicket(VOXY_SABLE_LOD_TICKET, chunkPos, ticketDistance, chunkPos);
                iterator.remove();
            }
        }
    }

    private static void addChunkBounds(ServerLevel level, BoundingBox3dc bounds, LongSet desiredChunks, double maxHorizontalDistanceSquared) {
        int minChunkX = Mth.floor(bounds.minX()) >> 4;
        int maxChunkX = Mth.floor(bounds.maxX()) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ()) >> 4;
        int maxChunkZ = Mth.floor(bounds.maxZ()) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                if (isChunkWithinHorizontalDistance(level, chunkPos, maxHorizontalDistanceSquared)) {
                    desiredChunks.add(chunkPos.toLong());
                }
            }
        }
    }

    private static boolean isWithinHorizontalDistance(ServerLevel level, BoundingBox3dc bounds, double maxHorizontalDistanceSquared) {
        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static boolean isChunkWithinHorizontalDistance(ServerLevel level, ChunkPos chunkPos, double maxHorizontalDistanceSquared) {
        double minX = chunkPos.getMinBlockX();
        double maxX = chunkPos.getMaxBlockX() + 1.0;
        double minZ = chunkPos.getMinBlockZ();
        double maxZ = chunkPos.getMaxBlockZ() + 1.0;

        for (var player : level.players()) {
            double dx = distanceToRange(player.getX(), minX, maxX);
            double dz = distanceToRange(player.getZ(), minZ, maxZ);
            if ((dx * dx) + (dz * dz) <= maxHorizontalDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    private static double distanceToRange(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }

}
