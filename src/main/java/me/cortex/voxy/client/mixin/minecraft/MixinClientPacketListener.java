package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Shadow private ClientLevel level;

    @Inject(method = "handleLogin", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;commonPlayerSpawnInfo()Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (!ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionStart();
        }
    }

    //A section-bulk packet is how the server delivers any multi-block change made in one tick - a
    //contraption lifting into an entity, or laying itself back down as blocks. The per-block ingest
    //trigger takes only border air (MixinClientLevel), which leaves most of a machine's footprint
    //un-ingested: the LOD keeps a body that left, or a hole where one parked, and the stale copy
    //shows through wherever the real section draws nothing. The packet is the natural coalescing
    //point - one section re-ingest per section per server tick, bounded by what the server sends.
    //TAIL: the handler has applied every change to the level by then.
    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void voxy$ingestBulkSectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (VoxyCommon.getInstance() == null || !VoxyConfig.CONFIG.ingestEnabled) {
            return;
        }
        var level = this.level;
        if (level == null) {
            return;
        }
        var wi = WorldIdentifier.of(level);
        if (wi == null) {
            return;
        }
        //The packet's section position has no public accessor; any one updated block names it
        SectionPos[] captured = new SectionPos[1];
        packet.runUpdates((pos, state) -> {
            if (captured[0] == null) {
                captured[0] = SectionPos.of(pos);
            }
        });
        if (captured[0] == null) {
            return;
        }
        var csp = captured[0];
        var chunk = level.getChunk(csp.x(), csp.z(), ChunkStatus.FULL, false);
        if (!(chunk instanceof LevelChunk levelChunk)) {
            return;
        }
        var section = levelChunk.getSection(level.getSectionIndexFromSectionY(csp.y()));
        var lp = level.getLightEngine();
        var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
        var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);
        VoxelIngestService.rawIngest(wi, levelChunk, section, csp.x(), csp.y(), csp.z(),
                blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
    }
}
