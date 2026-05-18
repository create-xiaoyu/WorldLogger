package com.xiaoyu.worldlogger.utils;

import com.mojang.logging.LogUtils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TimeUtils {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static long getGameTime(ServerPlayer player) {
        return player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 20;
    }

    public static String getTime() {
        LocalDateTime nowTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return nowTime.format(formatter);
    }
}
