package me.cortex.voxy.commonImpl.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.Logger;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

//Feeds the client the parent-world light under each tracked ship. Sable lights a distant hull from a
//single sky-light scalar sampled out of the parent chunks (MixinClientSubLevelFinalizeLighting); the
//chunks under a far ship sit outside every player's tracking view, so vanilla never sends them and
//the hull would render black.
//
//Send discipline, per (player, ship) pair, per chunk: a chunk the player's tracking view owns is
//vanilla's business and counts as never-sent here (leaving the view triggers ForgetLevelChunk, which
//drops the client's chunk AND its light). Inside the client's storage ring a full chunk-with-light
//packet sticks; outside it vanilla discards the chunk body on arrival and keeps only the light
//layers - which are also all the sky-light scalar can use out there - so those chunks get a light
//packet and nothing else. Each chunk is sent once; re-sends happen only when the chunk's own blocks
//or light actually changed (the ChunkHolder hook feeding markDirty), batched on a slow,
//per-key-staggered cadence. The scalar consumer samples five points; that cadence is far below
//perception.
public final class SableParentChunkLightSync {
    private static final long REFRESH_INTERVAL_TICKS = 300L;
    private static final int PARENT_CHUNK_PADDING = 1;

    //Keyed on the ServerPlayer INSTANCE, not its UUID: respawn creates a new ServerPlayer while the
    //UUID survives, and the respawned client just wiped its level - instance identity makes the stale
    //key fall out of the activeKeys sweep on its own, and the new instance full-sends from scratch.
    //Entity does not override equals(), so the record's Objects.equals is identity.
    private record TrackingKey(ServerPlayer player, UUID subLevelId) {}

    private static final class TrackingState {
        //Chunks the client holds a full copy of (sent while inside its storage ring)
        final LongOpenHashSet fullSent = new LongOpenHashSet();
        //Chunks that only ever got a light packet (outside the storage ring)
        final LongOpenHashSet lightSent = new LongOpenHashSet();
        long lastRefreshGameTime;
        long nextRefreshTick;
    }

    private static final Map<ServerLevel, Map<TrackingKey, TrackingState>> STATE = new WeakHashMap<>();
    //chunkLong -> gameTime of the last block or light change, fed by the ChunkHolder hook. Bounded by
    //the sable ticket footprint (markDirty filters on it) and pruned on the refresh cadence.
    private static final Map<ServerLevel, Long2LongOpenHashMap> DIRTY = new WeakHashMap<>();

    private static boolean unavailable;

    private SableParentChunkLightSync() {
    }

    //Called from the ChunkHolder block/light change hooks, server thread. Those fire for every loaded
    //chunk in the level, so the first two gates are what keep ordinary play untaxed: one static read,
    //then membership in the sable ticket footprint - the only chunks this class ever sends.
    public static void markDirty(ServerLevel level, ChunkPos pos) {
        if (unavailable) {
            return;
        }
        long chunk = pos.toLong();
        if (!SableLodChunkManager.isActiveLodChunk(level, chunk)) {
            return;
        }
        DIRTY.computeIfAbsent(level, ignored -> new Long2LongOpenHashMap()).put(chunk, level.getGameTime());
    }

    public static void onLevelClosed(ServerLevel level) {
        STATE.remove(level);
        DIRTY.remove(level);
    }

    public static void tick(ServerLevel level) {
        if (unavailable) {
            return;
        }
        if (level.players().isEmpty()) {
            //Nothing to send, and keys left behind would hold dead ServerPlayer references
            STATE.remove(level);
            DIRTY.remove(level);
            return;
        }

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                STATE.remove(level);
                return;
            }

            long gameTime = level.getGameTime();
            Map<TrackingKey, TrackingState> states = STATE.computeIfAbsent(level, ignored -> new HashMap<>());
            Long2LongOpenHashMap dirty = DIRTY.get(level);
            Set<TrackingKey> activeKeys = new HashSet<>();

            //getPlayerByUUID is a linear scan; resolve the level's players once per tick
            Map<UUID, ServerPlayer> playersById = new HashMap<>();
            for (var player : level.players()) {
                playersById.put(player.getUUID(), player);
            }

            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (subLevel.isRemoved() || subLevel.getTrackingPlayers().isEmpty()) {
                    continue;
                }

                UUID subLevelId = subLevel.getUniqueId();
                if (subLevelId == null) {
                    continue;
                }

