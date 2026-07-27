package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.config.CommonConfig;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

//Re-decides seasonal snow over LOD that is already in the store.
//
//Snow is not a property of the model - it is decided during ingest, where a snow-covered block is
//written as the complement of its own id (VoxyTool.changeBlockId) and that sentinel goes into the
//voxel itself. Distant LOD is never re-ingested, so it keeps whichever season it was stored under.
//Walking the store is what reaches it: region files exist only in singleplayer, so anything that reads
//those is dead weight on a server.
//
//The write is kept narrow: only the 20-bit block id, and only between a state and its own complement.
//Light, biome and air-ness are never touched, so nothing structural changes and a block-level dirty
//mark is all a section needs afterwards.
public final class SeasonalSnowRefresher {
    private static final int MAX_BLOCK_ID = 1048575;
    private static final long BLOCK_ID_MASK = ((1L << 20) - 1) << 27;
    private static final int BLOCK_ID_SHIFT = 27;
    private static final int SECTION_WIDTH = 32;
    //Sky light the block above must have before snow can settle, matching the ingest-side test
    private static final int MIN_SKY_LIGHT = 9;
    //Verdicts from shouldBeSnowy. UNKNOWN is not "no" - it means leave the voxel untouched.
    private static final int UNKNOWN = -1;
    private static final int NO = 0;
    private static final int YES = 1;
    private static final int SECTIONS_PER_BATCH = 128;
    private static final long BATCH_PAUSE_MILLIS = 10;

    private static Thread worker;
    //Per run, not shared: a cancel must not be able to stop the run that replaces it
    private static volatile Run active;

    public static volatile long runs;
    public static volatile long sectionsScanned;
    public static volatile long sectionsRewritten;
    public static volatile long voxelsFlipped;
    public static volatile String status = "never run";

    private SeasonalSnowRefresher() {
    }

    private static final class Run {
        volatile boolean cancelled;
    }

    //Control flow, not an error - no stack trace, no suppression
    private static final class WalkCancelled extends RuntimeException {
        WalkCancelled() {
            super(null, null, false, false);
        }
    }

