package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.api.EclipticSeasonsApi;
import com.teamtea.eclipticseasons.client.util.ClientCon;
import com.teamtea.eclipticseasons.common.core.map.MapChecker;
import com.teamtea.eclipticseasons.config.CommonConfig;
import me.cortex.voxy.client.config.VoxyConfig;
import java.lang.reflect.Method;
import java.util.function.IntConsumer;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

public class VoxyTool {
    private static final int maxBlockId = 1048575;

    public static boolean isVoxyTest() {
        return VoxyConfig.CONFIG.eclipticSeasonsSnowLod;
    }

    //Per-section invariants for the per-voxel remap: level, chunk-loaded state and the section
    //origin are constant across a section's 4096 voxels, and the biome entry table copy takes the
    //mapper's biome lock - doing any of these per voxel serializes the ingest workers or churns
    //allocation. One context per worker thread; convert() announces each new section through
    //beginSection (identity of the VoxelizedSection cannot be used as the key - those objects are
    //pooled and reused by the same thread).
    private static final class SectionCtx {
        boolean active;
        boolean loaded;
        Level level;
        Mapper mapper;
        ILightingSupplier lightSupplier;
        Mapper.BiomeEntry[] biomeEntries;
        Holder.Reference<Biome>[] holders;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int originX, originY, originZ;
    }
    private static final ThreadLocal<SectionCtx> CTX = ThreadLocal.withInitial(SectionCtx::new);

    public static void beginSection(VoxelizedSection section, Mapper mapper, ILightingSupplier lightSupplier) {
        var ctx = CTX.get();
        ctx.mapper = mapper;
        ctx.lightSupplier = lightSupplier;
        ctx.biomeEntries = null;
        ctx.holders = null;
        Level level = VoxyTool.isVoxyTest() ? ClientCon.getUseLevel() : null;
        ctx.level = level;
        ctx.active = level != null;
        if (!ctx.active) {
            return;
        }
        ctx.loaded = MapChecker.isLoaded((Level)level, (int)section.x, (int)section.z);
        BlockPos origin = SectionPos.of((int)section.x, (int)section.y, (int)section.z).origin();
        ctx.originX = origin.getX();
        ctx.originY = origin.getY();
        ctx.originZ = origin.getZ();
    }

    public static int changeBlockId(int blockId, int i, int biomeId) {
        var ctx = CTX.get();
        if (!ctx.active) {
            return blockId;
        }
        int maxBlockId = 1048575;
        BlockState state = ctx.mapper.getBlockStateFromBlockId(blockId);
        if (MapChecker.getDefaultBlockTypeFlag((BlockState)state) <= 0) {
            return blockId;
        }
        Level level = ctx.level;
        if (ctx.loaded) {
            ctx.pos.set(ctx.originX + (i & 0xF), ctx.originY + (i >> 8 & 0xF), ctx.originZ + (i >> 4 & 0xF));
            if (EclipticSeasonsApi.getInstance().isSnowyBlock(level, state, ctx.pos)) {
                return maxBlockId - blockId;
            }
            return blockId;
        }
        IVoxyAboveLightingSupplier supplier;
        byte supply;
        int skyLight;
        if (ctx.lightSupplier instanceof IVoxyAboveLightingSupplier && (skyLight = (supply = (supplier = (IVoxyAboveLightingSupplier)ctx.lightSupplier).supply(i & 0xF, (i >> 8 & 0xF) + 1, i >> 4 & 0xF)) & 0xFF & 0xF) > 9 && (!((Boolean)CommonConfig.Snow.notSnowyNearGlowingBlock.get()).booleanValue() || ((supply & 0xFF) >> 4 & 0xF) < CommonConfig.Snow.notSnowyNearGlowingBlockLevel.getAsInt())) {
            BlockState aboveState = supplier.getBlockState(i & 0xF, (i >> 8 & 0xF) + 1, i >> 4 & 0xF);
            boolean isLight = true;
            int flag = MapChecker.getDefaultBlockTypeFlag((BlockState)state);
            if (MapChecker.leaveLike((int)flag)) {
                boolean specialLeaves;
                boolean bl = specialLeaves = aboveState.is(state.getBlock()) && (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(aboveState) || MapChecker.extraSnowPassable((BlockState)aboveState));
                if (specialLeaves) {
                    isLight = (Boolean)CommonConfig.Snow.snowyTree.get();
                }
            } else if (MapChecker.extraSnowPassable((BlockState)state)) {
                boolean bl = isLight = !MapChecker.extraSnowPassable((BlockState)aboveState);
            }
            if (isLight) {
                Holder.Reference<Biome> holder = holderFor(ctx, biomeId);
                if (holder != null) {
                    ctx.pos.set(ctx.originX + (i & 0xF), ctx.originY + (i >> 8 & 0xF), ctx.originZ + (i >> 4 & 0xF));
                    if (MapChecker.shouldSnowAtBiome((Level)level, (Biome)((Biome)holder.value()), (BlockState)state, (RandomSource)level.getRandom(), (long)state.getSeed(ctx.pos), (BlockPos)ctx.pos)) {
                        return maxBlockId - blockId;
                    }
                }
            }
        }
        return blockId;
    }

