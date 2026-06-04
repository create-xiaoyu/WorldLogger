package com.xiaoyu.worldlogger.ai;

import com.xiaoyu.worldlogger.ai.database.AiToolContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 等待玩家确认的 AI 工具调用。
 *
 * @param id 审批 ID。
 * @param playerId 只能由这个玩家批准。
 * @param settings 发起请求时的配置快照。
 * @param toolContext 发起请求时的玩家上下文。
 * @param language 发起请求的客户端语言代码，用于审批后继续要求 AI 使用同一种语言回答。
 * @param previousResponseId OpenAI 等待工具结果的 response id。
 * @param toolCalls 等待执行的工具调用。
 * @param requestedDepth AI 请求的深度。
 * @param autoLimit 自动允许深度。
 * @param createdAt 创建时间，用于过期清理。
 */
public record AiPendingApproval(
        String id,
        UUID playerId,
        AiSettings settings,
        AiToolContext toolContext,
        String language,
        String previousResponseId,
        List<AiToolCall> toolCalls,
        int requestedDepth,
        int autoLimit,
        Instant createdAt
) {
    public AiPendingApproval {
        language = language == null || language.isBlank() ? "en_us" : language;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
