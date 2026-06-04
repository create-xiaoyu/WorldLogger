package com.xiaoyu.worldlogger.ai;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * WorldLogger AI 功能的配置类。
 *
 * <p>这里故意分成 SERVER 和 CLIENT 两套配置：
 * 专用服务器使用 SERVER 配置，可以让 AI 调用数据库工具；
 * 单人游戏的客户端直连聊天使用 CLIENT 配置，不调用数据库工具。</p>
 */
public final class AiConfig {
    /** 服务器 AI 配置构建器。 */
    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    /** 客户端 AI 配置构建器。 */
    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    /** 是否启用服务器 AI。关闭后多人服务器上的 /worldlogger ai 会直接返回错误。 */
    public static final ModConfigSpec.BooleanValue SERVER_ENABLED = SERVER_BUILDER
            .comment("Enable server-side WorldLogger AI. Server AI can use database tools.")
            .define("enabled", false);

    /** 服务器侧 OpenAI API 地址，默认指向官方 v1 API。 */
    public static final ModConfigSpec.ConfigValue<String> SERVER_API_BASE_URL = SERVER_BUILDER
            .comment("OpenAI compatible API base URL used by the server, for example: https://api.openai.com/v1")
            .define("api_base_url", "https://api.openai.com/v1");

    /** 服务器侧 API Key。不要把真实 key 提交到公开仓库。 */
    public static final ModConfigSpec.ConfigValue<String> SERVER_API_KEY = SERVER_BUILDER
            .comment("OpenAI API key used by the server. Keep this secret.")
            .define("api_key", "");

    /** 服务器侧模型名。用户可以改成任意兼容 Responses API 的 GPT 系列模型。 */
    public static final ModConfigSpec.ConfigValue<String> SERVER_MODEL = SERVER_BUILDER
            .comment("OpenAI model used by the server AI.")
            .define("model", "gpt-5.5");

    /** 不需要二次确认的最大自动搜索深度。 */
    public static final ModConfigSpec.IntValue SERVER_MAX_AUTO_SEARCH_DEPTH = SERVER_BUILDER
            .comment("Maximum database search depth the AI may use without player approval.")
            .defineInRange("max_auto_search_depth", 3, 1, 128);

    /** 玩家确认后允许的最大搜索深度，防止一次请求把数据库大量内容发给模型。 */
    public static final ModConfigSpec.IntValue SERVER_MAX_APPROVED_SEARCH_DEPTH = SERVER_BUILDER
            .comment("Maximum database search depth after player approval.")
            .defineInRange("max_approved_search_depth", 20, 1, 512);

    /** 单轮 AI 对话最多允许多少次工具调用循环。 */
    public static final ModConfigSpec.IntValue SERVER_MAX_TOOL_ITERATIONS = SERVER_BUILDER
            .comment("Maximum tool-call iterations in one AI request.")
            .defineInRange("max_tool_iterations", 6, 1, 32);

    /** AI 最多输出多少 token。 */
    public static final ModConfigSpec.IntValue SERVER_MAX_OUTPUT_TOKENS = SERVER_BUILDER
            .comment("Maximum output tokens for one AI response.")
            .defineInRange("max_output_tokens", 1200, 128, 16000);

    /** HTTP 请求超时时间。 */
    public static final ModConfigSpec.IntValue SERVER_REQUEST_TIMEOUT_SECONDS = SERVER_BUILDER
            .comment("OpenAI HTTP request timeout in seconds.")
            .defineInRange("request_timeout_seconds", 60, 5, 300);

    /** 是否把 OpenAI 请求体和响应体写到 DEBUG 日志。内容可能包含玩家输入和数据库结果，默认关闭。 */
    public static final ModConfigSpec.BooleanValue SERVER_DEBUG_LOG_PAYLOADS = SERVER_BUILDER
            .comment("Write OpenAI request and response bodies to DEBUG logs. This may include player messages and database records; keep it disabled unless debugging.")
            .define("debug_log_payloads", false);

