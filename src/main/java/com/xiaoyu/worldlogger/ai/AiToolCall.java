package com.xiaoyu.worldlogger.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * AI 模型请求执行的一次工具调用。
 *
 * @param callId OpenAI 返回的调用 ID，工具结果必须带回同一个 ID。
 * @param name 工具名称。
 * @param arguments 工具参数 JSON。
 * @param rawItem OpenAI 原始 function_call 项。审批后继续对话时必须把它原样带回。
 */
public record AiToolCall(String callId, String name, JsonObject arguments, JsonObject rawItem) {
    /** 兼容旧调用点：没有原始 function_call 项时，用空对象代替。 */
    public AiToolCall(String callId, String name, JsonObject arguments) {
        this(callId, name, arguments, new JsonObject());
    }

    /** 构造时复制 JSON，防止外部代码修改 record 内部保存的工具参数。 */
    public AiToolCall {
        arguments = copy(arguments);
        rawItem = copy(rawItem);
    }

    /** @return 工具参数副本；调用方可以读取或修改副本，不会影响当前对象。 */
    public JsonObject arguments() {
        return copy(arguments);
    }

    /** @return OpenAI 原始 function_call 项副本。 */
    public JsonObject rawItem() {
        return copy(rawItem);
    }

    /**
     * 生成下一轮 Responses API input 需要的 function_call 项。
     *
     * @return 可直接和 function_call_output 放在同一个 input 数组里的 JSON 对象。
     */
    public JsonObject inputItem() {
        // 优先使用原始对象，因为 OpenAI 需要看到自己上一轮产生的 function_call。
        JsonObject item = copy(rawItem);
        if (!"function_call".equals(readString(item, "type"))) {
            item = new JsonObject();
            item.addProperty("type", "function_call");
        }
        if (!item.has("call_id") || item.get("call_id").isJsonNull()) {
            item.addProperty("call_id", callId);
        }
        if (!item.has("name") || item.get("name").isJsonNull()) {
            item.addProperty("name", name);
        }
        if (!item.has("arguments") || item.get("arguments").isJsonNull()) {
            item.addProperty("arguments", arguments.toString());
        }
        return item;
    }

    private static JsonObject copy(JsonObject object) {
        if (object == null) {
            return new JsonObject();
        }
        // deepCopy 可以复制嵌套对象，避免浅拷贝导致内部 JSON 被外部引用改动。
        JsonElement copied = object.deepCopy();
        return copied.isJsonObject() ? copied.getAsJsonObject() : new JsonObject();
    }

    private static String readString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
