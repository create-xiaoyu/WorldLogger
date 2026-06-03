package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器返回给客户端的 select 查询结果包。
 *
 * @param table 查询的表名。
 * @param page 当前页码。
 * @param success true 表示查询成功，false 表示查询失败。
 * @param hasNext true 表示还有下一页。
 * @param lines 当前页要在聊天栏显示的列数据。
 * @param messageKey 错误或提示文本的语言文件 key。
 * @param messageArgs messageKey 对应的参数。
 */
public record SelectTableResponsePayload(
        String table,
        int page,
        boolean success,
        boolean hasNext,
        List<QueryLine> lines,
        String messageKey,
        List<String> messageArgs
) implements CustomPacketPayload {
    /** 单个响应最多携带的列行数，防止网络包过大。 */
    private static final int MAX_LINES = 64;

    /** 列名最大长度。数据库列名一般很短，64 已经足够。 */
    private static final int MAX_COLUMN_NAME_LENGTH = 64;

    /** 单行值最大长度。聊天栏 select 会压缩长文本，所以这里不用太大。 */
    private static final int MAX_LINE_LENGTH = 1200;

    /** 语言 key 最大长度，例如 text.worldlogger.error.xxx。 */
    private static final int MAX_MESSAGE_KEY_LENGTH = 128;

    /** 错误提示最多携带的参数数量。 */
    private static final int MAX_MESSAGE_ARGS = 8;

    /** 网络包类型 ID。 */
    public static final Type<SelectTableResponsePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "select_table_response")
    );

    /** 响应包编码/解码器。写入和读取字段顺序必须一致。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectTableResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SelectTableResponsePayload decode(RegistryFriendlyByteBuf buf) {
            // 基础状态字段：表名、页码、是否成功、是否有下一页。
            String table = buf.readUtf(64);
            int page = buf.readVarInt();
            boolean success = buf.readBoolean();
            boolean hasNext = buf.readBoolean();
            String messageKey = buf.readUtf(MAX_MESSAGE_KEY_LENGTH);

            // 先读取错误提示参数数量，再逐个读取参数。
            int messageArgCount = buf.readVarInt();
            if (messageArgCount < 0 || messageArgCount > MAX_MESSAGE_ARGS) {
                throw new IllegalArgumentException("Invalid WorldLogger response message arg count: " + messageArgCount);
            }

            List<String> messageArgs = new ArrayList<>(messageArgCount);
            for (int i = 0; i < messageArgCount; i++) {
                messageArgs.add(buf.readUtf(MAX_LINE_LENGTH));
            }

            // 读取结果行数。必须限制范围，避免异常或恶意网络包申请超大 List。
            int lineCount = buf.readVarInt();
            if (lineCount < 0 || lineCount > MAX_LINES) {
                throw new IllegalArgumentException("Invalid WorldLogger response line count: " + lineCount);
            }

            // 每个 QueryLine 是一对“列名 + 值”。
            List<QueryLine> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                lines.add(new QueryLine(buf.readUtf(MAX_COLUMN_NAME_LENGTH), buf.readUtf(MAX_LINE_LENGTH)));
            }
            return new SelectTableResponsePayload(table, page, success, hasNext, lines, messageKey, messageArgs);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SelectTableResponsePayload payload) {
            // 写入前再次限制数量和长度，防止服务端代码误传过大的数据。
            List<String> messageArgs = payload.messageArgs().stream()
                    .limit(MAX_MESSAGE_ARGS)
                    .map(SelectTableResponsePayload::trimLine)
                    .toList();
            List<QueryLine> lines = payload.lines().stream()
                    .limit(MAX_LINES)
                    .toList();

            // 写入基础状态。
            buf.writeUtf(payload.table(), 64);
            buf.writeVarInt(payload.page());
            buf.writeBoolean(payload.success());
            buf.writeBoolean(payload.hasNext());
            buf.writeUtf(trimMessageKey(payload.messageKey()), MAX_MESSAGE_KEY_LENGTH);

            // 写入错误提示参数。
            buf.writeVarInt(messageArgs.size());
            for (String messageArg : messageArgs) {
                buf.writeUtf(messageArg, MAX_LINE_LENGTH);
            }

            // 写入查询结果行。
            buf.writeVarInt(lines.size());
            for (QueryLine line : lines) {
                buf.writeUtf(trimColumnName(line.columnName()), MAX_COLUMN_NAME_LENGTH);
                buf.writeUtf(trimLine(line.value()), MAX_LINE_LENGTH);
            }
        }
    };

    /**
     * 规范化响应内容。
     * <p>
     * 注意：List.copyOf 会创建不可变副本，避免外部继续修改已经创建好的网络包。
     */
    public SelectTableResponsePayload {
        page = Math.max(1, page);
        lines = List.copyOf(lines);
        messageKey = messageKey == null ? "" : messageKey;
        messageArgs = List.copyOf(messageArgs);
    }

    /**
     * 创建成功响应。
     *
     * @param table 表名。
     * @param page 页码。
     * @param hasNext 是否有下一页。
     * @param lines 查询结果行。
     * @return 成功响应包。
     */
    public static SelectTableResponsePayload success(String table, int page, boolean hasNext, List<QueryLine> lines) {
        return new SelectTableResponsePayload(table, page, true, hasNext, lines, "", List.of());
    }

    /**
     * 创建失败响应。
     *
     * @param table 表名。
     * @param page 页码。
     * @param messageKey 语言文件 key。
     * @param messageArgs 语言参数。
     * @return 失败响应包。
     */
    public static SelectTableResponsePayload error(String table, int page, String messageKey, String... messageArgs) {
        return new SelectTableResponsePayload(table, page, false, false, List.of(), messageKey, List.of(messageArgs));
    }

    /** @return 当前网络包类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 裁剪语言 key，避免超出网络字符串长度限制。 */
    private static String trimMessageKey(String messageKey) {
        if (messageKey.length() <= MAX_MESSAGE_KEY_LENGTH) {
            return messageKey;
        }
        return messageKey.substring(0, MAX_MESSAGE_KEY_LENGTH);
    }

    /** 裁剪列名。 */
    private static String trimColumnName(String columnName) {
        if (columnName.length() <= MAX_COLUMN_NAME_LENGTH) {
            return columnName;
        }
        return columnName.substring(0, MAX_COLUMN_NAME_LENGTH);
    }

    /** 裁剪单元格文本，超长时用省略号提示。 */
    private static String trimLine(String line) {
        if (line.length() <= MAX_LINE_LENGTH) {
            return line;
        }
        return line.substring(0, MAX_LINE_LENGTH - 3) + "...";
    }

    /**
     * 单个聊天栏查询行。
     *
     * @param columnName 数据库列名。
     * @param value 该列对应的显示值。
     */
    public record QueryLine(String columnName, String value) {}
}
