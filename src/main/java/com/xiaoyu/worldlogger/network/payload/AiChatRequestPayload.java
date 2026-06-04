package com.xiaoyu.worldlogger.network.payload;

import com.xiaoyu.worldlogger.WorldLogger;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * 客户端发往服务器的 AI 聊天请求。
 *
 * @param message 玩家输入给 AI 的文本。
 * @param language 玩家客户端当前选择的语言代码，例如 zh_cn。服务器会把它写入 AI 提示词。
 */
public record AiChatRequestPayload(String message, String language) implements CustomPacketPayload {
    /** 最大输入长度，避免恶意客户端发送超长 prompt。 */
    private static final int MAX_MESSAGE_LENGTH = 4000;

    /** 最大语言代码长度，正常 Minecraft 语言代码很短，这里只是防止异常客户端发超长字符串。 */
    private static final int MAX_LANGUAGE_LENGTH = 32;

    /** 网络包类型 ID。 */
    public static final Type<AiChatRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(WorldLogger.MODID, "ai_chat_request")
    );

    /** 编码/解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, AiChatRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public AiChatRequestPayload decode(RegistryFriendlyByteBuf buf) {
            // 解码顺序必须和 encode 完全一致：先 message，再 language。
            return new AiChatRequestPayload(
                    buf.readUtf(MAX_MESSAGE_LENGTH),
                    buf.readUtf(MAX_LANGUAGE_LENGTH)
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, AiChatRequestPayload payload) {
            // 写包前再次裁剪，确保即使调用方传入异常 payload 也不会超过网络包限制。
            buf.writeUtf(trim(payload.message(), MAX_MESSAGE_LENGTH), MAX_MESSAGE_LENGTH);
            buf.writeUtf(normalizeLanguage(payload.language()), MAX_LANGUAGE_LENGTH);
        }
    };

    /** 规范化输入文本。 */
    public AiChatRequestPayload {
        message = trim(message, MAX_MESSAGE_LENGTH);
        language = normalizeLanguage(language);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 裁剪字符串。 */
    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String normalizeLanguage(String value) {
        // 语言代码会进入 AI 提示词，所以只允许简单的字母、数字和下划线。
        String language = trim(value, MAX_LANGUAGE_LENGTH)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if (language.isBlank() || !language.matches("[a-z0-9_]+")) {
            return "en_us";
        }
        return language;
    }
}
