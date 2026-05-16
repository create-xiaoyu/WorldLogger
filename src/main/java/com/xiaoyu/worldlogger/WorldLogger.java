package com.xiaoyu.worldlogger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;

@Mod(WorldLogger.MODID)
public class WorldLogger {
    public static final String MODID = "worldlogger";

    public static final Logger LOGGER = LogUtils.getLogger();

}
