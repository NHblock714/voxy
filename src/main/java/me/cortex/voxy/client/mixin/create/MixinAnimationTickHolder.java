package me.cortex.voxy.client.mixin.create;

import me.cortex.voxy.client.compat.create.KineticSnapshots;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//Every Create moving-part angle is time*speed*k + positionOffset off this clock, and a capture bakes
//the value into frozen vertices. Captures of one drivetrain are spread across ticks (the sweep walks
//the disk, the budget splits chunks), so each segment would freeze at its own moment - a connected
//shaft line reads as broken at every capture seam. Zeroing the clock for the capturing thread
//collapses every formula to its position term: all frozen machines share one instant, and any two
//connected parts agree. Angles that come from a block entity's own state rather than this clock (the
//bearing's interpolated top-disc angle) are untouched, which is what keeps the disc paired with the
//contraption pose frozen on the same tick. Thread-scoped: Flywheel's frame plan reads this clock
//concurrently from its worker threads and must see real time.
@Mixin(AnimationTickHolder.class)
public class MixinAnimationTickHolder {
    @Inject(method = "getRenderTime()F", at = @At("HEAD"), cancellable = true)
    private static void voxy$freezeCaptureClock(CallbackInfoReturnable<Float> cir) {
        if (KineticSnapshots.isCapturingOnThisThread()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getRenderTime(Lnet/minecraft/world/level/LevelAccessor;)F", at = @At("HEAD"), cancellable = true)
    private static void voxy$freezeCaptureClockForLevel(LevelAccessor level, CallbackInfoReturnable<Float> cir) {
        if (KineticSnapshots.isCapturingOnThisThread()) {
            cir.setReturnValue(0.0f);
        }
    }
}
