package com.xiaoyu.worldlogger.utils;

import net.minecraft.world.level.Level;

public class StringData {
    public static String getLevelName(Level level) {
        return String.format("[%s], %S", level.getDescription().getString(), level.dimension().identifier());
    }
}
