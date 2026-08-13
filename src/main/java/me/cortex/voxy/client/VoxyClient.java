package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        boolean systemSupported = Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters && !Capabilities.INSTANCE.hasBrokenDepthSampler;
        if (!systemSupported) {
             Logger.error("Voxy is unsupported on your system.");
        }

        if (systemSupported && System.getProperty("voxy.exclusiveLock", "false").equalsIgnoreCase("true")) {
            //Try acquire the lock file
            var vf = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
            if (!vf.toFile().isDirectory()) {
                vf.toFile().mkdir();
            }
            try {
                FileOutputStream fis = new FileOutputStream(vf.resolve("voxy.lock").toFile());
                EXCLUSIVE_LOCK = fis.getChannel().lock(0, Long.MAX_VALUE, false);
            } catch (NonWritableChannelException | IOException e) {
                //If some error write to log and unsupport
                Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
                systemSupported = false;
            }

        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            //Seasonal snow LOD: direct primitive-signature hook into the convert loop. VoxyTool's
            //method bodies reference EclipticSeasons classes, so the registration only forms behind
            //the mod-presence gate (linking is lazy until first call, which this guarantees).
            //LoadingModList, not ModList: this runs from the RenderSystem init mixin, before
            //ModList.get() exists - the mixin plugin gates on the same early list.
            var loadingList = net.neoforged.fml.loading.LoadingModList.get();
            if (loadingList != null && loadingList.getModFileById("eclipticseasons") != null) {
                me.cortex.voxy.common.voxelization.WorldConversionFactory.blockIdRemapper =
                        new me.cortex.voxy.common.voxelization.WorldConversionFactory.BlockIdRemapper() {
                            @Override
                            public void beginSection(me.cortex.voxy.common.voxelization.VoxelizedSection section,
                                                     me.cortex.voxy.common.world.other.Mapper mapper,
                                                     me.cortex.voxy.common.voxelization.ILightingSupplier lightSupplier) {
                                me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool.beginSection(section, mapper, lightSupplier);
                            }

                            @Override
                            public int remap(int blockId, int voxelIdx, int biomeId) {
                                return me.cortex.voxy.client.core.compat.eclipticseasons.VoxyTool.changeBlockId(blockId, voxelIdx, biomeId);
                            }
                        };
            }

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
