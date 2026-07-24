package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import me.cortex.voxy.client.compat.create.KineticCull;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//bits_n_bobs kinetic visuals that override beginFrame - the chain pulley animates its chain links each
//frame, shadowing the base-class cull like Create's own machines do. Same treatment as
//MixinKineticMachineVisuals; @Pseudo because the addon is optional (missing targets skip silently).
@Pseudo
@Mixin(targets = {
        "com.kipti.bnb.content.kinetics.chain_pulley.ChainPulleyVisual"
}, remap = false)
public abstract class MixinBnbKineticVisuals {
    @Unique private boolean voxy$culled;
    @Unique private long voxy$nextCheckTick;

    @Inject(method = "beginFrame(Ldev/engine_room/flywheel/api/visual/DynamicVisual$Context;)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void voxy$cull(DynamicVisual.Context ctx, CallbackInfo ci) {
        BlockPos pos = ((AccessorAbstractBlockEntityVisual) this).voxy$getPos();
        boolean beyond = KineticCull.beyond(pos, ctx, ((AccessorAbstractVisualLevel) this).voxy$getLevel());
        if (beyond) {
            if (!this.voxy$culled) {
                this.voxy$culled = true;
                KineticCull.hide((BlockEntityVisual) this);
                me.cortex.voxy.client.compat.create.KineticSnapshots.queueCapture(pos);
            } else {
                //Re-hide on a throttle beat (block updates rebuild instances visible again)
                long tick = net.minecraft.client.Minecraft.getInstance().level.getGameTime();
                if (tick >= this.voxy$nextCheckTick) {
                    this.voxy$nextCheckTick = tick + 8 + ((pos.getX() ^ pos.getZ()) & 7);
                    KineticCull.hide((BlockEntityVisual) this);
                }
            }
            ci.cancel();
        } else if (this.voxy$culled) {
            KineticCull.show((BlockEntityVisual) this);
            this.voxy$culled = false;
            me.cortex.voxy.client.compat.create.KineticSnapshots.queueRemove(pos);
        }
    }
}
