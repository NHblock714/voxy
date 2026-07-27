package me.cortex.voxy.client.mixin.sable;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import me.cortex.voxy.client.compat.ShipBorne;
import me.cortex.voxy.client.compat.sable.SableScreenBounds;
import me.cortex.voxy.client.compat.sable.VoxySableDepthShim;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class MixinSableDepthShim {
    //begin/end must stay paired - skipping begin without skipping end would leave the closing
    //write-back pass running against a merge that never happened
    @org.spongepowered.asm.mixin.Unique
    private boolean voxy$shimActive;

    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void voxy$beginCombinedDepth(
            Iterable<ClientSubLevel> subLevels,
            RenderType renderType,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            float partialTicks,
            CallbackInfo ci
    ) {
        //This fires once per chunk layer, ~5 times a frame, and the shim costs four gl_FragDepth passes
        //each time - which also defeat early-Z for the pass. With no sub-level present there is nothing
        //whose depth could need merging, so skip it. Only ever reachable with a shaderpack loaded: that
        //is the sole condition under which the depth texture is non-zero.
        this.voxy$shimActive = subLevels != null && subLevels.iterator().hasNext() && ShipBorne.anyShipPresent();
        if (!this.voxy$shimActive) {
            return;
        }

        //The shadow pass re-runs this whole dispatch with the shadow map's matrices. Merging LOD depth
        //captured from the camera into a shadow map is meaningless, and paying for it doubles the shim
        //on any pack with shadows enabled.
        if (IrisUtil.irisShadowActive()) {
            this.voxy$shimActive = false;
            VoxySableDepthShim.shadowPassesSkipped++;
            return;
        }

        //Two reductions, in order of what they save: drop plots LOD can never get in front of, then
        //bound the blits to where what is left actually draws
        SableScreenBounds.Result bounds = SableScreenBounds.of(subLevels, cameraX, cameraY, cameraZ, modelView, projection);
        if (bounds.skip() != SableScreenBounds.Skip.NONE) {
            this.voxy$shimActive = false;
            if (bounds.skip() == SableScreenBounds.Skip.ALL_NEAR) {
                VoxySableDepthShim.nearPassesSkipped++;
            } else {
                VoxySableDepthShim.offscreenPassesSkipped++;
            }
            return;
        }
        VoxySableDepthShim.begin(modelView, projection, bounds.ndc());
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void voxy$endCombinedDepth(
            Iterable<ClientSubLevel> subLevels,
            RenderType renderType,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            float partialTicks,
            CallbackInfo ci
    ) {
        if (!this.voxy$shimActive) {
            return;
        }
        this.voxy$shimActive = false;
        VoxySableDepthShim.end();
    }
}
