package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SelectTableResponsePayload(
        String table,
        int page,
        boolean success,
        boolean hasNext,
        List<QueryLine> lines,
        String messageKey,
        List<String> messageArgs
) implements CustomPacketPayload {
    private static final int MAX_LINES = 64;
    private static final int MAX_COLUMN_NAME_LENGTH = 64;
    private static final int MAX_LINE_LENGTH = 1200;
    private static final int MAX_MESSAGE_KEY_LENGTH = 128;
    private static final int MAX_MESSAGE_ARGS = 8;

    public static final Type<SelectTableResponsePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "select_table_response")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectTableResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SelectTableResponsePayload decode(RegistryFriendlyByteBuf buf) {
            String table = buf.readUtf(64);
            int page = buf.readVarInt();
            boolean success = buf.readBoolean();
            boolean hasNext = buf.readBoolean();
            String messageKey = buf.readUtf(MAX_MESSAGE_KEY_LENGTH);
            int messageArgCount = buf.readVarInt();
            if (messageArgCount < 0 || messageArgCount > MAX_MESSAGE_ARGS) {
                throw new IllegalArgumentException("Invalid WorldLogger response message arg count: " + messageArgCount);
            }

            List<String> messageArgs = new ArrayList<>(messageArgCount);
            for (int i = 0; i < messageArgCount; i++) {
                messageArgs.add(buf.readUtf(MAX_LINE_LENGTH));
            }

            int lineCount = buf.readVarInt();
            if (lineCount < 0 || lineCount > MAX_LINES) {
                throw new IllegalArgumentException("Invalid WorldLogger response line count: " + lineCount);
            }

            List<QueryLine> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                lines.add(new QueryLine(buf.readUtf(MAX_COLUMN_NAME_LENGTH), buf.readUtf(MAX_LINE_LENGTH)));
            }
            return new SelectTableResponsePayload(table, page, success, hasNext, lines, messageKey, messageArgs);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SelectTableResponsePayload payload) {
            List<String> messageArgs = payload.messageArgs().stream()
                    .limit(MAX_MESSAGE_ARGS)
                    .map(SelectTableResponsePayload::trimLine)
                    .toList();
            List<QueryLine> lines = payload.lines().stream()
                    .limit(MAX_LINES)
                    .toList();

            buf.writeUtf(payload.table(), 64);
            buf.writeVarInt(payload.page());
            buf.writeBoolean(payload.success());
            buf.writeBoolean(payload.hasNext());
            buf.writeUtf(trimMessageKey(payload.messageKey()), MAX_MESSAGE_KEY_LENGTH);
            buf.writeVarInt(messageArgs.size());
            for (String messageArg : messageArgs) {
                buf.writeUtf(messageArg, MAX_LINE_LENGTH);
            }

            buf.writeVarInt(lines.size());
            for (QueryLine line : lines) {
                buf.writeUtf(trimColumnName(line.columnName()), MAX_COLUMN_NAME_LENGTH);
                buf.writeUtf(trimLine(line.value()), MAX_LINE_LENGTH);
            }
        }
    };

    public SelectTableResponsePayload {
        page = Math.max(1, page);
        lines = List.copyOf(lines);
        messageKey = messageKey == null ? "" : messageKey;
        messageArgs = List.copyOf(messageArgs);
    }

    public static SelectTableResponsePayload success(String table, int page, boolean hasNext, List<QueryLine> lines) {
        return new SelectTableResponsePayload(table, page, true, hasNext, lines, "", List.of());
    }

    public static SelectTableResponsePayload error(String table, int page, String messageKey, String... messageArgs) {
        return new SelectTableResponsePayload(table, page, false, false, List.of(), messageKey, List.of(messageArgs));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static String trimMessageKey(String messageKey) {
        if (messageKey.length() <= MAX_MESSAGE_KEY_LENGTH) {
            return messageKey;
        }
        return messageKey.substring(0, MAX_MESSAGE_KEY_LENGTH);
    }

    private static String trimColumnName(String columnName) {
        if (columnName.length() <= MAX_COLUMN_NAME_LENGTH) {
            return columnName;
        }
        return columnName.substring(0, MAX_COLUMN_NAME_LENGTH);
    }

    private static String trimLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH - 3) + "...";
    }

    public record QueryLine(String columnName, String value) {}
}