    public static synchronized boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    public static synchronized void start(Level level, WorldEngine engine) {
        if (isRunning()) {
            return;
        }
        //Same gate the ingest side answers to - with the feature off, nothing may write sentinel ids
        if (!VoxyTool.isVoxyTest()) {
            return;
        }
        Run run = new Run();
        active = run;
        runs++;
        worker = new Thread(() -> run(level, engine, run), "Voxy seasonal snow refresh");
        worker.setDaemon(true);
        //Below the render and ingest threads: a season change is not worth a frame
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /**
     * Stops any pass and waits for it to actually be gone. Leaving a walker running past world teardown
     * lets it acquire sections out of a freed engine, and the world-quiescence check that gates teardown
     * cannot see a thread that holds no reference.
     */
    public static void cancelAndJoin() {
        Thread t;
        synchronized (SeasonalSnowRefresher.class) {
            Run run = active;
            if (run != null) {
                run.cancelled = true;
            }
            t = worker;
        }
        if (t != null && t.isAlive()) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static String describe() {
        return "status=" + status + " runs=" + runs + " sectionsScanned=" + sectionsScanned
                + " sectionsRewritten=" + sectionsRewritten + " voxelsFlipped=" + voxelsFlipped;
    }

    /** Kicks off a pass without waiting for the season to move, for /voxy debug seasons refresh. */
    public static String startManually() {
        if (!VoxyTool.isVoxyTest()) {
            return "seasonal snow LOD is off (ecliptic_seasons_snow_lod)";
        }
        if (isRunning()) {
            return "already running: " + describe();
        }
        Level level = ClientCon.getUseLevel();
        if (level == null) {
            return "no level";
        }
        //Nullable lookup: the get-or-create variant would stand up a store nothing else references
        WorldEngine engine = WorldIdentifier.ofEngineNullable(level);
        if (engine == null || !engine.isLive()) {
            return "no live world engine";
        }
        start(level, engine);
        return "started - poll /voxy debug seasons for progress";
    }

    private static void run(Level level, WorldEngine engine, Run run) {
        long scanned = 0;
        long rewritten = 0;
        long flipped = 0;
        boolean referenced = false;
        try {
            //Hold the world open for the whole walk. Without this the idle check that precedes teardown
            //samples a moment where nothing is acquired, frees the engine, and the next acquire here
            //throws - taking the flush and close of the store with it.
            engine.acquireRef();
            referenced = true;

            var mapper = engine.getMapper();
            var biomes = new BiomeCache(level, mapper);
            //Read the season config once - these are per-voxel reads otherwise, and they cannot change
            //meaningfully inside one pass
            boolean glowBlocksSnow = CommonConfig.Snow.notSnowyNearGlowingBlock.get();
            int glowLevel = CommonConfig.Snow.notSnowyNearGlowingBlockLevel.getAsInt();
            boolean snowyTree = CommonConfig.Snow.snowyTree.get();

            LongArrayList keys = new LongArrayList();
            //Level 0 only. Ingest decides snow at level 0 and mips upward, and every Mipper path returns
            //one of its children verbatim - so a parent's snow state is whichever child it picked, not an
            //independent decision. Re-deciding at level N against level-N neighbours would disagree with
            //the level 0 underneath it; the parents are followed below instead.
            //Collect first: the storage iterator holds a cursor open, and acquiring or writing sections
            //underneath it is not something the backend promises to survive. The walk holds a world
            //reference throughout, and the shutdown that waits on that reference does not time out, so
            //this loop needs its own way out - hence the throw.
            try {
                engine.storage.iteratePositions(0, key -> {
                    if (run.cancelled || Thread.currentThread().isInterrupted()
                            || (engine.instanceIn != null && !engine.instanceIn.isRunning())) {
                        throw new WalkCancelled();
                    }
                    keys.add(key);
                });
            } catch (WalkCancelled e) {
                status = "cancelled while listing sections";
                return;
            }
            status = "scanning " + keys.size() + " sections";

            for (int i = 0; i < keys.size(); i++) {
                //The held reference makes world teardown wait for this walk, so the walk has to be the
                //one that notices shutdown - otherwise it is the thing the quiescence check waits on
                //forever. Checked per section, so the wait is one section long at worst.
                if (run.cancelled || !engine.isLive() || Thread.currentThread().isInterrupted()
                        || (engine.instanceIn != null && !engine.instanceIn.isRunning())) {
                    break;
                }
                long key = keys.getLong(i);
                WorldSection section = engine.acquireIfExists(key);
                if (section == null) {
                    continue;
                }
                try {
                    int changed = refreshSection(engine, level, mapper, biomes, section,
                            glowBlocksSnow, glowLevel, snowyTree);
                    if (changed > 0) {
                        flipped += changed;
                        rewritten++;
                        engine.markDirty(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0);
                        remipIntoParents(engine, mapper, section, section._rawOrNull());
                    }
                } finally {
                    section.release();
                }
                scanned++;

                if ((i % SECTIONS_PER_BATCH) == SECTIONS_PER_BATCH - 1) {
                    sectionsScanned = scanned;
                    sectionsRewritten = rewritten;
                    voxelsFlipped = flipped;
                    //Yields a slice of every batch so a pass over a large store cannot sit on a core
                    //for its whole duration
                    Thread.sleep(BATCH_PAUSE_MILLIS);
                }
            }
            status = run.cancelled ? "cancelled after " + scanned + " sections" : "done";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "interrupted after " + scanned + " sections";
        } catch (Throwable t) {
            status = "failed: " + t;
            Logger.error("Seasonal snow LOD refresh failed", t);
        } finally {
            sectionsScanned = scanned;
            sectionsRewritten = rewritten;
            voxelsFlipped = flipped;
            if (referenced) {
                try {
                    engine.releaseRef();
                } catch (RuntimeException ignored) {
                    //World already torn down - nothing left to hand back
                }
            }
            synchronized (SeasonalSnowRefresher.class) {
                if (worker == Thread.currentThread()) {
                    worker = null;
                    active = null;
                }
            }
        }
    }

    /** @return how many voxels changed */
    private static int refreshSection(WorldEngine engine, Level level, Mapper mapper, BiomeCache biomes,
                                      WorldSection section,
                                      boolean glowBlocksSnow, int glowLevel, boolean snowyTree) {
        long[] data = section._rawOrNull();
        if (data == null) {
            //Uniform: every voxel's own copy sits directly above it. Nothing solid is sky-exposed under
            //itself, and uniform air has no block to cover, so there is nothing here to decide.
            return 0;
        }

        //A loaded chunk is ingest's business - it asks the season API directly, and racing that here
        //would replace a fresher answer with a guess. But a level 0 section spans 2x2 chunks, and
        //skipping the whole section when any one of them is loaded leaves the quadrants around the
        //render-distance edge with no writer at all: too far to be re-ingested, permanently skipped
        //here, and the band moves with the player. Skip by quadrant instead.
        int chunkX = section.x << 1;
        int chunkZ = section.z << 1;
        boolean[] quadrantLoaded = {
                MapChecker.isLoaded(level, chunkX, chunkZ),
                MapChecker.isLoaded(level, chunkX + 1, chunkZ),
                MapChecker.isLoaded(level, chunkX, chunkZ + 1),
                MapChecker.isLoaded(level, chunkX + 1, chunkZ + 1),
        };
        if (quadrantLoaded[0] && quadrantLoaded[1] && quadrantLoaded[2] && quadrantLoaded[3]) {
            return 0;
        }

        int stateCount = mapper.getBlockStateCount();
        WorldSection above = null;
        boolean aboveResolved = false;
        int changed = 0;

        try {
            for (int y = 0; y < SECTION_WIDTH; y++) {
                for (int z = 0; z < SECTION_WIDTH; z++) {
                    for (int x = 0; x < SECTION_WIDTH; x++) {
                        if (quadrantLoaded[(x >> 4) | ((z >> 4) << 1)]) {
                            continue;
                        }
                        int idx = WorldSection.getIndex(x, y, z);
                        long voxel = data[idx];
                        int storedId = Mapper.getBlockId(voxel);
                        if (storedId == 0) {
                            continue;
                        }

                        boolean storedSnowy = storedId >= stateCount;
                        int baseId = storedSnowy ? MAX_BLOCK_ID - storedId : storedId;
                        if (baseId <= 0 || baseId >= stateCount) {
                            //A complement of something this mapper no longer has - leave it alone
                            continue;
                        }

                        BlockState state = mapper.getBlockStateFromBlockId(baseId);
                        if (state == null || MapChecker.getDefaultBlockTypeFlag(state) <= 0) {
                            continue;
                        }

                        long aboveVoxel;
                        if (y + 1 < SECTION_WIDTH) {
                            aboveVoxel = data[WorldSection.getIndex(x, y + 1, z)];
                        } else {
                            if (!aboveResolved) {
                                aboveResolved = true;
                                above = engine.acquireIfExists(section.lvl, section.x, section.y + 1, section.z);
                            }
                            if (above == null) {
                                //No evidence about what is over this voxel, so do not guess
                                continue;
                            }
                            aboveVoxel = above.get(WorldSection.getIndex(x, 0, z));
                        }

                        int verdict = shouldBeSnowy(level, mapper, biomes, section, x, y, z,
                                state, aboveVoxel, Mapper.getBiomeId(voxel), stateCount,
                                glowBlocksSnow, glowLevel, snowyTree);
                        if (verdict == UNKNOWN) {
                            continue;
                        }
                        boolean wantSnowy = verdict == YES;
                        if (wantSnowy == storedSnowy) {
                            continue;
                        }

                        //Deciding took long enough for ingest to have replaced this voxel. Writing a
                        //value derived from the stale read would roll its work back, and it will have
                        //used the current season anyway - so re-read and only write if nothing moved.
                        long current = data[idx];
                        if (Mapper.getBlockId(current) != storedId) {
                            continue;
                        }
                        int newId = wantSnowy ? MAX_BLOCK_ID - baseId : baseId;
                        data[idx] = (current & ~BLOCK_ID_MASK) | (Integer.toUnsignedLong(newId) << BLOCK_ID_SHIFT);
                        changed++;
                    }
                }
            }
        } finally {
            if (above != null) {
                above.release();
            }
        }
        return changed;
    }

    //Bring the levels above this section back in line with the level 0 data just edited.
    //
    //Ingest only decides snow at level 0 and mips upward, so a parent voxel shows snow exactly when the
    //child the mip selected did - which means the mip has to be rerun to know, not guessed at from the
    //children's block ids. Rebuilding the pyramid from this section's own voxels is self-contained: a
    //level 4 voxel spans 16 blocks, so every child of every parent covering this section is inside its 32.
    //
    //Only the snow bit is carried across. Where the recomputed voxel disagrees with the stored one about
    //anything else, the stored one is ingest's and is left alone.
    //One set of pyramid buffers per walker thread - ~37KiB that a pass rewriting thousands of sections
    //would otherwise allocate for each of them.
    private static final ThreadLocal<long[][]> MIP_SCRATCH = ThreadLocal.withInitial(() -> {
        long[][] levels = new long[WorldEngine.MAX_LOD_LAYER][];
        int side = SECTION_WIDTH;
        for (int lvl = 0; lvl < levels.length; lvl++) {
            side >>= 1;
            levels[lvl] = new long[side * side * side];
        }
        return levels;
    });

    private static void remipIntoParents(WorldEngine engine, Mapper mapper, WorldSection section, long[] data) {
        int stateCount = mapper.getBlockStateCount();
        long[][] scratch = MIP_SCRATCH.get();
        long[] cur = data;
        int curSide = SECTION_WIDTH;

        for (int lvl = 1; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
            int side = curSide >> 1;
            long[] next = scratch[lvl - 1];
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        int cx = x << 1, cy = y << 1, cz = z << 1;
                        next[cubeIndex(x, y, z, side)] = Mipper.mip(
                                cur[cubeIndex(cx, cy, cz, curSide)],
                                cur[cubeIndex(cx + 1, cy, cz, curSide)],
                                cur[cubeIndex(cx, cy, cz + 1, curSide)],
                                cur[cubeIndex(cx + 1, cy, cz + 1, curSide)],
                                cur[cubeIndex(cx, cy + 1, cz, curSide)],
                                cur[cubeIndex(cx + 1, cy + 1, cz, curSide)],
                                cur[cubeIndex(cx, cy + 1, cz + 1, curSide)],
                                cur[cubeIndex(cx + 1, cy + 1, cz + 1, curSide)],
                                mapper);
                    }
                }
            }

            applyLevel(engine, section, lvl, next, side, stateCount);
            cur = next;
            curSide = side;
        }
    }

    private static void applyLevel(WorldEngine engine, WorldSection section, int lvl,
                                   long[] computed, int side, int stateCount) {
        WorldSection parent = engine.acquireIfExists(lvl,
                section.x >> lvl, section.y >> lvl, section.z >> lvl);
        if (parent == null) {
            return;
        }
        try {
            long[] pdata = parent._rawOrNull();
            if (pdata == null) {
                //Uniform parent: one value over the whole thing, so there is no per-voxel snow state to
                //correct. Materialising it here would cost 256KiB to write nothing.
                return;
            }
            //Where this section lands inside the parent: its 32 blocks are 32>>lvl of the parent's voxels
            int ox = (section.x << (5 - lvl)) & 31;
            int oy = (section.y << (5 - lvl)) & 31;
            int oz = (section.z << (5 - lvl)) & 31;

            boolean touched = false;
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        long want = computed[cubeIndex(x, y, z, side)];
                        int pidx = WorldSection.getIndex(ox + x, oy + y, oz + z);
                        long have = pdata[pidx];
                        if (want == have) {
                            continue;
                        }
                        int wantId = Mapper.getBlockId(want);
                        int haveId = Mapper.getBlockId(have);
                        boolean wantSnowy = wantId >= stateCount;
                        boolean haveSnowy = haveId >= stateCount;
                        int wantBase = wantSnowy ? MAX_BLOCK_ID - wantId : wantId;
                        int haveBase = haveSnowy ? MAX_BLOCK_ID - haveId : haveId;
                        //Disagreeing about which block this is means the mip picked differently than
                        //ingest did - not something a season change should be rewriting
                        if (wantBase != haveBase || wantSnowy == haveSnowy) {
                            continue;
                        }
                        int newId = wantSnowy ? MAX_BLOCK_ID - haveBase : haveBase;
                        pdata[pidx] = (have & ~BLOCK_ID_MASK) | (Integer.toUnsignedLong(newId) << BLOCK_ID_SHIFT);
                        touched = true;
                    }
                }
            }
            if (touched) {
                engine.markDirty(parent, WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0);
            }
        } finally {
            parent.release();
        }
    }

    //Matches WorldSection.getIndex at side 32 and generalises it to the smaller mip cubes
    private static int cubeIndex(int x, int y, int z, int side) {
        return (y * side + z) * side + x;
    }

    //The ingest-side decision for a chunk that is not loaded, rerun against stored voxels. Held parallel
    //to VoxyTool.changeBlockId's second branch - if that one changes this has to follow, or LOD and
    //freshly ingested terrain will disagree about the same block.
    private static int shouldBeSnowy(Level level, Mapper mapper, BiomeCache biomes, WorldSection section,
                                         int x, int y, int z, BlockState state, long aboveVoxel,
                                         int biomeId, int stateCount,
                                         boolean glowBlocksSnow, int glowLevel, boolean snowyTree) {
        int light = Mapper.getLightId(aboveVoxel);
        if ((light & 0xF) <= MIN_SKY_LIGHT) {
            return NO;
        }
        if (glowBlocksSnow && ((light >> 4) & 0xF) >= glowLevel) {
            return NO;
        }

        int aboveStored = Mapper.getBlockId(aboveVoxel);
        int aboveBase = aboveStored >= stateCount ? MAX_BLOCK_ID - aboveStored : aboveStored;
        if (aboveBase < 0 || aboveBase >= stateCount) {
            return UNKNOWN;
        }
        BlockState aboveState = mapper.getBlockStateFromBlockId(aboveBase);
        if (aboveState == null) {
            return UNKNOWN;
        }

        int flag = MapChecker.getDefaultBlockTypeFlag(state);
        if (MapChecker.leaveLike(flag)) {
            boolean specialLeaves = aboveState.is(state.getBlock())
                    && (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(aboveState)
                        || MapChecker.extraSnowPassable(aboveState));
            if (specialLeaves && !snowyTree) {
                return NO;
            }
        } else if (MapChecker.extraSnowPassable(state) && MapChecker.extraSnowPassable(aboveState)) {
            return NO;
        }

        //A biome the store remembers but this world cannot resolve is not evidence of no snow. Treating
        //it as such would strip snow, persist that, push it up the levels and cache the failure for the
        //whole pass - and only a re-ingest could ever put it back. Leave the voxel exactly as it is.
        Holder<Biome> biome = biomes.get(biomeId);
        if (biome == null) {
            return UNKNOWN;
        }
        BlockPos pos = new BlockPos((section.x << 5) + x, (section.y << 5) + y, (section.z << 5) + z);
        return MapChecker.shouldSnowAtBiome(level, biome.value(), state, level.getRandom(),
                state.getSeed(pos), pos) ? YES : NO;
    }

    //getBiomeEntries copies the whole table under a lock, and resolving a biome key allocates and hits
    //the registry twice. Both answer to a 9-bit id, so both are worth doing once per id per pass -
    //including the failures, so a biome the store remembers but the world no longer has cannot throw
    //once per voxel.
    private static final class BiomeCache {
        private final Level level;
        private final Mapper.BiomeEntry[] entries;
        private final Holder<Biome>[] resolved;
        private final boolean[] known;

        @SuppressWarnings("unchecked")
        BiomeCache(Level level, Mapper mapper) {
            this.level = level;
            //One copy for the whole pass: getBiomeEntries takes a lock and rebuilds the whole table
            this.entries = mapper.getBiomeEntries();
            this.resolved = new Holder[Math.max(1, this.entries.length)];
            this.known = new boolean[this.resolved.length];
        }

        Holder<Biome> get(int biomeId) {
            if (biomeId < 0 || biomeId >= this.known.length) {
                return null;
            }
            if (this.known[biomeId]) {
                return this.resolved[biomeId];
            }
            this.known[biomeId] = true;
            try {
                if (biomeId >= this.entries.length || this.entries[biomeId] == null) {
                    return null;
                }
                ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME,
                        ResourceLocation.parse(this.entries[biomeId].biome));
                this.resolved[biomeId] = this.level.registryAccess()
                        .registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
            } catch (RuntimeException e) {
                this.resolved[biomeId] = null;
            }
            return this.resolved[biomeId];
        }
    }
}