    /** 是否启用单人游戏客户端 AI。 */
    public static final ModConfigSpec.BooleanValue CLIENT_ENABLED = CLIENT_BUILDER
            .comment("Enable client-side WorldLogger AI for singleplayer chat. Client AI cannot use database tools.")
            .define("enabled", false);

    /** 客户端侧 API 地址。 */
    public static final ModConfigSpec.ConfigValue<String> CLIENT_API_BASE_URL = CLIENT_BUILDER
            .comment("OpenAI compatible API base URL used by the client, for example: https://api.openai.com/v1")
            .define("api_base_url", "https://api.openai.com/v1");

    /** 客户端侧 API Key，只用于单人游戏日常聊天。 */
    public static final ModConfigSpec.ConfigValue<String> CLIENT_API_KEY = CLIENT_BUILDER
            .comment("OpenAI API key used by the client in singleplayer. Keep this secret.")
            .define("api_key", "");

    /** 客户端侧模型名。 */
    public static final ModConfigSpec.ConfigValue<String> CLIENT_MODEL = CLIENT_BUILDER
            .comment("OpenAI model used by the client AI.")
            .define("model", "gpt-5.5");

    /** 客户端 AI 输出 token 上限。 */
    public static final ModConfigSpec.IntValue CLIENT_MAX_OUTPUT_TOKENS = CLIENT_BUILDER
            .comment("Maximum output tokens for one client-side AI response.")
            .defineInRange("max_output_tokens", 1000, 128, 16000);

    /** 客户端 HTTP 超时时间。 */
    public static final ModConfigSpec.IntValue CLIENT_REQUEST_TIMEOUT_SECONDS = CLIENT_BUILDER
            .comment("OpenAI HTTP request timeout in seconds.")
            .defineInRange("request_timeout_seconds", 60, 5, 300);

    /** 是否把客户端 OpenAI 请求体和响应体写到 DEBUG 日志。内容可能包含玩家输入，默认关闭。 */
    public static final ModConfigSpec.BooleanValue CLIENT_DEBUG_LOG_PAYLOADS = CLIENT_BUILDER
            .comment("Write client OpenAI request and response bodies to DEBUG logs. This may include player messages; keep it disabled unless debugging.")
            .define("debug_log_payloads", false);

    /** NeoForge 服务器配置规格。 */
    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    /** NeoForge 客户端配置规格。 */
    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    /** 工具类不需要实例化。 */
    private AiConfig() {}

    /**
     * 读取服务器 AI 设置。
     *
     * @return 当前服务器配置快照。
     */
    public static AiSettings serverSettings() {
        return new AiSettings(
                SERVER_ENABLED.get(),
                SERVER_API_BASE_URL.get(),
                SERVER_API_KEY.get(),
                SERVER_MODEL.get(),
                SERVER_MAX_AUTO_SEARCH_DEPTH.get(),
                SERVER_MAX_APPROVED_SEARCH_DEPTH.get(),
                SERVER_MAX_TOOL_ITERATIONS.get(),
                SERVER_MAX_OUTPUT_TOKENS.get(),
                SERVER_REQUEST_TIMEOUT_SECONDS.get(),
                SERVER_DEBUG_LOG_PAYLOADS.get()
        );
    }

    /**
     * 读取客户端 AI 设置。
     *
     * @return 当前客户端配置快照。客户端没有数据库工具，所以深度相关值固定为 1。
     */
    public static AiSettings clientSettings() {
        return new AiSettings(
                CLIENT_ENABLED.get(),
                CLIENT_API_BASE_URL.get(),
                CLIENT_API_KEY.get(),
                CLIENT_MODEL.get(),
                1,
                1,
                1,
                CLIENT_MAX_OUTPUT_TOKENS.get(),
                CLIENT_REQUEST_TIMEOUT_SECONDS.get(),
                CLIENT_DEBUG_LOG_PAYLOADS.get()
        );
    }
}
