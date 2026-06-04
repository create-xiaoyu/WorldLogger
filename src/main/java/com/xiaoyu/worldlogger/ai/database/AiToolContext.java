package com.xiaoyu.worldlogger.ai.database;

import java.util.UUID;

/**
 * AI 数据库工具执行时需要的玩家上下文。
 *
 * @param playerId 玩家 UUID。
 * @param playerName 玩家名。
 * @param centerX 玩家当前位置 X。
 * @param centerY 玩家当前位置 Y。
 * @param centerZ 玩家当前位置 Z。
 * @param worldId 玩家所在维度 ID。
 */
public record AiToolContext(
        UUID playerId,
        String playerName,
        int centerX,
        int centerY,
        int centerZ,
        String worldId
) {}
