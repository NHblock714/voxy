package me.cortex.voxy.client.mixin.iris;

import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//Iris tracks sampler bindings in this private array and skips a rebind when the entry already
//matches - so clearing the GL sampler state alone leaves iris convinced its sampler is still bound,
//and its next pass runs with voxy's cleared one. Zeroing the array directly replaces sixteen
//per-frame bindSamplerToUnit round trips with plain writes (no getter upstream).
@Mixin(value = IrisRenderSystem.class, remap = false)
public interface IrisRenderSystemAccessor {
    @Accessor("samplers")
    static int[] voxy$getSamplerBindings() {
        throw new AssertionError();
    }
}
