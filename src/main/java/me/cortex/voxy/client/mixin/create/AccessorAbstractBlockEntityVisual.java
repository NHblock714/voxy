package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//Exposes AbstractBlockEntityVisual's own `pos` field (the absolute BlockPos) for the distance check in
//the kinetic culls. The machine visuals sit several levels below this declaring class and an inherited
//@Shadow of a field that deep is fragile; an accessor on the declaring class resolves cleanly for every
//subclass.
@Mixin(AbstractBlockEntityVisual.class)
public interface AccessorAbstractBlockEntityVisual {
    @Accessor("pos")
    BlockPos voxy$getPos();
}
