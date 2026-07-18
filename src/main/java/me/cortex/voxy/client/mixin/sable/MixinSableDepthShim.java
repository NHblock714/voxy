package me.cortex.voxy.client.mixin.sable;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import me.cortex.voxy.client.compat.ShipBorne;
import me.cortex.voxy.client.compat.sable.VoxySableDepthShim;
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
        //Same guard the Flywheel sibling has carried all along (MixinVisualizationManagerImpl): with no
        //sub-level in this pass there is nothing whose depth could need merging, and the shim's four
        //fullscreen gl_FragDepth passes are not free - they also defeat early-Z for the whole pass.
        //This fires ~5 times a frame (one per chunk layer), so an unguarded shim was ~20 fullscreen
        //depth passes per frame in every world, and only ever with a shaderpack loaded, since that is
        //the sole condition under which its depth texture is non-zero.
        this.voxy$shimActive = subLevels != null && subLevels.iterator().hasNext() && ShipBorne.anyShipPresent();
        if (!this.voxy$shimActive) {
            return;
        }
        VoxySableDepthShim.begin(modelView, projection);
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
