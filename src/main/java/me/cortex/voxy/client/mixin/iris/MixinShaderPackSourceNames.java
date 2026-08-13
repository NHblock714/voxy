package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ShaderPackSourceNames.class, remap = false)
public class MixinShaderPackSourceNames {
    @WrapOperation(method = "findPotentialStarts", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableList;builder()Lcom/google/common/collect/ImmutableList$Builder;"))
    private static ImmutableList.Builder<String> voxy$injectVoxyShaderPatch(Operation<ImmutableList.Builder<String>> original){
        var builder = original.call();
        builder.add("voxy.json");
        builder.add("voxy_opaque.glsl");
        builder.add("voxy_translucent.glsl");
        //Lite-shading contract (IrisShaderPatch#makePatch): the source provider can only hand back
        //files that are nodes of the include graph, and the graph is built from this candidate list.
        //Without these two names the lite lookup always returns null and silently falls back to the
        //standard program. Candidates that a pack does not ship are skipped, so packs without lite
        //files are unaffected.
        builder.add("voxy_opaque_lite.glsl");
        builder.add("voxy_translucent_lite.glsl");
        return builder;
    }
}
