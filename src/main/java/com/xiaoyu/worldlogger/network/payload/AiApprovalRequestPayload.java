package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端发往服务器的 AI 深度查询审批请求。
 *
 * @param approvalId 服务器之前返回给玩家的审批 ID。
 */
public record AiApprovalRequestPayload(String approvalId) implements CustomPacketPayload {
    /** 审批 ID 最大长度。 */
    private static final int MAX_ID_LENGTH = 32;

    /** 网络包类型 ID。 */
    public static final Type<AiApprovalRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "ai_approval_request")
    );

    /** 编码/解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, AiApprovalRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AiApprovalRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new AiApprovalRequestPayload(buf.readUtf(MAX_ID_LENGTH));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AiApprovalRequestPayload payload) {
            buf.writeUtf(trim(payload.approvalId()), MAX_ID_LENGTH);
        }
    };

    /** 规范化审批 ID。 */
    public AiApprovalRequestPayload {
        approvalId = trim(approvalId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 裁剪字符串。 */
    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= MAX_ID_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ID_LENGTH);
    }
}
