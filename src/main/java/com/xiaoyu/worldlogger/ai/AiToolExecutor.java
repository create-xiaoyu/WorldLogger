package com.xiaoyu.worldlogger.ai;

import com.google.gson.JsonObject;

/**
 * AI 工具执行接口。
 *
 * <p>OpenAiResponsesClient 不直接知道数据库怎么查。
 * 它只负责发现模型请求了哪个工具，然后通过这个接口把工具交给服务端实现。</p>
 */
@FunctionalInterface
public interface AiToolExecutor {
    /**
     * 执行一个 AI 工具调用。
     *
     * @param call 工具调用信息。
     * @param approved true 表示玩家已经批准本次超深度查询。
     * @return 工具结果 JSON，会作为字符串返回给模型。
     * @throws Exception 工具执行失败时抛出，外层会把错误返回给玩家。
     */
    JsonObject execute(AiToolCall call, boolean approved) throws Exception;
}
