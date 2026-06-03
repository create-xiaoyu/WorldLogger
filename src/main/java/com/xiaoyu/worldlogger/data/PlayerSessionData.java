package com.xiaoyu.worldlogger.data;

import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 玩家在某个时刻的基础信息快照。
 *
 * <p>很多事件都会用到玩家 UUID、名字、坐标、世界和在线时间。
 * 把这些字段集中到一个类里，可以减少重复代码，也方便异步写库前先把运行时对象变成普通数据。</p>
 */
public class PlayerSessionData {
    /** 玩家 UUID 字符串。UUID 比名字稳定，玩家改名后仍然不会变。 */
    public final String uuid;

    /** 玩家当前名字。名字方便人类阅读，但不适合作为唯一身份。 */
    public final String name;

    /** 玩家当前位置，格式为 [X: ?, Y: ?, Z: ?]。 */
    public final String pos;

    /** 玩家当前所在世界的显示名和维度 ID。 */
    public final String world;

    /** 当前系统时间字符串，适合写入手动指定 time 的表。 */
    public final String time;

    /** 玩家总游玩时间，单位是秒。 */
    public final long gameTime;

    /** 玩家 IP 地址。单机或特殊连接情况下可能为 null。 */
    public final String ip;

    /**
     * 从 ServerPlayer 和 Level 中提取玩家会话信息。
     *
     * @param player 服务端玩家对象。
     * @param level 玩家所在世界。
     */
    public PlayerSessionData(ServerPlayer player, Level level) {
        this.uuid = player.getStringUUID();
        this.name = player.getName().getString();
        this.pos = StringData.getPos(player);
        this.world = StringData.getLevelName(level);
        this.gameTime = StringData.getGameTime(player);
        this.time = StringData.getTime();
        this.ip = StringData.getPlayerIP(player);
    }
}
