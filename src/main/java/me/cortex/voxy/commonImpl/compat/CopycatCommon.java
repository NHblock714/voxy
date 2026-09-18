package me.cortex.voxy.commonImpl.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

//Dist-safe half of the copycat integration: class-name recognition and the render-nbt subset.
//Shared by the client compat and the SERVER-side train shape sampler, which must never touch the
//client-only model classes CreateCopycatCompat pulls in.
public final class CopycatCommon {
    private static final boolean LOADED = ModList.get().isLoaded("create");
    static final String CREATE_PREFIX = "com.simibubi.create.content.decoration.copycat";
    static final String ADDON_PREFIX = "com.copycatsplus.copycats";

    private static final ClassValue<Boolean> COPYCAT_CLASSES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName();
            return name.startsWith(CREATE_PREFIX) || name.startsWith(ADDON_PREFIX);
        }
    };

    private CopycatCommon() {
    }

    public static boolean isLoaded() {
        return LOADED;
    }

    public static boolean isCopycatState(BlockState state) {
        if (!LOADED || state == null) {
            return false;
        }
        return COPYCAT_CLASSES.get(state.getBlock().getClass());
    }

    public static boolean isCopycatClass(Object instance) {
        return LOADED && instance != null && COPYCAT_CLASSES.get(instance.getClass());
    }

    //The slice of a copycat block entity's nbt the client needs to rebuild its look (both mods
    //store a single material under "Material"; Copycats+' multi-material blocks store a per-part
    //map under "material_data"). Null when there is nothing worth carrying.
    public static CompoundTag renderNbt(BlockState state, CompoundTag beNbt) {
        if (beNbt == null || !isCopycatState(state)) {
            return null;
        }
        CompoundTag out = null;
        if (beNbt.contains("Material")) {
            out = new CompoundTag();
            out.put("Material", beNbt.getCompound("Material").copy());
        }
        if (beNbt.contains("material_data")) {
            if (out == null) {
                out = new CompoundTag();
            }
            out.put("material_data", beNbt.getCompound("material_data").copy());
        }
        return out;
    }
}
