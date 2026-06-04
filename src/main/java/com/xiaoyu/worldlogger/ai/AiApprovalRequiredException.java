package com.xiaoyu.worldlogger.ai;

import java.util.List;

/**
 * 表示 AI 请求的数据库搜索深度超过自动允许范围，需要玩家确认。
 */
public class AiApprovalRequiredException extends Exception {
    /** OpenAI 已经生成的、包含工具调用的 response id。 */
    private final String previousResponseId;

    /** 等待玩家确认后执行的工具调用列表。 */
    private final List<AiToolCall> toolCalls;

    /** AI 请求的最大深度。 */
    private final int requestedDepth;

    /** 配置允许的自动深度。 */
    private final int autoLimit;

    /** 玩家确认后允许执行的最大深度。 */
    private final int approvedLimit;

    public AiApprovalRequiredException(String previousResponseId, List<AiToolCall> toolCalls, int requestedDepth, int autoLimit, int approvedLimit) {
        super("AI requested database search depth " + requestedDepth + ", which exceeds automatic limit " + autoLimit);
        this.previousResponseId = previousResponseId;
        this.toolCalls = List.copyOf(toolCalls);
        this.requestedDepth = requestedDepth;
        this.autoLimit = autoLimit;
        this.approvedLimit = approvedLimit;
    }

    public String previousResponseId() {
        return previousResponseId;
    }

    public List<AiToolCall> toolCalls() {
        return toolCalls;
    }

    public int requestedDepth() {
        return requestedDepth;
    }

    public int autoLimit() {
        return autoLimit;
    }

    public int approvedLimit() {
        return approvedLimit;
    }
}
