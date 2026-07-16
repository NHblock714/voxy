package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//Exposes AbstractEntityVisual's own `entity` field (erased to Entity) for the distance check in the
//CarriageContraptionVisual mixin. An inherited @Shadow does not resolve there; an accessor on the
//declaring class does.
@Mixin(AbstractEntityVisual.class)
public interface AccessorAbstractEntityVisual {
    @Accessor("entity")
    Entity voxy$getEntity();
}
