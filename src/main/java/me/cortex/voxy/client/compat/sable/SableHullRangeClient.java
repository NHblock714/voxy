package me.cortex.voxy.client.compat.sable;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableHullRangeProtocol;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

//Announces this client's sable hull preference to the server it is on. The channel is the
//same-version one, so a connection that got this far has a server that understands it.
public final class SableHullRangeClient {
    private SableHullRangeClient() {}

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        send();
    }

    public static void send() {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        var config = VoxyConfig.CONFIG;
        PacketDistributor.sendToServer(new SableHullRangeProtocol.HullRangePayload(
                config.isRenderingEnabled() && config.sableLodRendering,
                config.sectionRenderDistance,
                config.simulatedContraptionRenderDistancePercent));
    }
}
