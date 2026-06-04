package com.xiaoyu.worldlogger.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.ai.database.AiDatabaseToolService;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenAI Responses API 的轻量客户端。
 *
 * <p>这里不引入 OpenAI SDK，而是直接使用 Java 标准库 HttpClient。
 * 好处是依赖少；代价是需要自己构造 JSON 请求和解析工具调用结果。</p>
 */
public final class OpenAiResponsesClient {
    /** 日志对象。AI 请求体和响应体只会在配置允许且 DEBUG 日志开启时输出。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** JSON 工具对象。 */
    private static final Gson GSON = new Gson();

    /** 专门用于调试日志的 JSON 工具对象，pretty printing 能让请求体和响应体更容易阅读。 */
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 复用的 HTTP 客户端。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    /** 工具类不需要实例化。 */
    private OpenAiResponsesClient() {}

    /**
     * 发起一轮普通 AI 对话。
     *
     * @param settings AI 配置。
     * @param userMessage 玩家输入。
     * @param previousResponseId 上一轮 response id；没有历史时为 null。
     * @param toolsEnabled 是否允许模型调用 WorldLogger 数据库工具。
     * @param toolExecutor 工具执行器；toolsEnabled 为 false 时可以为 null。
     * @return AI 最终回复。
     */
    public static AiRunResult chat(AiSettings settings, String userMessage, String previousResponseId, boolean toolsEnabled, AiToolExecutor toolExecutor, String language)
            throws IOException, InterruptedException, AiApprovalRequiredException {
        JsonObject request = baseRequest(settings, toolsEnabled);
        request.addProperty("instructions", instructions(toolsEnabled, settings.model(), language));
        request.addProperty("input", userMessage);
        if (previousResponseId != null && !(previousResponseId.isBlank())) {
            request.addProperty("previous_response_id", previousResponseId);
        }

        return runLoop(settings, request, toolsEnabled, toolExecutor, false, language);
    }

    /**
     * 玩家批准超深度搜索后，从之前等待确认的工具调用继续对话。
     *
     * @param settings AI 配置。
     * @param previousResponseId OpenAI 工具调用响应的 response id。
     * @param toolCalls 等待执行的工具调用。
     * @param toolExecutor 工具执行器。
     * @return AI 最终回复。
     */
    public static AiRunResult continueAfterApproval(AiSettings settings, String previousResponseId, List<AiToolCall> toolCalls, AiToolExecutor toolExecutor, String language)
            throws IOException, InterruptedException, AiApprovalRequiredException {
        // 这里 approved=true，只表示“这批已经暂停的工具调用得到了玩家许可”。
        // 实际可读取的最大条数仍由 max_approved_search_depth 决定，工具层会继续做 clamp。
        JsonArray toolOutputs = executeToolCalls(toolCalls, toolExecutor, true);
        JsonObject request = followUpRequest(settings, previousResponseId, continuationInput(toolCalls, toolOutputs), language);
        return runLoop(settings, request, true, toolExecutor, false, language);
    }

    /**
     * 执行 Responses API 工具调用循环。
     *
     * <p>如果模型没有请求工具，就直接返回文本。
     * 如果模型请求工具，就执行工具、把工具结果发回 OpenAI，再等待模型总结。</p>
     */
    private static AiRunResult runLoop(AiSettings settings, JsonObject request, boolean toolsEnabled, AiToolExecutor toolExecutor, boolean firstToolCallsApproved, String language)
            throws IOException, InterruptedException, AiApprovalRequiredException {
        boolean approved = firstToolCallsApproved;

        for (int iteration = 0; iteration < settings.maxToolIterations(); iteration++) {
            JsonObject response = send(settings, request);
            String responseId = readString(response, "id");
            List<AiToolCall> toolCalls = readToolCalls(response);

            if (toolCalls.isEmpty()) {
                String text = readOutputText(response);
                if (text.isBlank()) {
                    text = describeEmptyResponse(response);
                }
                return new AiRunResult(text, responseId);
            }

            if (!(toolsEnabled) || toolExecutor == null) {
                return new AiRunResult("AI requested tools, but tools are disabled in this context.", responseId);
            }

            int requestedDepth = maxRequestedDepth(toolCalls);
            if (requestedDepth > settings.maxAutoSearchDepth() && !(approved)) {
                // 第一次遇到超自动深度的工具调用时先暂停，把 OpenAI response id 和 function_call 保存起来。
                // 玩家点击批准后，ServerAiService 会用这些保存的信息继续同一个 Responses API 流程。
                throw new AiApprovalRequiredException(
                        responseId,
                        toolCalls,
                        requestedDepth,
                        settings.maxAutoSearchDepth(),
                        settings.maxApprovedSearchDepth()
                );
            }

            JsonArray toolOutputs = executeToolCalls(toolCalls, toolExecutor, approved);
            request = followUpRequest(settings, responseId, continuationInput(array(response, "output"), toolOutputs), language);
            approved = false;
        }

        throw new IOException("AI tool loop exceeded max_tool_iterations=" + settings.maxToolIterations());
    }

