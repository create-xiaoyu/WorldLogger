package com.xiaoyu.worldlogger.client;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.ai.AiConfig;
import com.xiaoyu.worldlogger.ai.AiComponentFormatter;
import com.xiaoyu.worldlogger.ai.AiConversationStore;
import com.xiaoyu.worldlogger.ai.AiExecutorService;
import com.xiaoyu.worldlogger.ai.AiRunResult;
import com.xiaoyu.worldlogger.ai.AiSettings;
import com.xiaoyu.worldlogger.ai.OpenAiResponsesClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 单人游戏客户端 AI 聊天服务。
 *
 * <p>客户端没有数据库连接，也不应该读取服务端数据库。
 * 因此这个服务只用于单人游戏日常聊天，不启用 AI 工具。</p>
 */
public final class WorldLoggerClientAiService {
    /** 日志对象。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 工具类不需要实例化。 */
    private WorldLoggerClientAiService() {}

    /**
     * 发送客户端 AI 聊天请求。
     *
     * @param message 玩家输入。
     */
    public static void chat(String message, String language) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        AiSettings settings = AiConfig.clientSettings();
        if (!(settings.isUsable())) {
            send(Component.translatable("text.worldlogger.ai.error.config").withStyle(ChatFormatting.RED));
            return;
        }

        UUID playerId = minecraft.player.getUUID();
        String previousResponseId = AiConversationStore.get(playerId);
        send(Component.translatable("text.worldlogger.ai.request.sent", message).withStyle(ChatFormatting.GRAY));

        AiExecutorService.execute(LOGGER, "client ai chat", () -> {
            try {
                AiRunResult result = OpenAiResponsesClient.chat(settings, message, previousResponseId, false, null, language);
                AiConversationStore.set(playerId, result.responseId());
                send(AiComponentFormatter.aiMessage(result.text()));
            } catch (Exception e) {
                LOGGER.error("WorldLogger client AI request failed.", e);
                send(Component.translatable("text.worldlogger.ai.error.request", rootMessage(e)).withStyle(ChatFormatting.RED));
            }
        });
    }

    /** 重置客户端 AI 对话上下文。 */
    public static void reset() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            AiConversationStore.clear(minecraft.player.getUUID());
        }
        send(Component.translatable("text.worldlogger.ai.reset.done").withStyle(ChatFormatting.GRAY));
    }

    /** 在客户端线程发送聊天栏消息。 */
    private static void send(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(component);
            }
        });
    }

    /** 读取异常链最底层信息。 */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
