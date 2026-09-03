package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.api.event.SolarTermChangeEvent;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

public class VoxyEsHandler {
    public static final VoxyEsHandler INSTANCE = new VoxyEsHandler();

    @SubscribeEvent
    public void onSolarTermChangeEvent(SolarTermChangeEvent event) {
        if (event.getLevel() != Minecraft.getInstance().level) {
            return;
        }
        //The per-block seasonal verdicts are keyed to the term that computed them; a term change
        //invalidates every one of them regardless of whether a rebuild follows
        SeasonalMeshView.clearJudgementCaches();
        if (VoxyConfig.CONFIG.eclipticSeasonsReloadOnSeasonChange) {
            try {
                IGetVoxyRenderSystem levelRenderer = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                levelRenderer.voxy$shutdownRenderer();
                levelRenderer.voxy$createRenderer();
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        //The season definitions and snow tables are datapack-driven and the next server may carry
        //different ones under the same block ids
        SeasonalMeshView.clearJudgementCaches();
    }
}
