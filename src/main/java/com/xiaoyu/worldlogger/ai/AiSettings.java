package com.xiaoyu.worldlogger.ai;

/**
 * AI 请求所需配置的不可变快照。
 *
 * @param enabled 是否启用 AI。
 * @param apiBaseUrl OpenAI 兼容 API 地址。
 * @param apiKey API Key。
 * @param model 模型名。
 * @param maxAutoSearchDepth 自动工具搜索深度上限。
 * @param maxApprovedSearchDepth 玩家确认后的搜索深度上限。
 * @param maxToolIterations 单次对话最大工具调用循环次数。
 * @param maxOutputTokens AI 输出 token 上限。
 * @param timeoutSeconds HTTP 请求超时时间。
 * @param debugLogPayloads 是否把 OpenAI 请求体和响应体写入 DEBUG 日志。
 */
public record AiSettings(
        boolean enabled,
        String apiBaseUrl,
        String apiKey,
        String model,
        int maxAutoSearchDepth,
        int maxApprovedSearchDepth,
        int maxToolIterations,
        int maxOutputTokens,
        int timeoutSeconds,
        boolean debugLogPayloads
) {
    /**
     * 判断配置是否足够发起请求。
     *
     * @return true 表示 enabled 为 true，且 API Key、模型名、API 地址都不为空。
     */
    public boolean isUsable() {
        return enabled
                && apiBaseUrl != null && !(apiBaseUrl.isBlank())
                && apiKey != null && !(apiKey.isBlank())
                && model != null && !(model.isBlank());
    }
}
