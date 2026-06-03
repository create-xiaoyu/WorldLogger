package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SelectTableRequestPayload(String table, int page) implements CustomPacketPayload {
    public static final Type<SelectTableRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "select_table_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectTableRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SelectTableRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new SelectTableRequestPayload(buf.readUtf(64), buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SelectTableRequestPayload payload) {
            buf.writeUtf(payload.table(), 64);
            buf.writeVarInt(payload.page());
        }
    };

    public SelectTableRequestPayload {
        page = Math.max(1, page);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
