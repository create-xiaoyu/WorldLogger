package com.xiaoyu.worldlogger.data;

import com.xiaoyu.worldlogger.utils.StringData;
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
        this.pos = StringData.getPos(player);
        this.world = StringData.getLevelName(level);
        this.gameTime = StringData.getGameTime(player);
        this.time = StringData.getTime();
        this.ip = StringData.getPlayerIP(player);
    }
}
