package com.xiaoyu.worldlogger.data;

import com.xiaoyu.worldlogger.utils.NetworkUtils;
import com.xiaoyu.worldlogger.utils.StringData;
import com.xiaoyu.worldlogger.utils.TimeUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public class PlayerSessionData {
    public final String uuid;
    public final String name;
    public final String pos;
    public final String world;
    public final String time;
    public final long gameTime;
    public final String ip;

    public PlayerSessionData(ServerPlayer player, Level level) {
        this.uuid = player.getStringUUID();
        this.name = player.getName().getString();
        this.pos = String.format("[X: %d, Y: %d, Z: %d]", player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
        this.world = StringData.getLevelName(level);
        this.gameTime = TimeUtils.getGameTime(player);
        this.time = TimeUtils.getTime();
        this.ip = NetworkUtils.getPlayerIP(player);
    }
}
