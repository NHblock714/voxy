package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import me.cortex.voxy.client.compat.create.KineticSnapshots;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//A block change that takes a kinetic block entity out of a loaded chunk - contraption assembly
//picking up a gantry carriage, a machine broken at a distance - leaves any frozen snapshot at that
//position standing over nothing. The fresh-visual hook only covers the opposite direction (a block
//arriving under Flywheel), and the sweep takes up to a full disk pass to find the ghost; the block
//change is the precise moment. Chunk unload does not route through removeBlockEntity
//(clearAllBlockEntities walks the map directly), so leave-behinds in unloading chunks are untouched
//and the unload-time capture keeps its ordering.
@Mixin(LevelChunk.class)
public abstract class MixinLevelChunkKineticRemoval {
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void voxy$dropSnapshotWithBlock(BlockPos pos, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (self.getLevel().isClientSide()
                && self.getBlockEntity(pos) instanceof KineticBlockEntity) {
            KineticSnapshots.queueRemove(pos);
        }
    }
}
