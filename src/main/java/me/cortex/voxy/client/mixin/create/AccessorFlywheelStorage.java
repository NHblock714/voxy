package me.cortex.voxy.client.mixin.create;

import dev.engine_room.flywheel.impl.visualization.storage.Storage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

//Whether a Visual object exists for an entity right now - the ground truth the snapshot yield needs
//(no getter upstream). The api surface only says the backend is on for the level; a per-entity
//culler (nowheel) deletes individual visuals while the backend keeps running.
@Mixin(Storage.class)
public interface AccessorFlywheelStorage {
    @Accessor("visuals")
    Map<?, ?> voxy$getVisuals();
}
