package com.xiaoyu.worldlogger.ai;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.ai.database.AiDatabaseToolExecutor;
import com.xiaoyu.worldlogger.ai.database.AiToolContext;
import com.xiaoyu.worldlogger.network.payload.AiChatResponsePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 服务端 AI 请求调度器。
 *
 * <p>它负责读取服务器 AI 配置、创建数据库工具上下文、调用 OpenAI、
 * 处理超深度审批，并把最终结果发回玩家客户端。</p>
 */
public final class ServerAiService {
    /** 日志对象。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 工具类不需要实例化。 */
    private ServerAiService() {}

    /** 处理玩家发来的 AI 聊天请求。 */
    public static void chat(ServerPlayer player, String message, String language) {
        AiSettings settings = AiConfig.serverSettings();
        if (!(settings.isUsable())) {
            PacketDistributor.sendToPlayer(player, AiChatResponsePayload.error("text.worldlogger.ai.error.config"));
            return;
        }

        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        AiToolContext toolContext = context(player);
        String previousResponseId = AiConversationStore.get(playerId);

        if (!AiExecutorService.execute(LOGGER, "server ai chat", () -> {
            try {
                AiRunResult result = OpenAiResponsesClient.chat(
                        settings,
                        message,
                        previousResponseId,
                        true,
                        new AiDatabaseToolExecutor(settings, toolContext),
                        language
                );
                AiConversationStore.set(playerId, result.responseId());
                send(server, playerId, AiChatResponsePayload.success(result.text()));
            } catch (AiApprovalRequiredException e) {
                AiPendingApproval pending = AiApprovalStore.create(
                        playerId,
                        settings,
                        toolContext,
                        language,
                        e.previousResponseId(),
                        e.toolCalls(),
                        e.requestedDepth(),
                        e.autoLimit()
                );
                send(server, playerId, AiChatResponsePayload.approvalRequired(pending.id(), e.requestedDepth(), e.autoLimit(), e.approvedLimit()));
            } catch (Exception e) {
                LOGGER.error("WorldLogger server AI request failed.", e);
                send(server, playerId, AiChatResponsePayload.error("text.worldlogger.ai.error.request", rootMessage(e)));
            }
        })) {
            PacketDistributor.sendToPlayer(player, AiChatResponsePayload.error("text.worldlogger.ai.error.executor"));
        }
    }

    /** 处理玩家确认超深度搜索。 */
    public static void approve(ServerPlayer player, String approvalId) {
        AiPendingApproval pending;
        try {
            pending = AiApprovalStore.take(approvalId, player.getUUID());
        } catch (IllegalArgumentException e) {
            PacketDistributor.sendToPlayer(player, AiChatResponsePayload.error("text.worldlogger.ai.error.approval", e.getMessage()));
            return;
        }

        MinecraftServer server = player.level().getServer();
        UUID playerId = player.getUUID();
        if (!AiExecutorService.execute(LOGGER, "server ai approved tool calls", () -> {
            try {
                AiRunResult result = OpenAiResponsesClient.continueAfterApproval(
                        pending.settings(),
                        pending.previousResponseId(),
                        pending.toolCalls(),
                        new AiDatabaseToolExecutor(pending.settings(), pending.toolContext()),
                        pending.language()
                );
                AiConversationStore.set(playerId, result.responseId());
                send(server, playerId, AiChatResponsePayload.success(result.text()));
            } catch (AiApprovalRequiredException e) {
                AiPendingApproval nextPending = AiApprovalStore.create(
                        playerId,
                        pending.settings(),
                        pending.toolContext(),
                        pending.language(),
                        e.previousResponseId(),
                        e.toolCalls(),
                        e.requestedDepth(),
                        e.autoLimit()
                );
                send(server, playerId, AiChatResponsePayload.approvalRequired(nextPending.id(), e.requestedDepth(), e.autoLimit(), e.approvedLimit()));
            } catch (Exception e) {
                LOGGER.error("WorldLogger approved AI request failed.", e);
                send(server, playerId, AiChatResponsePayload.error("text.worldlogger.ai.error.request", rootMessage(e)));
            }
        })) {
            PacketDistributor.sendToPlayer(player, AiChatResponsePayload.error("text.worldlogger.ai.error.executor"));
        }
    }

    /** 重置玩家 AI 对话上下文。 */
    public static void reset(ServerPlayer player) {
        AiConversationStore.clear(player.getUUID());
        AiApprovalStore.clear(player.getUUID());
        PacketDistributor.sendToPlayer(player, AiChatResponsePayload.info("text.worldlogger.ai.reset.done"));
    }

    /** 创建玩家上下文快照。 */
    private static AiToolContext context(ServerPlayer player) {
        return new AiToolContext(
                player.getUUID(),
                player.getName().getString(),
                player.blockPosition().getX(),
                player.blockPosition().getY(),
                player.blockPosition().getZ(),
                player.level().dimension().identifier().toString()
        );
    }

    /** 回到服务器线程后再给玩家发包。 */
    private static void send(MinecraftServer server, UUID playerId, AiChatResponsePayload payload) {
        server.executeIfPossible(() -> {
            ServerPlayer target = server.getPlayerList().getPlayer(playerId);
            if (target != null && !(target.hasDisconnected())) {
                PacketDistributor.sendToPlayer(target, payload);
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