                for (UUID playerId : subLevel.getTrackingPlayers()) {
                    ServerPlayer player = playersById.get(playerId);
                    if (player == null || player.level() != level) {
                        continue;
                    }

                    TrackingKey key = new TrackingKey(player, subLevelId);
                    activeKeys.add(key);
                    TrackingState state = states.get(key);
                    if (state == null) {
                        state = new TrackingState();
                        state.lastRefreshGameTime = gameTime;
                        //Stagger by key so pairs created together stop bursting in phase
                        state.nextRefreshTick = gameTime + Math.floorMod(key.hashCode(), (int) REFRESH_INTERVAL_TICKS);
                        states.put(key, state);
                    }

                    syncFootprint(level, player, state, subLevel.boundingBox(), subLevel.logicalPose().position());

                    if (gameTime >= state.nextRefreshTick) {
                        refreshDirty(level, player, state, dirty);
                        state.lastRefreshGameTime = gameTime;
                        state.nextRefreshTick = gameTime + REFRESH_INTERVAL_TICKS;
                    }
                }
            }

            states.keySet().removeIf(key -> !activeKeys.contains(key));
            if (states.isEmpty()) {
                STATE.remove(level);
            }
            pruneDirty(level, dirty, gameTime);
        } catch (NoClassDefFoundError e) {
            unavailable = true;
            STATE.clear();
            DIRTY.clear();
        } catch (RuntimeException | LinkageError e) {
            Logger.error("Disabling Voxy Sable parent chunk light sync after direct access failed", e);
            unavailable = true;
            STATE.clear();
            DIRTY.clear();
        }
    }

    //Walk the footprint every tick, but send only transitions: a chunk new to the footprint (ship
    //moved), newly outside the tracking view (player walked away, the client forgot it), or newly
    //outside the storage ring. Steady state sends nothing and costs the containment tests alone.
    private static void syncFootprint(ServerLevel level, ServerPlayer player, TrackingState state,
                                      BoundingBox3dc bounds, Vector3dc position) {
        ChunkTrackingView trackingView = player.getChunkTrackingView();
        if (trackingView == null) {
            trackingView = ChunkTrackingView.EMPTY;
        }

        int minChunkX;
        int maxChunkX;
        int minChunkZ;
        int maxChunkZ;
        if (bounds == null) {
            int chunkX = Mth.floor(position.x()) >> 4;
            int chunkZ = Mth.floor(position.z()) >> 4;
            minChunkX = chunkX - PARENT_CHUNK_PADDING;
            maxChunkX = chunkX + PARENT_CHUNK_PADDING;
            minChunkZ = chunkZ - PARENT_CHUNK_PADDING;
            maxChunkZ = chunkZ + PARENT_CHUNK_PADDING;
        } else {
            minChunkX = (Mth.floor(bounds.minX()) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkX = (Mth.floor(bounds.maxX()) >> 4) + PARENT_CHUNK_PADDING;
            minChunkZ = (Mth.floor(bounds.minZ()) >> 4) - PARENT_CHUNK_PADDING;
            maxChunkZ = (Mth.floor(bounds.maxZ()) >> 4) + PARENT_CHUNK_PADDING;
        }

        //The client stores chunk bodies only within this Chebyshev radius of its view center; a full
        //packet outside it is discarded on arrival, so out there light is all that can stick
        int ringRadius = Math.max(2, level.getServer().getPlayerList().getViewDistance()) + 3;
        ChunkPos ringCenter = trackingView instanceof ChunkTrackingView.Positioned positioned
                ? positioned.center() : player.chunkPosition();

        LevelLightEngine lightEngine = level.getLightEngine();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                long chunkLong = ChunkPos.asLong(chunkX, chunkZ);
                if (trackingView.contains(chunkX, chunkZ)) {
                    //Vanilla owns it; when it later leaves the view the server sends ForgetLevelChunk
                    //and the client drops chunk and light both, so it counts as never-sent again
                    state.fullSent.remove(chunkLong);
                    state.lightSent.remove(chunkLong);
                    continue;
                }

                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    //Not loaded yet - retried next tick, per chunk, without re-sending its neighbours
                    continue;
                }

                boolean inRing = Math.max(Math.abs(chunkX - ringCenter.x), Math.abs(chunkZ - ringCenter.z)) <= ringRadius;
                if (inRing) {
                    if (!state.fullSent.contains(chunkLong)) {
                        player.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, lightEngine, null, null));
                        state.fullSent.add(chunkLong);
                        state.lightSent.remove(chunkLong);
                    }
                } else {
                    //Ring recentered away: the client already discarded the body it once accepted
                    state.fullSent.remove(chunkLong);
                    if (!state.lightSent.contains(chunkLong)) {
                        player.connection.send(new ClientboundLightUpdatePacket(new ChunkPos(chunkX, chunkZ), lightEngine, null, null));
                        state.lightSent.add(chunkLong);
                    }
                }
            }
        }
    }

    //Re-send only what changed since this key's last refresh, and only as light: nothing client-side
    //reads the block copy after the first send except the chunk-presence gate, which that send
    //already satisfied. Blocks in the storage annulus go stale; the sky-light scalar does not.
    private static void refreshDirty(ServerLevel level, ServerPlayer player, TrackingState state,
                                     Long2LongOpenHashMap dirty) {
        if (dirty == null || dirty.isEmpty()) {
            return;
        }
        LevelLightEngine lightEngine = level.getLightEngine();
        refreshDirtySet(player, state.fullSent, dirty, state.lastRefreshGameTime, lightEngine);
        refreshDirtySet(player, state.lightSent, dirty, state.lastRefreshGameTime, lightEngine);
    }

    private static void refreshDirtySet(ServerPlayer player, LongOpenHashSet sent,
                                        Long2LongOpenHashMap dirty, long since, LevelLightEngine lightEngine) {
        LongIterator iterator = sent.iterator();
        while (iterator.hasNext()) {
            long chunkLong = iterator.nextLong();
            if (dirty.getOrDefault(chunkLong, Long.MIN_VALUE) >= since) {
                player.connection.send(new ClientboundLightUpdatePacket(new ChunkPos(chunkLong), lightEngine, null, null));
            }
        }
    }

    //Entries older than two refresh intervals cannot matter any more: every live key refreshed since
    //then, and a new key starts with empty sent-sets, so it full-sends regardless of pruned dirt
    private static void pruneDirty(ServerLevel level, Long2LongOpenHashMap dirty, long gameTime) {
        if (dirty == null || dirty.isEmpty() || Math.floorMod(gameTime, REFRESH_INTERVAL_TICKS) != 0) {
            return;
        }
        long cutoff = gameTime - 2 * REFRESH_INTERVAL_TICKS;
        dirty.long2LongEntrySet().removeIf(entry -> entry.getLongValue() < cutoff);
        if (dirty.isEmpty()) {
            DIRTY.remove(level);
        }
    }
}
