package me.cortex.voxy.client.mixin;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean connectorInstalled = false;
    private static boolean sableInstalled;
    private static boolean eclipticSeasonsInstalled;

    private static boolean isLoadedEarly(String modId) {
        var list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = isLoadedEarly("valkyrienskies");
        nvidiumInstalled = isLoadedEarly("nvidium");
        connectorInstalled = isLoadedEarly("connector");
        sableInstalled = isLoadedEarly("sable");
        eclipticSeasonsInstalled = isLoadedEarly("eclipticseasons");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        // client.voxy.mixins.json is entirely client-rendering (sodium/iris/sable/eclipticseasons targets).
        // None of it applies on a dedicated server and the targets don't exist there, so add nothing server-side.
        if (FMLLoader.getDist() != Dist.CLIENT) {
            return mixins;
        }
        //(sable.MixinSableSubLevelRenderSectionManager omitted: its sable target class was removed in
        // sable 2.0.3 and its sodium ctor target no longer matches sodium 0.8.12.)
        if (sableInstalled) {
            mixins.add("minecraft.MixinGameRendererSableRenderDistance");
            mixins.add("sable.MixinSableReacharoundCulling");
            mixins.add("sable.MixinSableDepthShim");
        }
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        // EclipticSeasons snow-LOD compat: client-gated even for the common-class targets, because the shared
        // VoxyTool references EclipticSeasons client classes (ClientCon) and our delta-sync server also runs ingest.
        if (eclipticSeasonsInstalled && FMLLoader.getDist() == Dist.CLIENT) {
            mixins.add("eclipticseasons.MixinClientLevel");
            mixins.add("eclipticseasons.MixinMapping");
            mixins.add("eclipticseasons.MixinModelBakerySubsystem");
            mixins.add("eclipticseasons.MixinModelFactory");
            mixins.add("eclipticseasons.MixinModelTextureBakery");
            mixins.add("eclipticseasons.MixinWorldConversionFactory");
            mixins.add("eclipticseasons.MixinWorldImporter");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
