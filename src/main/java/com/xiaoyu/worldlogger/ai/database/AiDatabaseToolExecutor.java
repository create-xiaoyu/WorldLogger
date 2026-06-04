package com.xiaoyu.worldlogger.ai.database;

import com.google.gson.JsonObject;
import com.xiaoyu.worldlogger.ai.AiSettings;
import com.xiaoyu.worldlogger.ai.AiToolCall;
import com.xiaoyu.worldlogger.ai.AiToolExecutor;

/**
 * 服务端 AI 数据库工具执行器。
 *
 * <p>这个类把 OpenAI 请求的工具调用转发给 AiDatabaseToolService。
 * 它持有玩家上下文和配置，因此可以按配置限制搜索深度。</p>
 */
public class AiDatabaseToolExecutor implements AiToolExecutor {
    /** 当前请求的配置快照。 */
    private final AiSettings settings;

    /** 执行命令的玩家上下文。 */
    private final AiToolContext context;

    public AiDatabaseToolExecutor(AiSettings settings, AiToolContext context) {
        this.settings = settings;
        this.context = context;
    }

    @Override
    public JsonObject execute(AiToolCall call, boolean approved) throws Exception {
        // approved=false 时只给自动深度；approved=true 时给玩家确认后的深度。
        // 如果 AI 请求的 depth 仍然大于这个值，AiDatabaseToolService 会继续 clamp，保证配置上限永远生效。
        int maxDepth = approved ? settings.maxApprovedSearchDepth() : settings.maxAutoSearchDepth();
        return AiDatabaseToolService.execute(context, call.name(), call.arguments(), maxDepth);
    }
}