    /** 构造基础请求体。 */
    private static JsonObject baseRequest(AiSettings settings, boolean toolsEnabled) {
        JsonObject request = new JsonObject();
        request.addProperty("model", settings.model());
        request.addProperty("max_output_tokens", settings.maxOutputTokens());

        if (toolsEnabled) {
            request.add("tools", AiDatabaseToolService.toolDefinitions());
            request.addProperty("tool_choice", "auto");
        }

        return request;
    }

    /** 构造工具结果 follow-up 请求。 */
    private static JsonObject followUpRequest(AiSettings settings, String previousResponseId, JsonArray inputItems, String language) {
        JsonObject request = baseRequest(settings, true);
        request.addProperty("instructions", instructions(true, settings.model(), language));
        if (previousResponseId != null && !(previousResponseId.isBlank())) {
            request.addProperty("previous_response_id", previousResponseId);
        }
        request.add("input", inputItems);
        return request;
    }

    /** AI 系统指令。 */
    private static String instructions(boolean toolsEnabled, String model, String language) {
        String clientLanguage = normalizeLanguage(language);
        String languageInstruction = """
                The player's Minecraft client language code is: %s.
                Always reply in that client language. This overrides the language detected from old conversation turns, database records, tool results, examples, or the user's mixed-language text.
                If the code is zh_cn, reply in Simplified Chinese. If zh_tw, reply in Traditional Chinese. If en_us or en_gb, reply in English. If ja_jp, reply in Japanese.
                """.formatted(clientLanguage);
        String componentInstruction = """
                Final answer format:
                Prefer returning a single JSON object with this exact shape, and do not wrap it in markdown/code fences:
                {"worldlogger_component":[{"text":"Title","color":"gold","bold":true},{"text":"\\nDetails","color":"white"}]}
                The mod will render this JSON as Minecraft chat Components.
                Supported segment fields: text, color, bold, italic, underlined, strikethrough, obfuscated, insertion, hover_text, click, extra, font, shadow_color, no_shadow.
                Supported colors include Minecraft color names such as white, gray, gold, aqua, green, yellow, red, dark_red, and hex colors such as #55FF55.
                Use Component fields instead of Markdown. Never output **bold**, *italic*, or `code` for styling; use bold, italic, color, and hover_text fields instead.
                Use color sparingly: gold/aqua for headings, green for safe/normal conclusions, yellow for warnings, red/dark_red for serious risks, gray for secondary details.
                For click actions, use only safe actions: open_url for http/https links, suggest_command to place a command in chat, copy_to_clipboard for copying text, and run_command only for harmless WorldLogger viewing commands such as /worldlogger select, /worldlogger search, or /worldlogger gui.
                Put any extra explanation for a highlighted term in hover_text instead of making the main reply too long.
                If you cannot produce the JSON format, plain text is acceptable, but avoid Markdown styling.
                """;
        if (toolsEnabled) {
            return """
                    You are WorldLogger AI, an assistant inside a Minecraft NeoForge server.
                    Configured model for this request: %s.
                    If the user asks what model you are using, answer with that configured model.
                    %s
                    %s
                    Treat the latest user message as the active task. Do not continue an older database investigation unless the latest user message asks you to.
                    You may chat normally, but when the user asks to summarize, inspect, audit, compare, or analyze WorldLogger database data, use the available tools.
                    If the user asks what a specific player did after entering the world, after joining, after their latest login, or during their latest/current session, call worldlogger_player_activity_after_latest_login directly and summarize its events. Do not stop after only listing available tables.
                    Decide a reasonable database search depth yourself. Use small depth for quick questions and larger depth only when the user asks for broader analysis.
                    Never claim database facts unless you have checked them with tools in this conversation.
                    Keep answers concise and explain which tables or filters you inspected.
                    """.formatted(model, languageInstruction, componentInstruction);
        }

        return """
                You are WorldLogger AI, an assistant inside a Minecraft client.
                Configured model for this request: %s.
                If the user asks what model you are using, answer with that configured model.
                %s
                %s
                Treat the latest user message as the active task. Do not continue an older topic unless the latest user message asks you to.
                This is client-side or singleplayer chat mode. All WorldLogger database operations are unavailable here.
                Do not claim that you checked database records, do not summarize or analyze database contents, and do not invent database facts.
                Do not suggest /worldlogger gui, /worldlogger select, /worldlogger search, or clickable run_command actions as a workaround for database questions in this client mode.
                If the user asks for database logs, table data, player activity, command history, containers, coordinates, or any WorldLogger record analysis, politely say that this client mode cannot access database data and that database analysis must be done with server-side AI on a multiplayer/server environment.
                You can still answer general questions and chat normally.
                """.formatted(model, languageInstruction, componentInstruction);
    }

