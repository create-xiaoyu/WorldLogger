package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SearchRequestPayload(int page) implements CustomPacketPayload {
    public static final Type<SearchRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "search_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SearchRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SearchRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new SearchRequestPayload(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SearchRequestPayload payload) {
            buf.writeVarInt(payload.page());
        }
    };

    public SearchRequestPayload {
        page = Math.max(1, page);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
