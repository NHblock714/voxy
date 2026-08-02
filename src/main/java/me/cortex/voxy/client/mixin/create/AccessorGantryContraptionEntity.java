package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.gantry.GantryContraptionEntity;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//The travel direction of a gantry carriage - together with the entity position it names the rail the
//crane runs on, an identity that outlives the entity itself (a fresh UUID is minted every assembly;
//no getter upstream).
@Mixin(GantryContraptionEntity.class)
public interface AccessorGantryContraptionEntity {
    @Accessor("movementAxis")
    Direction voxy$getMovementAxis();
}
