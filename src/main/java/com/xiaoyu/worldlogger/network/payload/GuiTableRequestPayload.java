package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record GuiTableRequestPayload(String table, int page, String filter, int requestId) implements CustomPacketPayload {
    private static final int MAX_TABLE_LENGTH = 64;
    private static final int MAX_FILTER_LENGTH = 256;

    public static final Type<GuiTableRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "gui_table_request")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, GuiTableRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GuiTableRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new GuiTableRequestPayload(
                    buf.readUtf(MAX_TABLE_LENGTH),
                    buf.readVarInt(),
                    buf.readUtf(MAX_FILTER_LENGTH),
                    buf.readVarInt()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, GuiTableRequestPayload payload) {
            buf.writeUtf(trim(payload.table(), MAX_TABLE_LENGTH), MAX_TABLE_LENGTH);
            buf.writeVarInt(payload.page());
            buf.writeUtf(trim(payload.filter(), MAX_FILTER_LENGTH), MAX_FILTER_LENGTH);
            buf.writeVarInt(payload.requestId());
        }
    };

    public GuiTableRequestPayload {
        table = table == null ? "" : table;
        page = Math.max(1, page);
        filter = filter == null ? "" : trim(filter, MAX_FILTER_LENGTH);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
