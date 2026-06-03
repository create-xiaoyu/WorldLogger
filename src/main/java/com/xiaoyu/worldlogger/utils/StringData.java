package com.xiaoyu.worldlogger.utils;

import com.xiaoyu.worldlogger.data.PlayerSessionData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 把 Minecraft 对象转换成数据库和聊天栏更容易阅读的字符串。
 *
 * <p>这些方法没有副作用，只负责格式化数据。统一格式非常重要，
 * 因为附近搜索会依赖坐标字符串中的 X/Y/Z 来解析位置。</p>
 */
public class StringData {
    /**
     * 获取世界名称和维度 ID。
     *
     * @param level 世界对象。
     * @return 形如 [Overworld], minecraft:overworld 的字符串。
     */
    public static String getLevelName(Level level) {
        return String.format("[%s], %s", level.getDescription().getString(), level.dimension().identifier());
    }

    /**
     * 获取普通实体名称和实体类型 ID。
     *
     * @param entity 任意实体。
     * @return 形如 [Zombie], ID: minecraft:zombie 的字符串。
     */
    public static String getEntityName(Entity entity) {
        return String.format("[%s], ID: %s", entity.getName().getString(), entity.typeHolder().getRegisteredName());
    }

    /**
     * 根据玩家快照生成玩家显示名。
     *
     * @param data PlayerSessionData 快照。
     * @return 包含 UUID 和名字的字符串。
     */
    public static String getEntityName(PlayerSessionData data) {
        return String.format("[Player] UUID: %s, NAME: %s", data.uuid, data.name);
    }

    /**
     * 根据 Player 对象生成玩家显示名。
     *
     * @param player 玩家对象。
     * @return 包含 UUID 和名字的字符串。
     */
    public static String getEntityName(Player player) {
        return String.format("[Player] UUID: %s, NAME: %s", player.getStringUUID(), player.getName().getString());
    }

    /**
     * 获取实体所在方块坐标。
     *
     * @param entity 实体对象。
     * @return 固定格式坐标字符串，供数据库和搜索解析使用。
     */
    public static String getPos(Entity entity) {
        return String.format("[X: %d, Y: %d, Z: %d]", entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
    }

    /**
     * 获取玩家所在方块坐标。
     *
     * @param player 玩家对象。
     * @return 固定格式坐标字符串。
     */
    public static String getPos(Player player) {
        return String.format("[X: %d, Y: %d, Z: %d]", player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
    }

    /**
     * 获取 BlockPos 坐标。
     *
     * @param pos 方块坐标对象。
     * @return 固定格式坐标字符串。
     */
    public static String getPos(BlockPos pos) {
        return String.format("[X: %d, Y: %d, Z: %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * 获取玩家累计游玩时间。
     *
     * @param player 服务端玩家。
     * @return 游玩时间秒数。Minecraft 统计值单位是 tick，20 tick 等于 1 秒。
     */
    public static long getGameTime(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 20;
    }

    /**
     * 获取当前本地时间字符串。
     *
     * @return 形如 2026-06-04 01:23:45 的时间。
     */
    public static String getTime() {
        LocalDateTime nowTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return nowTime.format(formatter);
    }

    /**
     * 获取玩家 IP。
     *
     * @param serverPlayer 服务端玩家。
     * @return 玩家 IP 字符串；如果连接地址不是 InetSocketAddress，则返回 null。
     */
    public static String getPlayerIP (ServerPlayer serverPlayer) {
        SocketAddress socketAddress = serverPlayer.connection.getRemoteAddress();
        if (!(socketAddress instanceof InetSocketAddress inetSocketAddress)) return null;

        return inetSocketAddress.getAddress().getHostAddress();
    }
}
