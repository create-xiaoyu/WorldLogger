package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

public record GuiTableResponsePayload(
        String table,
        int page,
        int requestId,
        boolean success,
        boolean hasNext,
        List<QueryCell> cells,
        String messageKey,
        List<String> messageArgs
) implements CustomPacketPayload {
    private static final int MAX_TABLE_LENGTH = 64;
    private static final int MAX_COLUMN_COUNT = 96;
    private static final int MAX_COLUMN_NAME_LENGTH = 64;
    private static final int MAX_VALUE_CHUNK_LENGTH = 30000;
    private static final int MAX_VALUE_CHUNKS = 8;
    private static final int MAX_MESSAGE_KEY_LENGTH = 128;
    private static final int MAX_MESSAGE_ARGS = 8;
    private static final int MAX_MESSAGE_ARG_LENGTH = 1200;
    private static final String TRUNCATED_SUFFIX = "\n...";

    public static final Type<GuiTableResponsePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "gui_table_response")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, GuiTableResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GuiTableResponsePayload decode(RegistryFriendlyByteBuf buf) {
            String table = buf.readUtf(MAX_TABLE_LENGTH);
            int page = buf.readVarInt();
            int requestId = buf.readVarInt();
            boolean success = buf.readBoolean();
            boolean hasNext = buf.readBoolean();
            String messageKey = buf.readUtf(MAX_MESSAGE_KEY_LENGTH);
            List<String> messageArgs = readMessageArgs(buf);

            int cellCount = buf.readVarInt();
            if (cellCount < 0 || cellCount > MAX_COLUMN_COUNT) {
                throw new IllegalArgumentException("Invalid WorldLogger GUI cell count: " + cellCount);
            }

            List<QueryCell> cells = new ArrayList<>(cellCount);
            for (int i = 0; i < cellCount; i++) {
                String columnName = buf.readUtf(MAX_COLUMN_NAME_LENGTH);
                cells.add(new QueryCell(columnName, readChunkedValue(buf)));
            }

            return new GuiTableResponsePayload(table, page, requestId, success, hasNext, cells, messageKey, messageArgs);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, GuiTableResponsePayload payload) {
            List<QueryCell> cells = payload.cells().stream()
                    .limit(MAX_COLUMN_COUNT)
                    .toList();

            buf.writeUtf(trim(payload.table(), MAX_TABLE_LENGTH), MAX_TABLE_LENGTH);
            buf.writeVarInt(payload.page());
            buf.writeVarInt(payload.requestId());
            buf.writeBoolean(payload.success());
            buf.writeBoolean(payload.hasNext());
            buf.writeUtf(trim(payload.messageKey(), MAX_MESSAGE_KEY_LENGTH), MAX_MESSAGE_KEY_LENGTH);
            writeMessageArgs(buf, payload.messageArgs());

            buf.writeVarInt(cells.size());
            for (QueryCell cell : cells) {
                buf.writeUtf(trim(cell.columnName(), MAX_COLUMN_NAME_LENGTH), MAX_COLUMN_NAME_LENGTH);
                writeChunkedValue(buf, cell.value());
            }
        }
    };

    public GuiTableResponsePayload {
        table = table == null ? "" : table;
        page = Math.max(1, page);
        cells = List.copyOf(cells);
        messageKey = messageKey == null ? "" : messageKey;
        messageArgs = List.copyOf(messageArgs);
    }

    public static GuiTableResponsePayload success(String table, int page, int requestId, boolean hasNext, List<QueryCell> cells) {
        return new GuiTableResponsePayload(table, page, requestId, true, hasNext, cells, "", List.of());
    }

    public static GuiTableResponsePayload error(String table, int page, int requestId, String messageKey, String... messageArgs) {
        return new GuiTableResponsePayload(table, page, requestId, false, false, List.of(), messageKey, List.of(messageArgs));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static List<String> readMessageArgs(RegistryFriendlyByteBuf buf) {
        int argCount = buf.readVarInt();
        if (argCount < 0 || argCount > MAX_MESSAGE_ARGS) {
            throw new IllegalArgumentException("Invalid WorldLogger GUI message arg count: " + argCount);
        }

        List<String> args = new ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            args.add(buf.readUtf(MAX_MESSAGE_ARG_LENGTH));
        }
        return args;
    }

    private static void writeMessageArgs(RegistryFriendlyByteBuf buf, List<String> args) {
        List<String> trimmedArgs = args.stream()
                .limit(MAX_MESSAGE_ARGS)
                .map(arg -> trim(arg, MAX_MESSAGE_ARG_LENGTH))
                .toList();

        buf.writeVarInt(trimmedArgs.size());
        for (String arg : trimmedArgs) {
            buf.writeUtf(arg, MAX_MESSAGE_ARG_LENGTH);
        }
    }

    private static String readChunkedValue(RegistryFriendlyByteBuf buf) {
        int chunkCount = buf.readVarInt();
        if (chunkCount < 0 || chunkCount > MAX_VALUE_CHUNKS) {
            throw new IllegalArgumentException("Invalid WorldLogger GUI value chunk count: " + chunkCount);
        }

        StringBuilder value = new StringBuilder();
        for (int i = 0; i < chunkCount; i++) {
            value.append(buf.readUtf(MAX_VALUE_CHUNK_LENGTH));
        }
        return value.toString();
    }

    private static void writeChunkedValue(RegistryFriendlyByteBuf buf, String value) {
        List<String> chunks = splitValue(value);
        buf.writeVarInt(chunks.size());
        for (String chunk : chunks) {
            buf.writeUtf(chunk, MAX_VALUE_CHUNK_LENGTH);
        }
    }

    private static List<String> splitValue(String value) {
        String normalized = value == null ? "null" : value;
        int maxLength = MAX_VALUE_CHUNK_LENGTH * MAX_VALUE_CHUNKS;
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
        }

        if (normalized.isEmpty()) {
            return List.of("");
        }

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length(); start += MAX_VALUE_CHUNK_LENGTH) {
            chunks.add(normalized.substring(start, Math.min(normalized.length(), start + MAX_VALUE_CHUNK_LENGTH)));
        }
        return chunks;
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

    public record QueryCell(String columnName, String value) {}
}
