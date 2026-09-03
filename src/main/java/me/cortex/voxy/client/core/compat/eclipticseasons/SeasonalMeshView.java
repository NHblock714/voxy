package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.api.constant.solar.SolarTerm;
import com.teamtea.eclipticseasons.client.color.season.FoliageColorSource;
import com.teamtea.eclipticseasons.client.core.ExtraModelManager;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.client.util.ClientRef;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.common.core.map.stub.PlainsStubHolder;
import com.teamtea.eclipticseasons.config.ClientConfig;
import com.teamtea.eclipticseasons.config.CommonConfig;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.bakery.ReuseVertexConsumer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.SeasonalIdSpace;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

//The mesh-time seasonal view: stored voxels stay season neutral, and each remesh judges snow
//cover, frozen water and seasonal models against the CURRENT solar term, swapping in render-only
//ids from SeasonalIdSpace. Judgements run per voxel on mesh workers (the sodium builder threads
//included when job stealing is on), so the caches below are keyed per Mapper - block and biome
//ids are per world engine, and a shared table would hand one dimension's verdicts to another -
//and are snapshotted into a per-call Ctx so the hit path is a plain array read with no monitor.
public class SeasonalMeshView implements SeasonalLod.View {
    private static final ThreadLocal<RandomSource> RANDOM =
            ThreadLocal.withInitial(RandomSource::createNewThreadLocalInstance);
    private static final ThreadLocal<BlockPos.MutableBlockPos> POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    //Per-real-block-id verdicts: whether the block has seasonal model definitions, whether it is
    //freezable water, plus the snow-type flag. Static between data reloads; racy rebuilds only
    //recompute the same value.
    private static final int F_COMPUTED = 1 << 31, F_SEASONAL = 1 << 30, F_FREEZABLE = 1 << 29;
    private static final int FLAG_MASK = 0xFFFF;

    private static final int[] EMPTY_CANDIDATES = new int[0];
    @SuppressWarnings("unchecked")
    private static final Holder<Biome>[] EMPTY_BIOMES = new Holder[0];

    private static final Map<Mapper, int[]> CANDIDATE_CACHES = new WeakHashMap<>();
    private static final Map<Mapper, Holder<Biome>[]> BIOME_CACHES = new WeakHashMap<>();

    //The season definitions and snow tables are data driven: a solar-term change or a logout can
    //sit on top of a /reload that changed them, so both events drop the verdicts wholesale
    public static void clearJudgementCaches() {
        synchronized (CANDIDATE_CACHES) { CANDIDATE_CACHES.clear(); }
        synchronized (BIOME_CACHES) { BIOME_CACHES.clear(); }
    }

    //Everything a per-voxel judgement needs, resolved once per section call: the cache snapshots
    //(refreshed by the miss paths when they grow the shared arrays) and the config reads, which
    //are ModConfigSpec lookups too slow to sit on a 32768-iteration loop
    private static final class Ctx {
        Level level;
        Mapper mapper;
        int[] candidates;
        Holder<Biome>[] biomes;
        boolean snowyWinter;
        boolean glowGate;
        int glowLevel;
        boolean frozenWater;
        boolean snowyTree;
    }

    private static Ctx makeCtx(Level level, Mapper mapper) {
        var ctx = new Ctx();
        ctx.level = level;
        ctx.mapper = mapper;
        synchronized (CANDIDATE_CACHES) {
            var cache = CANDIDATE_CACHES.get(mapper);
            ctx.candidates = cache != null ? cache : EMPTY_CANDIDATES;
        }
        synchronized (BIOME_CACHES) {
            var cache = BIOME_CACHES.get(mapper);
            ctx.biomes = cache != null ? cache : EMPTY_BIOMES;
        }
        ctx.snowyWinter = CommonConfig.isSnowyWinter();
        ctx.glowGate = CommonConfig.Snow.notSnowyNearGlowingBlock.get();
        ctx.glowLevel = CommonConfig.Snow.notSnowyNearGlowingBlockLevel.get();
        ctx.frozenWater = ClientConfig.Debug.frozenWater.get();
        ctx.snowyTree = CommonConfig.Snow.snowyTree.get();
        return ctx;
    }

