package me.cortex.voxy.commonImpl.compat;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compatibility bridge for Domum Ornamentum materially textured blocks.
 *
 * Domum stores its selected materials on block entities and exposes them via
 * NeoForge ModelData. Voxy's normal mapping is only BlockState based, so every
 * Domum block with the same shape was baked as the same fallback/placeholder
 * model. This bridge creates Voxy-side virtual block-state ids for Domum
 * material variants while keeping the original BlockState for shape, culling,
 * lighting and metadata.
 */
public final class DomumOrnamentumCompat {
    private static final boolean LOADED = ModList.get().isLoaded("domum_ornamentum");
    private static final String DOMUM_PACKAGE = "com.ldtteam.domumornamentum";

    private record DomumModelInfo(ModelData modelData, String variantKey) { }

    private static final ThreadLocal<DomumModelInfo[]> SECTION_MODEL_DATA = new ThreadLocal<>();
    private static final Map<Integer, ModelData> MODEL_DATA_BY_BLOCK_ID = new ConcurrentHashMap<>();

    private DomumOrnamentumCompat() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static void beginSection(LevelChunk chunk, int sectionY) {
        if (!LOADED || chunk == null) {
            SECTION_MODEL_DATA.remove();
            return;
        }

        DomumModelInfo[] sectionData = null;
        int minY = sectionY << 4;
        int maxY = minY + 15;

        try {
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity == null || !isDomumBlockEntity(blockEntity)) {
                    continue;
                }

                BlockPos pos = blockEntity.getBlockPos();
                if (pos.getY() < minY || pos.getY() > maxY) {
                    continue;
                }

                ModelData modelData = blockEntity.getModelData();
                if (modelData == null || modelData == ModelData.EMPTY) {
                    continue;
                }

                String variantKey = extractVariantKey(blockEntity, modelData);
                if (variantKey == null || variantKey.isBlank()) {
                    continue;
                }

                if (sectionData == null) {
                    sectionData = new DomumModelInfo[4096];
                }
                int localIndex = (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
                sectionData[localIndex] = new DomumModelInfo(modelData, variantKey);
            }
        } catch (Throwable ignored) {
            sectionData = null;
        }

        if (sectionData == null) {
            SECTION_MODEL_DATA.remove();
        } else {
            SECTION_MODEL_DATA.set(sectionData);
        }
    }

    public static void endSection() {
        SECTION_MODEL_DATA.remove();
    }

    /**
     * Returns the Voxy block id that should be stored for this voxel. For normal
     * blocks this is just the base id. For Domum blocks with ModelData at the
     * same position, this returns a virtual id that still points to the original
     * BlockState but bakes with the captured Domum ModelData.
     */
    public static int mapBlockId(Mapper mapper, BlockState state, int baseBlockId, int localIndex) {
        if (!LOADED || mapper == null || state == null || localIndex < 0 || localIndex >= 4096) {
            return baseBlockId;
        }

        DomumModelInfo[] sectionData = SECTION_MODEL_DATA.get();
        if (sectionData == null) {
            return baseBlockId;
        }

        DomumModelInfo info = sectionData[localIndex];
        if (info == null || info.modelData == null || info.modelData == ModelData.EMPTY) {
            return baseBlockId;
        }

        String key = state.toString() + "|" + info.variantKey;
        int mappedId = mapper.getIdForBlockStateVariant(state, key);
        MODEL_DATA_BY_BLOCK_ID.putIfAbsent(mappedId, info.modelData);
        return mappedId;
    }

    public static ModelData getModelData(int blockId, BlockState state) {
        if (!LOADED) {
            return ModelData.EMPTY;
        }
        return MODEL_DATA_BY_BLOCK_ID.getOrDefault(blockId, ModelData.EMPTY);
    }

    /**
     * Backward-compatible fallback used by older call sites. This intentionally
     * no longer returns state-wide ModelData because Domum material data is
     * position-specific, not BlockState-specific.
     */
    public static ModelData getModelData(BlockState state) {
        return ModelData.EMPTY;
    }

    private static boolean isDomumBlockEntity(BlockEntity blockEntity) {
        String name = blockEntity.getClass().getName();
        return name.startsWith(DOMUM_PACKAGE);
    }

    private static String extractVariantKey(BlockEntity blockEntity, ModelData modelData) {
        // Domum block entities expose getTextureData(); use it to avoid creating
        // one virtual id per block entity when many blocks share the same material.
        try {
            Method method = blockEntity.getClass().getMethod("getTextureData");
            Object textureData = method.invoke(blockEntity);
            if (textureData != null) {
                return String.valueOf(textureData);
            }
        } catch (Throwable ignored) {
        }

        // Fallback: still make the block render correctly in the current session.
        return modelData.getClass().getName() + "@" + System.identityHashCode(modelData);
    }
}
