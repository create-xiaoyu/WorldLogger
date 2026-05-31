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

public class StringData {
    public static String getLevelName(Level level) {
        return String.format("[%s], %s", level.getDescription().getString(), level.dimension().identifier());
    }

    public static String getEntityName(Entity entity) {
        return String.format("[%s], ID: %s", entity.getName().getString(), entity.typeHolder().getRegisteredName());
    }

    public static String getEntityName(PlayerSessionData data) {
        return String.format("[Player] UUID: %s, NAME: %s", data.uuid, data.name);
    }

    public static String getPos(Entity entity) {
        return String.format("[X: %d, Y: %d, Z: %d]", entity.getBlockX(), entity.getBlockY(), entity.getBlockZ());
    }

    public static String getPos(Player player) {
        return String.format("[X: %d, Y: %d, Z: %d]", player.blockPosition().getX(), player.blockPosition().getY(), player.blockPosition().getZ());
    }

    public static String getPos(BlockPos pos) {
        return String.format("[X: %d, Y: %d, Z: %d]", pos.getX(), pos.getY(), pos.getZ());
    }

    public static long getGameTime(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 20;
    }

    public static String getTime() {
        LocalDateTime nowTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return nowTime.format(formatter);
    }

    public static String getPlayerIP (ServerPlayer serverPlayer) {
        SocketAddress socketAddress = serverPlayer.connection.getRemoteAddress();
        if (!(socketAddress instanceof InetSocketAddress inetSocketAddress)) return null;

        return inetSocketAddress.getAddress().getHostAddress();
    }
}
