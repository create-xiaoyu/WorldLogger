package com.xiaoyu.worldlogger;

import com.xiaoyu.worldlogger.event.PlayerInteractEvent.RightClickBlock;
import com.xiaoyu.worldlogger.writetable.*;
import com.xiaoyu.worldlogger.mysql.DataBase;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;

import java.sql.Connection;
import java.sql.SQLException;

@Mod(WorldLogger.MODID)
public class WorldLogger {
    public static final String MODID = "worldlogger";
    private static final Logger LOGGER = LogUtils.getLogger();

    public WorldLogger(ModContainer container, IEventBus modBus) {
        if (FMLEnvironment.getDist().isDedicatedServer()) {
            NeoForge.EVENT_BUS.addListener(WorldLogger::onServerStarted);
            NeoForge.EVENT_BUS.addListener(WorldLogger::onServerStopped);

            NeoForge.EVENT_BUS.register(PlayerInfo.class);
            NeoForge.EVENT_BUS.register(PlayerDeathInfo.class);
            NeoForge.EVENT_BUS.register(ExecuteCommandInfo.class);
            NeoForge.EVENT_BUS.register(ServerChatInfo.class);
            NeoForge.EVENT_BUS.register(PlayerLostItem.class);
            NeoForge.EVENT_BUS.register(RightClickBlock.class);
            NeoForge.EVENT_BUS.register(PlayerXPInfo.class);
            NeoForge.EVENT_BUS.register(PlayerContainerInfo.class);
        }

        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    public static void onServerStarted(ServerStartedEvent event) {
        InitMySQL.InitHikari();
        MySQLExecutorService.init();

        try (Connection mySQLConnection = InitMySQL.getMySQLConnection()) {
            DataBase.InitDataBaseTable(mySQLConnection);
        } catch (SQLException e) {
            LOGGER.error("Failed to connect to MySQL server!", e);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        MySQLExecutorService.shutdown();
        InitMySQL.closeHikari();
    }
}
