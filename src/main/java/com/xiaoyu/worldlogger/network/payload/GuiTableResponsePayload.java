package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器返回给客户端 GUI 的表格查询结果包。
 *
 * <p>GUI 需要显示原始长文本，例如 JSON、NBT、聊天组件字符串。
 * Minecraft 单个网络字符串有长度限制，所以这里把超长 value 切成多个 chunk 传输。</p>
 *
 * @param table 表名。
 * @param page 页码。
 * @param requestId 请求编号，用来匹配最新请求。
 * @param success 是否成功。
 * @param hasNext 是否有下一页。
 * @param cells 当前页的列数据。
 * @param messageKey 错误提示语言 key。
 * @param messageArgs 错误提示参数。
 */
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
    /** 表名最大长度。 */
    private static final int MAX_TABLE_LENGTH = 64;

    /** 单行记录最多允许携带的列数。 */
    private static final int MAX_COLUMN_COUNT = 96;

    /** 列名最大长度。 */
    private static final int MAX_COLUMN_NAME_LENGTH = 64;

    /** 单个文本分块最大长度。 */
    private static final int MAX_VALUE_CHUNK_LENGTH = 30000;

    /** 单个字段最多允许拆成几个分块。 */
    private static final int MAX_VALUE_CHUNKS = 8;

    /** 错误语言 key 最大长度。 */
    private static final int MAX_MESSAGE_KEY_LENGTH = 128;

    /** 错误语言参数最大数量。 */
    private static final int MAX_MESSAGE_ARGS = 8;

    /** 错误语言参数最大长度。 */
    private static final int MAX_MESSAGE_ARG_LENGTH = 1200;

    /** 字段过长被截断时追加的后缀。 */
    private static final String TRUNCATED_SUFFIX = "\n...";

    /** 网络包类型 ID。 */
    public static final Type<GuiTableResponsePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "gui_table_response")
    );

    /** GUI 响应包编码/解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, GuiTableResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GuiTableResponsePayload decode(RegistryFriendlyByteBuf buf) {
            // 读取基础状态。
            String table = buf.readUtf(MAX_TABLE_LENGTH);
            int page = buf.readVarInt();
            int requestId = buf.readVarInt();
            boolean success = buf.readBoolean();
            boolean hasNext = buf.readBoolean();
            String messageKey = buf.readUtf(MAX_MESSAGE_KEY_LENGTH);
            List<String> messageArgs = readMessageArgs(buf);

            // 读取单元格数量，并限制最大值，防止异常包导致内存压力。
            int cellCount = buf.readVarInt();
            if (cellCount < 0 || cellCount > MAX_COLUMN_COUNT) {
                throw new IllegalArgumentException("Invalid WorldLogger GUI cell count: " + cellCount);
            }

            // 每个单元格由列名和一个可能分块传输的值组成。
            List<QueryCell> cells = new ArrayList<>(cellCount);
            for (int i = 0; i < cellCount; i++) {
                String columnName = buf.readUtf(MAX_COLUMN_NAME_LENGTH);
                cells.add(new QueryCell(columnName, readChunkedValue(buf)));
            }

            return new GuiTableResponsePayload(table, page, requestId, success, hasNext, cells, messageKey, messageArgs);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, GuiTableResponsePayload payload) {
            // GUI 一次只显示一条记录，但仍然限制列数，避免异常表结构导致包过大。
            List<QueryCell> cells = payload.cells().stream()
                    .limit(MAX_COLUMN_COUNT)
                    .toList();

            // 写入基础状态和错误信息。
            buf.writeUtf(trim(payload.table(), MAX_TABLE_LENGTH), MAX_TABLE_LENGTH);
            buf.writeVarInt(payload.page());
            buf.writeVarInt(payload.requestId());
            buf.writeBoolean(payload.success());
            buf.writeBoolean(payload.hasNext());
            buf.writeUtf(trim(payload.messageKey(), MAX_MESSAGE_KEY_LENGTH), MAX_MESSAGE_KEY_LENGTH);
            writeMessageArgs(buf, payload.messageArgs());

            // 写入所有列值。长文本通过 writeChunkedValue 拆分。
            buf.writeVarInt(cells.size());
            for (QueryCell cell : cells) {
                buf.writeUtf(trim(cell.columnName(), MAX_COLUMN_NAME_LENGTH), MAX_COLUMN_NAME_LENGTH);
                writeChunkedValue(buf, cell.value());
            }
        }
    };

    /** 规范化字段，确保 page >= 1，列表不可变，字符串不为 null。 */
    public GuiTableResponsePayload {
        table = table == null ? "" : table;
        page = Math.max(1, page);
        cells = List.copyOf(cells);
        messageKey = messageKey == null ? "" : messageKey;
        messageArgs = List.copyOf(messageArgs);
    }

    /** 创建成功响应。 */
    public static GuiTableResponsePayload success(String table, int page, int requestId, boolean hasNext, List<QueryCell> cells) {
        return new GuiTableResponsePayload(table, page, requestId, true, hasNext, cells, "", List.of());
    }

    /** 创建失败响应。 */
    public static GuiTableResponsePayload error(String table, int page, int requestId, String messageKey, String... messageArgs) {
        return new GuiTableResponsePayload(table, page, requestId, false, false, List.of(), messageKey, List.of(messageArgs));
    }

    /** @return 当前网络包类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 读取错误提示参数。 */
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

    /** 写入错误提示参数。 */
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

    /**
     * 读取分块字符串。
     *
     * @param buf 网络缓冲区。
     * @return 合并后的完整字符串。
     */
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

    /**
     * 写入分块字符串。
     *
     * @param buf 网络缓冲区。
     * @param value 原始字段值。
     */
    private static void writeChunkedValue(RegistryFriendlyByteBuf buf, String value) {
        List<String> chunks = splitValue(value);
        buf.writeVarInt(chunks.size());
        for (String chunk : chunks) {
            buf.writeUtf(chunk, MAX_VALUE_CHUNK_LENGTH);
        }
    }

    /**
     * 将长文本拆成多个安全长度的字符串。
     *
     * @param value 原始文本，允许为 null。
     * @return 分块列表，至少包含一个字符串。
     */
    private static List<String> splitValue(String value) {
        String normalized = value == null ? "null" : value;
        int maxLength = MAX_VALUE_CHUNK_LENGTH * MAX_VALUE_CHUNKS;
        // 如果超过协议允许的总长度，先截断并追加提示。
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
        }

        // 空字符串也要传一个空 chunk，否则解码端不知道这个字段存在。
        if (normalized.isEmpty()) {
            return List.of("");
        }

        // 每次截取 MAX_VALUE_CHUNK_LENGTH 个字符，直到整段文本拆完。
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < normalized.length(); start += MAX_VALUE_CHUNK_LENGTH) {
            chunks.add(normalized.substring(start, Math.min(normalized.length(), start + MAX_VALUE_CHUNK_LENGTH)));
        }
        return chunks;
    }

    /** 裁剪普通字符串，null 会变成空字符串。 */
    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * GUI 中的一个字段。
     *
     * @param columnName 数据库列名。
     * @param value 原始字段值。
     */
    public record QueryCell(String columnName, String value) {}
}
