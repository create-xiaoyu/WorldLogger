package com.xiaoyu.worldlogger.ai;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存每个玩家最近一次 OpenAI response id。
 *
 * <p>Responses API 可以用 previous_response_id 延续上下文。
 * 这样玩家连续执行 /worldlogger ai 时，AI 可以记住前面几轮对话。</p>
 */
public final class AiConversationStore {
    /** 玩家 UUID -> OpenAI response id。 */
    private static final Map<UUID, String> RESPONSE_IDS = new ConcurrentHashMap<>();

    /** 工具类不需要实例化。 */
    private AiConversationStore() {}

    /** @return 玩家上一轮 response id；没有历史时返回 null。 */
    public static String get(UUID playerId) {
        return RESPONSE_IDS.get(playerId);
    }

    /** 保存玩家最新 response id。 */
    public static void set(UUID playerId, String responseId) {
        if (responseId == null || responseId.isBlank()) {
            return;
        }
        RESPONSE_IDS.put(playerId, responseId);
    }

    /** 清理某个玩家的 AI 对话上下文。 */
    public static void clear(UUID playerId) {
        RESPONSE_IDS.remove(playerId);
    }
}