    @SuppressWarnings("unchecked")
    private static Holder.Reference<Biome> holderFor(SectionCtx ctx, int biomeId) {
        if (ctx.biomeEntries == null) {
            //One lock-guarded full copy per SECTION, never per voxel - getBiomeEntries copies the
            //whole table under the biome lock (SeasonalSnowRefresher hoists it for the same reason)
            ctx.biomeEntries = ctx.mapper.getBiomeEntries();
            ctx.holders = new Holder.Reference[ctx.biomeEntries.length];
        }
        if (biomeId < 0 || biomeId >= ctx.holders.length || ctx.biomeEntries[biomeId] == null) {
            return null;
        }
        var holder = ctx.holders[biomeId];
        if (holder == null) {
            String biome = ctx.biomeEntries[biomeId].biome;
            ResourceKey<Biome> holderKey = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(biome));
            holder = ctx.level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(holderKey);
            ctx.holders[biomeId] = holder;
        }
        return holder;
    }

    public static int fixId(Mapper mapper, int blockId) {
        return VoxyTool.fixId(mapper, blockId, VoxyTool::emptyConsumer);
    }

    private static void emptyConsumer(int i) {
    }

    public static int fixId(Mapper mapper, int blockId, IntConsumer consumer) {
        int blockStateCount = mapper.getBlockStateCount();
        if (blockId < blockStateCount) {
            return blockId;
        }
        if ((blockId = 1048575 - blockId) < blockStateCount) {
            consumer.accept(blockId);
            return blockId;
        }
        return 1048575 - blockId;
    }

    public static WorldEngine getWorld(Level level) {
        return VoxyTool.getVoxyInstance().getNullable(WorldIdentifier.of((Level)level));
    }

    public static Mapper getMapper(Level level) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : world.getMapper();
    }

    public static int getSkyLightFromBlockId(long blockId) {
        return Mapper.getLightId((long)blockId) % 16;
    }

    public static WorldSection getWorldSection(WorldEngine into, SectionPos section) {
        int lvl = 0;
        return into.acquireIfExists(lvl, section.x() >> lvl + 1, section.y() >> lvl + 1, section.z() >> lvl + 1);
    }

    public static WorldSection getWorldSection(Level level, SectionPos section) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : VoxyTool.getWorldSection(world, section);
    }

    //Polled from ClientLevel.tick. The snow-change flag is what EclipticSeasons raises when the term
    //rolls over, and consuming it starts one pass over the stored LOD.
    public static void tryUpdate() {
        if (!VoxyTool.isVoxyTest()) {
            return;
        }
        if (!VoxyConfig.CONFIG.eclipticSeasonsLodAutoReload) {
            return;
        }
        Level level = ClientCon.getUseLevel();
        if (level == null || !ClientCon.getAgent().isSnowChange() || SeasonalSnowRefresher.isRunning()) {
            return;
        }
        //Snow-depth broadcasts repeat for as long as it is snowing, and each pass walks the whole
        //store. The flag is left unconsumed, so a season change inside the window is deferred to the
        //next tick past it, never lost. Manual /voxy debug seasons refresh bypasses this by calling
        //start directly.
        if (System.currentTimeMillis() - SeasonalSnowRefresher.lastPassEndMillis < 60_000) {
            return;
        }
        //Nullable: the get-or-create variant would stand up an engine and a RocksDB store for a
        //dimension nothing else references, every time a term rolls over
        WorldEngine engine = WorldIdentifier.ofEngineNullable((Level)level);
        if (engine == null || !engine.isLive()) {
            //Do not consume the flag with nowhere to put the work - the next tick can try again
            return;
        }
        ClientCon.agent.setSnowChange(false);
        SeasonalSnowRefresher.start(level, engine);
    }

    @Nullable
    private static VoxyInstance getVoxyInstance() {
        VoxyInstance instance = null;
        try {
            Class<?> clazz = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            Method method = clazz.getDeclaredMethod("getInstance", new Class[0]);
            instance = (VoxyInstance)method.invoke(null, new Object[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return instance;
    }
}