    /** 规范化客户端语言代码，避免异常内容进入提示词。 */
    private static String normalizeLanguage(String language) {
        if (language == null) {
            return "en_us";
        }
        String normalized = language.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');
        if (normalized.isBlank() || !normalized.matches("[a-z0-9_]+")) {
            return "en_us";
        }
        return normalized;
    }

    /** 向 OpenAI 发送 HTTP 请求并解析 JSON 响应。 */
    private static JsonObject send(AiSettings settings, JsonObject requestBody) throws IOException, InterruptedException {
        String endpoint = responseEndpoint(settings.apiBaseUrl());
        String requestJson = GSON.toJson(requestBody);
        logPayload(settings, "request", endpoint, 0, requestJson);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        logPayload(settings, "response", endpoint, response.statusCode(), response.body());

        JsonObject body = parseObject(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(openAiErrorMessage(body, response.statusCode()));
        }
        return body;
    }

    /**
     * 输出 OpenAI 请求体或响应体调试日志。
     *
     * <p>注意：这里故意不记录 Authorization 请求头，避免 API Key 进入日志。
     * 但 JSON 内容仍可能包含玩家输入、提示词、工具输出和数据库查询结果，
     * 所以必须同时满足配置开关和 DEBUG 日志级别才会输出。</p>
     */
    private static void logPayload(AiSettings settings, String direction, String endpoint, int statusCode, String payload) {
        if (!(settings.debugLogPayloads()) || !(LOGGER.isDebugEnabled())) {
            return;
        }

        String prettyPayload = prettyJsonOrRaw(payload);
        if (statusCode > 0) {
            LOGGER.debug("WorldLogger AI OpenAI {} body. endpoint={}, status={}\n{}", direction, endpoint, statusCode, prettyPayload);
        } else {
            LOGGER.debug("WorldLogger AI OpenAI {} body. endpoint={}\n{}", direction, endpoint, prettyPayload);
        }
    }

    /**
     * 尽量把 JSON 格式化成人类容易阅读的形式；如果不是合法 JSON，就原样返回。
     *
     * @param payload OpenAI 请求体或响应体。
     * @return 格式化后的 JSON，或原始文本。
     */
    private static String prettyJsonOrRaw(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        try {
            JsonElement element = JsonParser.parseString(payload);
            return PRETTY_GSON.toJson(element);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return payload;
        }
    }

