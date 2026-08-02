package me.cortex.voxy.client.compat.create;

import net.minecraft.world.entity.Entity;

//Ground truth for "is the Flywheel pipeline actually holding a visual for this entity". The api's
//supportsVisualization only says the backend is on for the level; nowheel deletes an EC-culled
//entity's visual outright and blocks re-creation while the cull holds, so backend-on does not imply
//drawn. Reads flywheel impl internals through an accessor; any linkage break fails toward "has a
//visual" - the yield then behaves as if the backend draws, which can at worst double an image,
//never blank one.
public final class FlywheelVisuals {
    private static boolean unavailable;

    private FlywheelVisuals() {}

    public static boolean hasVisual(Entity entity) {
        if (unavailable) {
            return true;
        }
        try {
            var manager = dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.get(entity.level());
            if (manager == null) {
                return false;
            }
            var entities = (dev.engine_room.flywheel.impl.visualization.VisualManagerImpl<?, ?>) manager.entities();
            return ((me.cortex.voxy.client.mixin.create.AccessorFlywheelStorage) entities.getStorage())
                    .voxy$getVisuals().containsKey(entity);
        } catch (LinkageError | ClassCastException e) {
            unavailable = true;
            return true;
        }
    }
}
