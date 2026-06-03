package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端发往服务器的附近搜索请求包。
 *
 * @param page 搜索结果页码。
 */
public record SearchRequestPayload(int page) implements CustomPacketPayload {
    /** 网络包类型 ID。 */
    public static final Type<SearchRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "search_request")
    );

    /** 搜索请求只需要传页码，所以编码器非常短。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, SearchRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SearchRequestPayload decode(RegistryFriendlyByteBuf buf) {
            // readVarInt 用于读取小整数，适合页码这种数值。
            return new SearchRequestPayload(buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, SearchRequestPayload payload) {
            // 写入页码，顺序要和 decode 一致。
            buf.writeVarInt(payload.page());
        }
    };

    /** 页码最小为 1，避免负数页或第 0 页。 */
    public SearchRequestPayload {
        page = Math.max(1, page);
    }

    /** @return 当前网络包的类型。 */
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
