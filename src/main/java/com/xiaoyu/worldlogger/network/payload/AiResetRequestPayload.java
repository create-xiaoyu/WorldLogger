package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端发往服务器的 AI 对话重置请求。
 */
public record AiResetRequestPayload() implements CustomPacketPayload {
    /** 网络包类型 ID。 */
    public static final Type<AiResetRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "ai_reset_request")
    );

    /** 空 payload 的编码/解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, AiResetRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AiResetRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new AiResetRequestPayload();
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AiResetRequestPayload payload) {
            // 没有字段需要写入。
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