    private static int computeCandidate(Ctx ctx, int realBlockId) {
        BlockState state = ctx.mapper.getBlockStateFromBlockId(realBlockId);
        int v = F_COMPUTED;
        if (ClientRef.seasonDef.containsKey(state.getBlock())) v |= F_SEASONAL;
        if (state.is(Blocks.WATER) && state.getFluidState().isSourceOfType(Fluids.WATER)) v |= F_FREEZABLE;
        //FLAG_IGNORE is negative: clamp it instead of masking, or the mask would turn ES's
        //"never treat this block" marker into a large snowable flag
        v |= Math.max(MapChecker.getDefaultBlockTypeFlag(state), 0) & FLAG_MASK;
        synchronized (CANDIDATE_CACHES) {
            int[] cache = CANDIDATE_CACHES.get(ctx.mapper);
            if (cache == null || realBlockId >= cache.length) {
                int[] grown = new int[Math.max(realBlockId + 1,
                        cache == null ? 1024 : cache.length << 1)];
                if (cache != null) System.arraycopy(cache, 0, grown, 0, cache.length);
                cache = grown;
            }
            cache[realBlockId] = v;
            CANDIDATE_CACHES.put(ctx.mapper, cache);
            ctx.candidates = cache;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome> biomeFor(Ctx ctx, int biomeId) {
        if (biomeId < 0) return null;
        var cache = ctx.biomes;
        if (biomeId < cache.length) {
            Holder<Biome> hit = cache[biomeId];
            if (hit != null) return hit;
        }
        var entry = ctx.mapper.getBiomeEntry(biomeId);
        if (entry == null) return null;
        var key = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(entry.biome));
        Holder<Biome> resolved = ctx.level.registryAccess().registryOrThrow(Registries.BIOME)
                .getHolder(key).map(h -> (Holder<Biome>) h).orElse(PlainsStubHolder.PLAINS);
        synchronized (BIOME_CACHES) {
            var current = BIOME_CACHES.get(ctx.mapper);
            if (current == null || current.length <= biomeId) {
                var grown = new Holder[Math.max(biomeId + 1,
                        current == null ? 512 : current.length << 1)];
                if (current != null) System.arraycopy(current, 0, grown, 0, current.length);
                current = (Holder<Biome>[]) grown;
            }
            current[biomeId] = resolved;
            BIOME_CACHES.put(ctx.mapper, current);
            ctx.biomes = current;
        }
        return resolved;
    }

    //Dimensions outside ES's whitelist (nether, the end, custom and mirror worlds) tick
    //nowSolarTerm to NONE and show no seasons at close range; the LOD must agree, and skipping
    //here also spares the 32768-voxel scan in every section of those dimensions
    private static boolean seasonInactive() {
        var term = ClientCon.nowSolarTerm;
        return term == null || term == SolarTerm.NONE;
    }

    @Override
    public long[] substituteSection(WorldEngine world, WorldSection section, long[] source) {
        if (!VoxyConfig.CONFIG.eclipticSeasonsSnowLod) return source;
        if (seasonInactive()) return source;
        Level level = ClientCon.getUseLevel();
        if (level == null) return source;

        var ctx = makeCtx(level, world.getMapper());
        long[] output = source;

        int scale = 1 << section.lvl;
        int centerOffset = section.lvl == 0 ? 0 : scale >> 1;
        int baseX = ((section.x << 5) << section.lvl) + centerOffset;
        int baseY = ((section.y << 5) << section.lvl) + centerOffset;
        int baseZ = ((section.z << 5) << section.lvl) + centerOffset;

        var pos = POS.get();
        WorldSection aboveSection = null;
        boolean aboveChecked = false;
        try {
            for (int index = 0; index < source.length; index++) {
                long mappingId = source[index];
                if (Mapper.isAir(mappingId)) continue;

                int storedBlockId = Mapper.getBlockId(mappingId);
                //Decode first: legacy archives hold complement-encoded snow, and re-encoding an
                //already-encoded id must be impossible for the pass to be idempotent
                int originalBlockId = SeasonalIdSpace.decode(ctx.mapper, storedBlockId);

                //Cache-first: the common verdict (not seasonal, not snowable) must cost one array
                //read, with no BlockState fetch on the hit path
                int cand = originalBlockId < ctx.candidates.length ? ctx.candidates[originalBlockId] : 0;
                if (cand == 0) cand = computeCandidate(ctx, originalBlockId);
                if ((cand & (F_SEASONAL | F_FREEZABLE)) == 0
                        && (cand & FLAG_MASK) <= MapChecker.FLAG_NONE) continue;

                long aboveMappingId;
                int localY = index >>> 10;
                if (localY < 31) {
                    aboveMappingId = source[index + 1024];
                } else {
                    if (!aboveChecked) {
                        aboveChecked = true;
                        aboveSection = world.acquireIfExists(section.lvl, section.x, section.y + 1, section.z);
                    }
                    if (aboveSection == null) continue;
                    //Single-voxel read: materialising the neighbour would permanently expand a
                    //uniform section into a pooled 256KiB array for a 1024-voxel row
                    aboveMappingId = aboveSection.get(index & 0x3FF);
                }

                //Light and winter gates before any BlockState fetch: snow and ice need a snowy
                //winter and lit sky above, seasonal models need neither
                boolean canSnow = canSnow(ctx, aboveMappingId);
                if (!canSnow && (cand & F_SEASONAL) == 0) continue;

                pos.set(baseX + (index & 31) * scale,
                        baseY + localY * scale,
                        baseZ + ((index >> 5) & 31) * scale);

                int renderBlockId = judge(ctx, mappingId, aboveMappingId, originalBlockId, cand, canSnow, pos);
                if (renderBlockId == storedBlockId) continue;

                if (output == source) output = Arrays.copyOf(source, source.length);
                output[index] = Mapper.withBlockBiome(mappingId, renderBlockId, Mapper.getBiomeId(mappingId));
            }
            return output;
        } finally {
            if (aboveSection != null) aboveSection.release(WorldSection.RELEASE_HINT_POSSIBLE_REUSE);
        }
    }

    //Slices are indexed h|(y<<5) (acquireNeighborData); the in-slice voxel one above is i+32.
    //The top row (y==31) is left as stored - its above-voxel lives in a diagonal section - and
    //the +-y slices are skipped entirely: a voxel with a block directly above it can neither
    //freeze nor carry snow, so a -y-slice substitution could never change anything the culler
    //sees, and the +y slice has no above data at all.
    @Override
    public void substituteLateralSlices(WorldEngine world, WorldSection section,
                                        long[] neighborFaces, int neighborMsk) {
        if (!VoxyConfig.CONFIG.eclipticSeasonsSnowLod) return;
        if (seasonInactive()) return;
        Level level = ClientCon.getUseLevel();
        if (level == null) return;
        var ctx = makeCtx(level, world.getMapper());

        int scale = 1 << section.lvl;
        int centerOffset = section.lvl == 0 ? 0 : scale >> 1;
        int baseX = ((section.x << 5) << section.lvl) + centerOffset;
        int baseY = ((section.y << 5) << section.lvl) + centerOffset;
        int baseZ = ((section.z << 5) << section.lvl) + centerOffset;

        //msk bit -> slice, fixed X/Z world coordinate, horizontal axis of the slice index
        substituteSlice(ctx, neighborFaces, neighborMsk, 1, 0, baseX - scale, baseY, baseZ, true, scale);
        substituteSlice(ctx, neighborFaces, neighborMsk, 2, 1, baseX + 32 * scale, baseY, baseZ, true, scale);
        substituteSlice(ctx, neighborFaces, neighborMsk, 16, 4, baseZ - scale, baseY, baseX, false, scale);
        substituteSlice(ctx, neighborFaces, neighborMsk, 32, 5, baseZ + 32 * scale, baseY, baseX, false, scale);
    }

    private void substituteSlice(Ctx ctx, long[] faces, int msk, int bit, int slice,
                                 int fixedCoord, int baseY, int baseH, boolean fixedIsX, int scale) {
        if ((msk & bit) == 0) return;
        int base = slice * 32 * 32;
        var pos = POS.get();
        for (int i = 0; i < 32 * 31; i++) {//y == 31 row excluded
            long mappingId = faces[base + i];
            if (mappingId == 0 || Mapper.isAir(mappingId)) continue;//0 doubles as the cleared-slice sentinel

            int storedBlockId = Mapper.getBlockId(mappingId);
            int originalBlockId = SeasonalIdSpace.decode(ctx.mapper, storedBlockId);
            int cand = originalBlockId < ctx.candidates.length ? ctx.candidates[originalBlockId] : 0;
            if (cand == 0) cand = computeCandidate(ctx, originalBlockId);
            if ((cand & (F_SEASONAL | F_FREEZABLE)) == 0
                    && (cand & FLAG_MASK) <= MapChecker.FLAG_NONE) continue;

            long aboveMappingId = faces[base + i + 32];
            boolean canSnow = canSnow(ctx, aboveMappingId);
            if (!canSnow && (cand & F_SEASONAL) == 0) continue;

            int h = (i & 31), y = i >> 5;
            if (fixedIsX) pos.set(fixedCoord, baseY + y * scale, baseH + h * scale);
            else pos.set(baseH + h * scale, baseY + y * scale, fixedCoord);

            int renderBlockId = judge(ctx, mappingId, aboveMappingId, originalBlockId, cand, canSnow, pos);
            if (renderBlockId != storedBlockId) {
                faces[base + i] = Mapper.withBlockBiome(mappingId, renderBlockId, Mapper.getBiomeId(mappingId));
            }
        }
    }

    //Voxel light packs sky in the low nibble, block in the high (VoxelIngestService). The
    //SnowyWinter config gate matches the near field, where it sits in front of every snow and
    //ice placement but not the seasonal model channel.
    private static boolean canSnow(Ctx ctx, long aboveMappingId) {
        if (!ctx.snowyWinter) return false;
        int light = Mapper.getLightId(aboveMappingId);
        return (light & 0xF) > 9
                && (!ctx.glowGate || ((light >> 4) & 0xF) < ctx.glowLevel);
    }

    private static int judge(Ctx ctx, long mappingId, long aboveMappingId,
                             int originalBlockId, int cand, boolean canSnow, BlockPos pos) {
        boolean seasonalModelRelated = (cand & F_SEASONAL) != 0;
        //Snow and ice are light gated; seasonal models are not (leaves change colour in the dark)
        boolean freezable = canSnow && (cand & F_FREEZABLE) != 0;
        int flag = canSnow ? (cand & FLAG_MASK) : MapChecker.FLAG_NONE;
        if (!seasonalModelRelated && !freezable && flag <= MapChecker.FLAG_NONE) return originalBlockId;

        Mapper mapper = ctx.mapper;
        BlockState state = mapper.getBlockStateFromBlockId(originalBlockId);
        BlockState above = mapper.getBlockStateFromBlockId(Mapper.getBlockId(aboveMappingId));
        boolean snowRenderable = flag > MapChecker.FLAG_NONE && canRenderSnow(ctx, state, above, flag);
        if (!seasonalModelRelated && !freezable && !snowRenderable) return originalBlockId;

        Holder<Biome> biome = biomeFor(ctx, Mapper.getBiomeId(mappingId));
        if (biome == null) return originalBlockId;

        long seed = state.getSeed(pos);
        if (freezable && ctx.frozenWater && above.isAir()
                && MapChecker.shouldSnowAtBiome(ctx.level, biome.value(), state, RANDOM.get(), seed, pos)) {
            return SeasonalIdSpace.VIRTUAL_ICE_ID;
        }

        boolean snowy = snowRenderable && MapChecker.shouldSnowAtBiome(
                ctx.level, biome.value(), state, RANDOM.get(), seed, pos);

        int model = seasonalModelRelated
                ? findSeasonalModel(mapper, originalBlockId, biome, state, above.isAir(), pos, seed, snowy)
                : originalBlockId;
        return model != originalBlockId ? model
                : snowy ? SeasonalIdSpace.MAX_BLOCK_ID - originalBlockId : originalBlockId;
    }

    private static int findSeasonalModel(Mapper mapper, int originalBlockId, Holder<Biome> biome,
                                         BlockState state, boolean airAbove, BlockPos pos,
                                         long seed, boolean snowy) {
        var defs = ClientRef.seasonDef.get(state.getBlock());
        if (defs == null) return originalBlockId;
        for (int i = 0; i < defs.size(); i++) {
            var def = defs.get(i);
            var holders = def.getFlatSliceEnumMap().get(ClientCon.nowSolarTerm);
            if (holders == null || holders.isEmpty()) continue;
            //Unconditional containment, matching the near field: an empty biome set never
            //matches, so a definition without biomes shows on neither side of the LOD border
            if (!def.getBiomes().contains(biome)) continue;
            for (int j = 0; j < holders.size(); j++) {
                var flatSlice = holders.get(j).flatSlice();
                if (flatSlice.emptyAbove() && !airAbove) continue;
                //Per-block staggered transition: blocks flip from the first to the second model
                //as the in-term progress (0-100) advances past their own stable threshold
                ResourceLocation modelId = flatSlice.transitionModels() == null
                        ? flatSlice.mid()
                        : Mth.abs((int) (seed + pos.getX())) % 100 > ClientCon.progress
                        ? flatSlice.transitionModels().getFirst()
                        : flatSlice.transitionModels().getSecond();
                if (modelId == null) continue;
                return SeasonalIdSpace.getOrCreate(mapper, originalBlockId, modelId, snowy);
            }
        }
        return originalBlockId;
    }

    private static boolean canRenderSnow(Ctx ctx, BlockState state, BlockState above, int flag) {
        if (MapChecker.leaveLike(flag)) {
            boolean specialLeaves = above.is(state.getBlock())
                    && (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(above)
                    || MapChecker.extraSnowPassable(above));
            return !specialLeaves || ctx.snowyTree;
        }
        return !MapChecker.solidTest(above);
    }

    @Override
    public boolean isSeasonalConstantTint(BlockState state, Object colourProvider) {
        //ES recolours through providers that never call getBlockTint: the hardwired
        //birch/spruce/mangrove trio and every data-driven SeasonalColorOverrides entry register a
        //FoliageColorSource (or its Impl). MixinBlockColors can also rewrite the vanilla lambdas
        //for the trio in place, which an instanceof cannot see - hence the block identity check
        //stays alongside the provider check. Over-marking a leaf that is genuinely biome
        //dependent is harmless - it was headed for the per-biome colour rows anyway.
        return colourProvider instanceof FoliageColorSource
                || colourProvider instanceof FoliageColorSource.Impl
                || state.is(Blocks.BIRCH_LEAVES) || state.is(Blocks.SPRUCE_LEAVES)
                || state.is(Blocks.MANGROVE_LEAVES);
    }

    @Override
    public SeasonalLod.SeasonalBakedModel resolveSeasonalModel(BlockState state, ResourceLocation modelId) {
        var tester = ExtraModelManager.getSeasonalModel(state, modelId);
        if (tester == null) return null;
        var model = ExtraModelManager.getExtraModel(tester.modelResourceLocation());
        return model == null ? null : new SeasonalLod.SeasonalBakedModel(model, tester.replace());
    }

    @Override
    public void renderSnowOverlay(BlockState state, RenderType layer,
                                  ReuseVertexConsumer translucentVC, ReuseVertexConsumer opaqueVC) {
        VoxyClientTool.renderToStream(state, layer, translucentVC, opaqueVC);
    }
}
