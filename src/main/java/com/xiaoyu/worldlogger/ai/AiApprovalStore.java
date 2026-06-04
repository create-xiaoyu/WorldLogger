package com.xiaoyu.worldlogger.ai;

import com.xiaoyu.worldlogger.ai.database.AiToolContext;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 保存等待玩家确认的 AI 深度查询请求。
 */
public final class AiApprovalStore {
    /** 审批请求有效期。 */
    private static final Duration EXPIRATION = Duration.ofMinutes(10);

    /** 用于生成短 ID 的字符表。 */
    private static final char[] ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** 安全随机数，用于生成不容易猜的审批 ID。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 审批 ID -> 待确认请求。 */
    private static final Map<String, AiPendingApproval> PENDING = new ConcurrentHashMap<>();

    /** 工具类不需要实例化。 */
    private AiApprovalStore() {}

    /** 创建并保存新的审批请求。 */
    public static AiPendingApproval create(UUID playerId, AiSettings settings, AiToolContext context, String language, String previousResponseId, List<AiToolCall> toolCalls, int requestedDepth, int autoLimit) {
        cleanupExpired();
        String id = newId();
        AiPendingApproval pending = new AiPendingApproval(
                id,
                playerId,
                settings,
                context,
                language,
                previousResponseId,
                toolCalls,
                requestedDepth,
                autoLimit,
                Instant.now()
        );
        PENDING.put(id, pending);
        return pending;
    }

    /**
     * 取出并删除审批请求。
     *
     * @param id 审批 ID。
     * @param playerId 执行 approve 的玩家 UUID。
     * @return 匹配且未过期的请求。
     * @throws IllegalArgumentException ID 不存在、已过期或玩家不匹配时抛出。
     */
    public static AiPendingApproval take(String id, UUID playerId) {
        cleanupExpired();
        AiPendingApproval pending = PENDING.remove(id);
        if (pending == null) {
            throw new IllegalArgumentException("Unknown or expired approval id: " + id);
        }
        if (!(pending.playerId().equals(playerId))) {
            PENDING.put(id, pending);
            throw new IllegalArgumentException("This approval id belongs to another player.");
        }
        if (isExpired(pending)) {
            throw new IllegalArgumentException("Approval id expired: " + id);
        }
        return pending;
    }

    /** 清理某个玩家的所有待审批请求。 */
    public static void clear(UUID playerId) {
        PENDING.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
    }

    /** 生成 8 位审批 ID。 */
    private static String newId() {
        String id;
        do {
            StringBuilder builder = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                builder.append(ID_CHARS[RANDOM.nextInt(ID_CHARS.length)]);
            }
            id = builder.toString();
        } while (PENDING.containsKey(id));
        return id;
    }

    /** 清理过期请求。 */
    private static void cleanupExpired() {
        PENDING.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    /** 判断请求是否过期。 */
    private static boolean isExpired(AiPendingApproval pending) {
        return pending.createdAt().plus(EXPIRATION).isBefore(Instant.now());
    }
}
