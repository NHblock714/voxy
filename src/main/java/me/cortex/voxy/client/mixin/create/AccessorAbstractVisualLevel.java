package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//The level a visual belongs to. A visual on a contraption is built against Create's virtual render
//world, not the client level, which is the only reliable way to tell the two apart: its position is in
//contraption-local space and can coincide with anything.
@Mixin(AbstractVisual.class)
public interface AccessorAbstractVisualLevel {
    @Accessor("level")
    Level voxy$getLevel();
}
