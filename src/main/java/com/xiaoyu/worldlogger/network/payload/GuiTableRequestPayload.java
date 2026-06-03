package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * GUI 表格查询请求包。
 *
 * @param table GUI 当前选择的表名。
 * @param page 当前页码。
 * @param filter 搜索框过滤内容。
 * @param requestId 请求编号，用来忽略过期响应。
 */
public record GuiTableRequestPayload(String table, int page, String filter, int requestId) implements CustomPacketPayload {
    /** 表名最大长度，和 ListData 中的表名长度相比留有余量。 */
    private static final int MAX_TABLE_LENGTH = 64;

    /** 搜索框最大长度，避免客户端发送过长字符串给服务器。 */
    private static final int MAX_FILTER_LENGTH = 256;

    /** 网络包类型 ID。 */
    public static final Type<GuiTableRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "gui_table_request")
    );

    /** GUI 请求包编码/解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, GuiTableRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public GuiTableRequestPayload decode(RegistryFriendlyByteBuf buf) {
            // 读取顺序：表名、页码、过滤文本、请求编号。
            return new GuiTableRequestPayload(
                    buf.readUtf(MAX_TABLE_LENGTH),
                    buf.readVarInt(),
                    buf.readUtf(MAX_FILTER_LENGTH),
                    buf.readVarInt()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, GuiTableRequestPayload payload) {
            // 字符串写入前先裁剪，避免超过 Minecraft 网络字符串长度限制。
            buf.writeUtf(trim(payload.table(), MAX_TABLE_LENGTH), MAX_TABLE_LENGTH);
            buf.writeVarInt(payload.page());
            buf.writeUtf(trim(payload.filter(), MAX_FILTER_LENGTH), MAX_FILTER_LENGTH);
            buf.writeVarInt(payload.requestId());
        }
    };

    /**
     * 规范化请求数据。
     * <p>
     * 注意：record 构造器里改写参数，会影响最终保存到 record 字段中的值。
     */
    public GuiTableRequestPayload {
        table = table == null ? "" : table;
        page = Math.max(1, page);
        filter = filter == null ? "" : trim(filter, MAX_FILTER_LENGTH);
    }

    /** @return 当前网络包类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 裁剪字符串。
     *
     * @param value 原始字符串，允许为 null。
     * @param maxLength 最大字符数。
     * @return 不为 null 且长度不超过 maxLength 的字符串。
     */
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
