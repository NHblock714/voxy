package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionDisassemblyPacket;
import me.cortex.voxy.client.compat.create.DistantContraptionManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Entity removal is ambiguous (tracker release vs death), so snapshot cleanup normally rides a 2s
//presence grace - which shows as a ~1s ghost right where a contraption just DISASSEMBLED into
//blocks (entity gone -> live=false -> the renderer stops yielding and draws the frozen copy until
//the grace expires, even up close). Disassembly however has its own explicit signal: this packet.
//Drop the snapshot the moment it arrives; the presence grace stays as the fallback for lost packets.
//
//Only where the blocks themselves are synced: entity tracking reaches far past the chunk-send
//distance, so this packet can describe a disassembly whose re-placed blocks this client never
//receives - deleting there leaves a hole in the LOD where a structure really stands. That case
//retires the snapshot into a leave-behind at the resting pose instead.
@Mixin(AbstractContraptionEntity.class)
public class MixinContraptionDisassembly {
    @Inject(method = "handleDisassemblyPacket", at = @At("HEAD"))
    private static void voxy$dropSnapshotOnDisassembly(ContraptionDisassemblyPacket packet, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        var entity = level.getEntity(packet.entityId());
        if (entity == null) {
            return;
        }
        if (level.isLoaded(entity.blockPosition())) {
            DistantContraptionManager.removeDead(entity.getUUID());
            if (entity instanceof AbstractContraptionEntity ce) {
                //The placed blocks broadcast one server tick after this packet: a deferred pass over
                //the structure's own bounds (the entity's box is not the body) reads the settled
                //sections, so the terrain LOD gains the body the machine just became instead of
                //keeping a hole until the next chunk reload.
                var contraption = ce.getContraption();
                var box = contraption != null && contraption.bounds != null
                        ? contraption.bounds.move(ce.getX(), ce.getY(), ce.getZ()).inflate(1.0)
                        : ce.getBoundingBox().inflate(1.0);
                me.cortex.voxy.client.compat.create.SectionReingestQueue.scheduleBox(level, box, 5);
            }
        } else if (entity instanceof AbstractContraptionEntity ce) {
            DistantContraptionManager.retireToLeaveBehind(ce);
        }
    }
}
