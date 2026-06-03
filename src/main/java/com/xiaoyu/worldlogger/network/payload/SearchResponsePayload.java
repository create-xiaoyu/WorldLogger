package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器返回给客户端的附近搜索结果包。
 *
 * @param page 当前页码。
 * @param success true 表示搜索成功。
 * @param hasNext true 表示还有下一页。
 * @param truncated true 表示服务端为了防止刷屏截断了总结果。
 * @param lines 当前页搜索结果。
 * @param messageKey 错误提示语言 key。
 * @param messageArgs 错误提示参数。
 */
public record SearchResponsePayload(
        int page,
        boolean success,
        boolean hasNext,
        boolean truncated,
        List<SearchLine> lines,
        String messageKey,
        List<String> messageArgs
) implements CustomPacketPayload {
    /** 最多携带的搜索结果行数。实际一页默认 5 条，这里是网络层的硬限制。 */
    private static final int MAX_LINES = 160;

    /** 每条本地化文本最多携带的参数数量。 */
    private static final int MAX_ARGS = 8;

    /** 语言 key 最大长度。 */
    private static final int MAX_KEY_LENGTH = 128;

    /** 单个语言参数最大长度。 */
    private static final int MAX_ARG_LENGTH = 1200;

    /** 网络包类型 ID。 */
    public static final Type<SearchResponsePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "search_response")
    );

    /** 搜索响应编码/解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, SearchResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SearchResponsePayload decode(RegistryFriendlyByteBuf buf) {
            // 读取响应状态字段。
            int page = buf.readVarInt();
            boolean success = buf.readBoolean();
            boolean hasNext = buf.readBoolean();
            boolean truncated = buf.readBoolean();
            String messageKey = buf.readUtf(MAX_KEY_LENGTH);
            List<String> messageArgs = readArgs(buf);

            // 读取搜索结果数量，并检查是否超过限制。
            int lineCount = buf.readVarInt();
            if (lineCount < 0 || lineCount > MAX_LINES) {
                throw new IllegalArgumentException("Invalid WorldLogger search line count: " + lineCount);
            }

            // 每条 SearchLine 保存一个语言 key 和若干参数，客户端用 Component.translatable 显示。
            List<SearchLine> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                String translationKey = buf.readUtf(MAX_KEY_LENGTH);
                lines.add(new SearchLine(translationKey, readArgs(buf)));
            }

            return new SearchResponsePayload(page, success, hasNext, truncated, lines, messageKey, messageArgs);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SearchResponsePayload payload) {
            // 写出前限制行数，防止响应包过大。
            List<SearchLine> lines = payload.lines().stream()
                    .limit(MAX_LINES)
                    .toList();

            // 写入基础状态和错误提示。
            buf.writeVarInt(payload.page());
            buf.writeBoolean(payload.success());
            buf.writeBoolean(payload.hasNext());
            buf.writeBoolean(payload.truncated());
            buf.writeUtf(trimKey(payload.messageKey()), MAX_KEY_LENGTH);
            writeArgs(buf, payload.messageArgs());

            // 写入搜索结果。
            buf.writeVarInt(lines.size());
            for (SearchLine line : lines) {
                buf.writeUtf(trimKey(line.translationKey()), MAX_KEY_LENGTH);
                writeArgs(buf, line.args());
            }
        }
    };

    /** 规范化页码和列表字段，保证对象创建后列表不可变。 */
    public SearchResponsePayload {
        page = Math.max(1, page);
        lines = List.copyOf(lines);
        messageKey = messageKey == null ? "" : messageKey;
        messageArgs = List.copyOf(messageArgs);
    }

    /** 创建成功响应。 */
    public static SearchResponsePayload success(int page, boolean hasNext, boolean truncated, List<SearchLine> lines) {
        return new SearchResponsePayload(page, true, hasNext, truncated, lines, "", List.of());
    }

    /** 创建默认第一页的失败响应。 */
    public static SearchResponsePayload error(String messageKey, String... messageArgs) {
        return error(1, messageKey, messageArgs);
    }

    /** 创建指定页码的失败响应。 */
    public static SearchResponsePayload error(int page, String messageKey, String... messageArgs) {
        return new SearchResponsePayload(page, false, false, false, List.of(), messageKey, List.of(messageArgs));
    }

    /** @return 当前网络包类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 读取一组字符串参数。
     *
     * @param buf 网络缓冲区。
     * @return 参数列表。
     */
    private static List<String> readArgs(RegistryFriendlyByteBuf buf) {
        int argCount = buf.readVarInt();
        if (argCount < 0 || argCount > MAX_ARGS) {
            throw new IllegalArgumentException("Invalid WorldLogger search arg count: " + argCount);
        }

        List<String> args = new ArrayList<>(argCount);
        for (int i = 0; i < argCount; i++) {
            args.add(buf.readUtf(MAX_ARG_LENGTH));
        }
        return args;
    }

    /**
     * 写入一组字符串参数。
     *
     * @param buf 网络缓冲区。
     * @param args 原始参数列表。
     */
    private static void writeArgs(RegistryFriendlyByteBuf buf, List<String> args) {
        List<String> trimmedArgs = args.stream()
                .limit(MAX_ARGS)
                .map(SearchResponsePayload::trimArg)
                .toList();

        buf.writeVarInt(trimmedArgs.size());
        for (String arg : trimmedArgs) {
            buf.writeUtf(arg, MAX_ARG_LENGTH);
        }
    }

    /** 裁剪语言 key。 */
    private static String trimKey(String key) {
        if (key.length() <= MAX_KEY_LENGTH) {
            return key;
        }
        return key.substring(0, MAX_KEY_LENGTH);
    }

    /** 裁剪语言参数。 */
    private static String trimArg(String arg) {
        if (arg.length() <= MAX_ARG_LENGTH) {
            return arg;
        }
        return arg.substring(0, MAX_ARG_LENGTH - 3) + "...";
    }

    /**
     * 单条附近搜索结果。
     *
     * @param translationKey 语言文件 key。
     * @param args 填入语言文本中的参数。
     */
    public record SearchLine(String translationKey, List<String> args) {
        /** 创建不可变参数列表，避免网络包创建后被外部修改。 */
        public SearchLine {
            args = List.copyOf(args);
        }
    }
}
