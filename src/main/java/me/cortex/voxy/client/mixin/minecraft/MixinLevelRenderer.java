package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxyRenderSystem renderer;
    //True while a VoxyRenderSystem is being constructed on this stack. Iris can call allChanged()
    //synchronously from inside that construction when LOD-pipeline creation trips its shader
    //fallback - without the guard the re-entry builds a complete second render system and one of
    //the two is orphaned: an acquired world ref that never releases (world exit hangs) and the
    //engine's single dirty-callback slot stolen from the live renderer (LOD stops re-meshing).
    @Unique private boolean voxy$creatingRenderer;

    @Override
    public VoxyRenderSystem voxy$getRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)//We want to inject before sodium
    private void voxy$reloadVoxyRenderer(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
        if (this.level != null) {
            this.voxy$createRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        if (this.level != world) {
            this.voxy$shutdownRenderer();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void voxy$injectClose(CallbackInfo ci) {
        this.voxy$shutdownRenderer();
    }

    @Override
    public void voxy$shutdownRenderer() {
        //The seasonal snow walk holds a reference on the world engine, which is what keeps the engine
        //from being freed under it. Stop it when the world goes: rewriting LOD for a level we are
        //leaving buys nothing, and the reference would hold the engine past its usefulness.
        //Guarded so the class - which reaches into EclipticSeasons - is never resolved without it.
        if (net.neoforged.fml.ModList.get().isLoaded("eclipticseasons")) {
            me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalSnowRefresher.cancelAndJoin();
        }
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void voxy$createRenderer() {
        if (this.voxy$creatingRenderer) {
            Logger.warn("Suppressed re-entrant voxy renderer creation");
            return;
        }
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.enabled) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled rendering");
            return;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.level);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        boolean fallbackToNoShaders = false;
        this.voxy$creatingRenderer = true;
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                fallbackToNoShaders = true;
            } else {
                throw e;
            }
        } finally {
            this.voxy$creatingRenderer = false;
        }
        if (fallbackToNoShaders) {
            //After the flag clears: the allChanged this triggers is the load-bearing re-entry
            //that builds the shaderless fallback renderer
            IrisUtil.disableIrisShaders();
        }
        instance.updateDedicatedThreads();
    }
}
