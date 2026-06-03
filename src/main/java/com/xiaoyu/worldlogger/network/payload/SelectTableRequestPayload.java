package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端发往服务器的 select 查询请求包。
 *
 * @param table 要查询的表名。
 * @param page 要查询的页码。
 */
public record SelectTableRequestPayload(String table, int page) implements CustomPacketPayload {
    /** 网络包类型 ID。namespace 使用 MODID，path 必须在本模组网络包中唯一。 */
    public static final Type<SelectTableRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "select_table_request")
    );

    /** 编码/解码器，负责把 Java 对象写进字节缓冲区，或从字节缓冲区读回对象。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectTableRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SelectTableRequestPayload decode(RegistryFriendlyByteBuf buf) {
            // 表名最多读取 64 个字符，防止恶意客户端发送超长字符串。
            return new SelectTableRequestPayload(buf.readUtf(64), buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SelectTableRequestPayload payload) {
            // 写入顺序必须和 decode 读取顺序完全一致。
            buf.writeUtf(payload.table(), 64);
            buf.writeVarInt(payload.page());
        }
    };

    /**
     * record 的紧凑构造器。
     * <p>
     * 注意：这里把页码最小值限制为 1，防止网络包里出现非法页码。
     */
    public SelectTableRequestPayload {
        page = Math.max(1, page);
    }

    /**
     * NeoForge 通过这个方法识别网络包类型。
     *
     * @return 本 payload 的类型对象。
     */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
