package me.cortex.voxy;

import me.cortex.voxy.client.config.VoxyNeoForgeConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * Main mod class for Voxy on NeoForge.
 *
 * Handles config registration and config screen setup.
 * Actual initialization happens via mixins (MixinRenderSystem).
 */
@Mod("voxy")
public class Voxy {
    public static final String MODID = "voxy";

    public Voxy(IEventBus modEventBus, ModContainer container) {
        //Terrain streaming is handled by the external VSS mod; on a dedicated server voxy only
        //provides the sable contraption ticket hook (MixinServerLevel). Everything else is client side.

        // Only register client config on client side
        if (FMLLoader.getDist() == Dist.CLIENT) {
            // Register NeoForge config
            VoxyNeoForgeConfig.register(container);

            // Register the built-in NeoForge config screen
            container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

            // Voxy's Sodium video-settings page is registered by VoxyConfigMenu (@ConfigEntryPointForge,
            // Sodium 0.8 native config API), not here.

            // EclipticSeasons compat: rebuild the LOD renderer on season change. Gated on the mod being present
            // so the snow-LOD code (which references EclipticSeasons client classes) never loads without it.
            if (ModList.get().isLoaded("eclipticseasons")) {
                NeoForge.EVENT_BUS.register(me.cortex.voxy.client.core.compat.eclipticseasons.VoxyEsHandler.INSTANCE);
            }
        }
    }
}
