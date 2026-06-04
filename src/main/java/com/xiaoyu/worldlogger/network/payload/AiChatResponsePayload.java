package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import com.xiaoyu.worldlogger.ai.AiComponentFormatter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器返回给客户端的 AI 响应。
 *
 * @param success true 表示 AI 正常回复。
 * @param message AI 回复组件；普通本地化提示会把这里留空。
 * @param messageKey 本地化消息 key；为空时直接显示 message 组件。
 * @param messageArgs 本地化参数。
 */
public record AiChatResponsePayload(
        boolean success,
        Component message,
        String messageKey,
        List<String> messageArgs
) implements CustomPacketPayload {
    /** AI 回复最大长度。 */
    private static final int MAX_MESSAGE_LENGTH = 30000;

    /** 本地化 key 最大长度。 */
    private static final int MAX_KEY_LENGTH = 128;

    /** 本地化参数最大数量。 */
    private static final int MAX_ARGS = 8;

    /** 单个参数最大长度。 */
    private static final int MAX_ARG_LENGTH = 1200;

    /** 网络包类型 ID。 */
    public static final Type<AiChatResponsePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "ai_chat_response")
    );

    /** 编码/解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, AiChatResponsePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AiChatResponsePayload decode(RegistryFriendlyByteBuf buf) {
            // 解码顺序必须和 encode 保持一致，否则客户端会把后面的字段读乱。
            boolean success = buf.readBoolean();
            // ComponentSerialization 会保留文本、颜色、样式、hover/click 等组件结构。
            Component message = ComponentSerialization.STREAM_CODEC.decode(buf);
            String messageKey = buf.readUtf(MAX_KEY_LENGTH);
            int argCount = buf.readVarInt();
            // 参数数量来自网络包，必须做范围检查，避免异常客户端构造过大的列表。
            if (argCount < 0 || argCount > MAX_ARGS) {
                throw new IllegalArgumentException("Invalid WorldLogger AI arg count: " + argCount);
            }

            List<String> args = new ArrayList<>(argCount);
            for (int i = 0; i < argCount; i++) {
                args.add(buf.readUtf(MAX_ARG_LENGTH));
            }
            return new AiChatResponsePayload(success, message, messageKey, args);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AiChatResponsePayload payload) {
            // 本地化参数可能包含玩家消息、审批 ID 或错误详情，发送前统一裁剪。
            List<String> args = payload.messageArgs().stream()
                    .limit(MAX_ARGS)
                    .map(arg -> trim(arg, MAX_ARG_LENGTH))
                    .toList();

            buf.writeBoolean(payload.success());
            ComponentSerialization.STREAM_CODEC.encode(buf, payload.message());
            buf.writeUtf(trim(payload.messageKey(), MAX_KEY_LENGTH), MAX_KEY_LENGTH);
            buf.writeVarInt(args.size());
            for (String arg : args) {
                buf.writeUtf(arg, MAX_ARG_LENGTH);
            }
        }
    };

    /** 规范化字段。 */
    public AiChatResponsePayload {
        message = sanitizeMessage(message);
        messageKey = messageKey == null ? "" : trim(messageKey, MAX_KEY_LENGTH);
        messageArgs = messageArgs == null ? List.of() : List.copyOf(messageArgs);
    }

    /** 创建 AI 成功回复。 */
    public static AiChatResponsePayload success(String message) {
        return new AiChatResponsePayload(true, AiComponentFormatter.aiMessage(message), "", List.of());
    }

    /** 创建普通本地化提示。 */
    public static AiChatResponsePayload info(String messageKey, String... args) {
        return new AiChatResponsePayload(true, Component.empty(), messageKey, List.of(args));
    }

    /** 创建错误响应。 */
    public static AiChatResponsePayload error(String messageKey, String... args) {
        return new AiChatResponsePayload(false, Component.empty(), messageKey, List.of(args));
    }

    /** 创建需要玩家确认的响应。 */
    public static AiChatResponsePayload approvalRequired(String approvalId, int requestedDepth, int autoLimit, int approvedLimit) {
        // messageArgs 的顺序要和语言文件中的 %1$s、%2$s... 对应。
        // 第 5 个“点击按钮”由客户端收到包后临时生成，不直接放进网络包。
        return new AiChatResponsePayload(
                false,
                Component.empty(),
                "text.worldlogger.ai.approval.required",
                List.of(approvalId, String.valueOf(requestedDepth), String.valueOf(autoLimit), String.valueOf(approvedLimit))
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 限制组件大小。
     *
     * @param component 原始组件。
     * @return 字符数安全的组件；过长时会退化成截断后的纯文本组件。
     */
    private static Component sanitizeMessage(Component component) {
        if (component == null) {
            return Component.empty();
        }

        String plainText = component.getString();
        if (plainText.length() <= MAX_MESSAGE_LENGTH) {
            return component;
        }
        return Component.literal(trim(plainText, MAX_MESSAGE_LENGTH));
    }

    /** 裁剪字符串。 */
    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }
}
