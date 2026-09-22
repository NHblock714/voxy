package me.cortex.voxy.commonImpl.compat.sable;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

//The client's sable hull preference - the same three values the integrated server reads straight
//out of the host's config - sent on login and on every config save so a dedicated server can track
//ships to each player over the LOD range instead of sable's own default. The server applies its
//view distance and ceiling; the values themselves are only a request.
public final class SableHullRangeProtocol {
    private SableHullRangeProtocol() {}

    public record HullRangePayload(boolean enabled, float sectionRenderDistance, int percent) implements CustomPacketPayload {
        public static final Type<HullRangePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("voxy", "sable_hull_range"));
        public static final StreamCodec<ByteBuf, HullRangePayload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, HullRangePayload::enabled,
                ByteBufCodecs.FLOAT, HullRangePayload::sectionRenderDistance,
                ByteBufCodecs.VAR_INT, HullRangePayload::percent,
                HullRangePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