    /** 把 base URL 转成 /responses endpoint。 */
    private static String responseEndpoint(String apiBaseUrl) {
        String normalized = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/responses")) {
            return normalized;
        }
        return normalized + "/responses";
    }

    /** 读取 OpenAI 错误信息。 */
    private static String openAiErrorMessage(JsonObject body, int statusCode) {
        if (body.has("error") && body.get("error").isJsonObject()) {
            JsonObject error = body.getAsJsonObject("error");
            String message = readString(error, "message");
            if (!(message.isBlank())) {
                return "OpenAI API error " + statusCode + ": " + message;
            }
        }
        return "OpenAI API error " + statusCode;
    }

    /** 解析 JSON 对象，解析失败时返回包含原始文本的对象。 */
    private static JsonObject parseObject(String text) throws IOException {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (JsonSyntaxException e) {
            throw new IOException("OpenAI returned invalid JSON: " + text, e);
        }
        throw new IOException("OpenAI returned non-object JSON: " + text);
    }

    /** 读取模型请求的所有 function_call。 */
    private static List<AiToolCall> readToolCalls(JsonObject response) {
        List<AiToolCall> calls = new ArrayList<>();
        JsonArray output = array(response, "output");
        for (JsonElement element : output) {
            if (!(element.isJsonObject())) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!"function_call".equals(readString(item, "type"))) {
                continue;
            }

            JsonObject arguments = parseArguments(readString(item, "arguments"));
            calls.add(new AiToolCall(
                    readString(item, "call_id"),
                    readString(item, "name"),
                    arguments,
                    item
            ));
        }
        return calls;
    }

    /** 把模型上一轮 output 和工具结果一起作为下一轮 input。 */
    private static JsonArray continuationInput(JsonArray previousOutput, JsonArray toolOutputs) {
        JsonArray input = new JsonArray();
        for (JsonElement element : previousOutput) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            input.add(element.deepCopy());
        }
        appendToolOutputs(input, toolOutputs);
        return input;
    }

    /** 审批流程没有完整 response 对象，所以用保存下来的 function_call 项恢复下一轮 input。 */
    private static JsonArray continuationInput(List<AiToolCall> toolCalls, JsonArray toolOutputs) {
        JsonArray input = new JsonArray();
        for (AiToolCall call : toolCalls) {
            input.add(call.inputItem());
        }
        appendToolOutputs(input, toolOutputs);
        return input;
    }

    /** 追加工具输出；单独拆出来避免普通流程和审批流程出现拼接差异。 */
    private static void appendToolOutputs(JsonArray input, JsonArray toolOutputs) {
        for (JsonElement output : toolOutputs) {
            if (output == null || output.isJsonNull()) {
                continue;
            }
            input.add(output.deepCopy());
        }
    }

    /** 解析工具 arguments 字符串。 */
    private static JsonObject parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new JsonObject();
        }

        try {
            JsonElement element = JsonParser.parseString(arguments);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException e) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("_raw_arguments", arguments);
            return fallback;
        }
    }

    /** 执行所有工具调用，并组装 function_call_output 数组。 */
    private static JsonArray executeToolCalls(List<AiToolCall> toolCalls, AiToolExecutor toolExecutor, boolean approved) throws IOException {
        JsonArray outputs = new JsonArray();
        for (AiToolCall call : toolCalls) {
            JsonObject output = new JsonObject();
            output.addProperty("type", "function_call_output");
            output.addProperty("call_id", call.callId());

            try {
                JsonObject result = toolExecutor.execute(call, approved);
                output.addProperty("output", GSON.toJson(result));
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("success", false);
                error.addProperty("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                output.addProperty("output", GSON.toJson(error));
            }

            outputs.add(output);
        }
        return outputs;
    }

    /** 读取工具调用中最大的 depth 参数。 */
    private static int maxRequestedDepth(List<AiToolCall> toolCalls) {
        int max = 1;
        for (AiToolCall call : toolCalls) {
            max = Math.max(max, requestedDepth(call.arguments()));
        }
        return max;
    }

    /** 从工具参数中读取 depth；缺失或非法时返回 1。 */
    public static int requestedDepth(JsonObject arguments) {
        if (arguments == null || !(arguments.has("depth"))) {
            return 1;
        }
        try {
            return Math.max(1, arguments.get("depth").getAsInt());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** 读取最终输出文本。 */
    private static String readOutputText(JsonObject response) {
        String outputText = readString(response, "output_text");
        if (!(outputText.isBlank())) {
            return outputText;
        }

        String choicesText = readChoicesText(response);
        if (!(choicesText.isBlank())) {
            return choicesText;
        }

        StringBuilder text = new StringBuilder();
        JsonArray output = array(response, "output");
        for (JsonElement outputElement : output) {
            if (!(outputElement.isJsonObject())) {
                continue;
            }
            JsonObject outputItem = outputElement.getAsJsonObject();
            JsonArray content = array(outputItem, "content");
            for (JsonElement contentElement : content) {
                appendText(text, contentElement);
            }

            if (content.isEmpty()) {
                appendText(text, outputItem);
            }
        }
        return text.toString();
    }

    /** 兼容 Chat Completions 风格的 choices 返回。 */
    private static String readChoicesText(JsonObject response) {
        StringBuilder text = new StringBuilder();
        JsonArray choices = array(response, "choices");
        for (JsonElement choiceElement : choices) {
            if (!(choiceElement.isJsonObject())) {
                continue;
            }
            JsonObject choice = choiceElement.getAsJsonObject();
            if (choice.has("message") && choice.get("message").isJsonObject()) {
                appendText(text, choice.getAsJsonObject("message").get("content"));
            }
            appendText(text, choice.get("text"));
        }
        return text.toString();
    }

    /** 从各种常见 JSON 结构里提取文本。 */
    private static void appendText(StringBuilder text, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }

        if (element.isJsonPrimitive()) {
            appendLine(text, element.getAsString());
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                appendText(text, child);
            }
            return;
        }

        if (!(element.isJsonObject())) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        String type = readString(object, "type");
        if ("function_call".equals(type) || "function_call_output".equals(type)) {
            return;
        }

        if (object.has("text")) {
            appendText(text, object.get("text"));
        }
        if (object.has("content")) {
            appendText(text, object.get("content"));
        }
        if (object.has("message")) {
            appendText(text, object.get("message"));
        }
        if (object.has("value")) {
            appendText(text, object.get("value"));
        }
    }

    /** 追加一行文本，自动跳过空白。 */
    private static void appendLine(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(value);
    }

    /** 没有提取到文本时，生成更有帮助的诊断信息。 */
    private static String describeEmptyResponse(JsonObject response) {
        String status = readString(response, "status");
        String error = readNestedMessage(response, "error");
        String incomplete = readNestedMessage(response, "incomplete_details");
        String outputTypes = outputTypes(response);

        StringBuilder builder = new StringBuilder("AI did not return readable text.");
        if (!(status.isBlank())) {
            builder.append(" status=").append(status).append('.');
        }
        if (!(error.isBlank())) {
            builder.append(" error=").append(error).append('.');
        }
        if (!(incomplete.isBlank())) {
            builder.append(" incomplete_details=").append(incomplete).append('.');
        }
        if (!(outputTypes.isBlank())) {
            builder.append(" output_types=").append(outputTypes).append('.');
        }
        return builder.toString();
    }

    /** 读取嵌套对象中的 message 或 reason。 */
    private static String readNestedMessage(JsonObject response, String key) {
        if (!(response.has(key)) || !(response.get(key).isJsonObject())) {
            return "";
        }
        JsonObject object = response.getAsJsonObject(key);
        String message = readString(object, "message");
        if (!(message.isBlank())) {
            return message;
        }
        return readString(object, "reason");
    }

    /** 汇总 output 数组里出现的 item 类型，方便排查兼容网关返回结构。 */
    private static String outputTypes(JsonObject response) {
        JsonArray output = array(response, "output");
        if (output.isEmpty()) {
            return "";
        }

        List<String> types = new ArrayList<>();
        for (JsonElement element : output) {
            if (!(element.isJsonObject())) {
                types.add(element.getClass().getSimpleName());
                continue;
            }
            String type = readString(element.getAsJsonObject(), "type");
            types.add(type.isBlank() ? "object" : type);
        }
        return String.join(",", types);
    }

    /** 安全读取字符串字段。 */
    private static String readString(JsonObject object, String key) {
        if (object == null || !(object.has(key)) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 安全读取数组字段。 */
    private static JsonArray array(JsonObject object, String key) {
        if (object != null && object.has(key) && object.get(key).isJsonArray()) {
            return object.getAsJsonArray(key);
        }
        return new JsonArray();
    }
}
