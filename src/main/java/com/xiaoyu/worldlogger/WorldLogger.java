package com.xiaoyu.worldlogger;

import com.xiaoyu.worldlogger.command.MainCommand;
import com.xiaoyu.worldlogger.ai.AiConfig;
import com.xiaoyu.worldlogger.ai.AiExecutorService;
import com.xiaoyu.worldlogger.event.CommandEvent.ExecuteCommands;
import com.xiaoyu.worldlogger.event.PlayerInteractEvent.RightClickBlock;
import com.xiaoyu.worldlogger.network.WorldLoggerNetwork;
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
    /** 模组 ID 必须和 resources/META-INF/neoforge.mods.toml 以及资源目录中的命名空间保持一致。 */
    public static final String MODID = "worldlogger";

    /** 日志对象用于向控制台输出信息、警告和错误，方便排查数据库或事件注册问题。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * NeoForge 创建模组实例时会调用这个构造方法。
     *
     * @param container 当前模组容器，用来注册配置文件等模组级内容。
     * @param modBus 模组事件总线，用来注册网络包等“加载阶段”的监听器。
     *
     * 注意：这里区分客户端和专用服务端，是为了避免服务端加载只存在于客户端的类。
     */
    public WorldLogger(ModContainer container, IEventBus modBus) {
        // 注册自定义网络包。客户端命令查询数据库时，会先把请求发到服务器。
        modBus.addListener(WorldLoggerNetwork::register);

        // 专用服务端负责监听游戏事件、写入数据库、初始化数据库连接。
        if (FMLEnvironment.getDist().isDedicatedServer()) {
            // 服务器启动时连接 MySQL 并建表；服务器关闭时释放线程池和数据库连接池。
            NeoForge.EVENT_BUS.addListener(WorldLogger::onServerStarted);
            NeoForge.EVENT_BUS.addListener(WorldLogger::onServerStopped);

            // 注册所有写表事件类。被 @SubscribeEvent 标记的方法会自动接收对应事件。
            NeoForge.EVENT_BUS.register(PlayerInfo.class);
            NeoForge.EVENT_BUS.register(PlayerDeathInfo.class);
            NeoForge.EVENT_BUS.register(ExecuteCommandInfo.class);
            NeoForge.EVENT_BUS.register(ServerChatInfo.class);
            NeoForge.EVENT_BUS.register(PlayerLostItem.class);
            NeoForge.EVENT_BUS.register(RightClickBlock.class);
            NeoForge.EVENT_BUS.register(PlayerXPInfo.class);
            NeoForge.EVENT_BUS.register(PlayerContainerInfo.class);
            NeoForge.EVENT_BUS.register(EntityPlaceBlock.class);
            NeoForge.EVENT_BUS.register(PlayerBreakInfo.class);
            NeoForge.EVENT_BUS.register(EntityBreakInfo.class);
            NeoForge.EVENT_BUS.register(ExplosionBreakBlock.class);
            NeoForge.EVENT_BUS.register(EntityDeathInfo.class);
            NeoForge.EVENT_BUS.register(EntitySpawnInfo.class);
            NeoForge.EVENT_BUS.register(ExecuteCommands.class);
        }

        // 客户端注册 /worldlogger 命令入口。命令本身在客户端执行，但数据库查询由服务器完成。
        if (FMLEnvironment.getDist().isClient()) {
            NeoForge.EVENT_BUS.register(MainCommand.class);
        }

        // 注册 common 配置文件，让用户可以修改数据库地址、账号和线程数。
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // 注册服务器 AI 配置。专用服务器使用这里的模型、API 地址、密钥和数据库工具深度限制。
        container.registerConfig(ModConfig.Type.SERVER, AiConfig.SERVER_SPEC);

        // 注册客户端 AI 配置。它只用于单人游戏日常聊天，不包含数据库工具。
        if (FMLEnvironment.getDist().isClient()) {
            container.registerConfig(ModConfig.Type.CLIENT, AiConfig.CLIENT_SPEC);
        }
    }

    /**
     * 服务器启动完成后调用。
     *
     * @param event NeoForge 的服务器启动事件，这里不直接使用它，只把它作为监听器签名需要的参数。
     *
     * 用法：先初始化 HikariCP 连接池，再初始化 MySQL 线程池，最后创建缺失的数据表。
     */
    public static void onServerStarted(ServerStartedEvent event) {
        // 如果数据库连接池创建失败，后续数据库功能都不能安全运行，因此直接返回。
        if (!(InitMySQL.InitHikari())) {
            LOGGER.error("WorldLogger database features are disabled because MySQL initialization failed.");
            return;
        }

        // 数据库读写可能很慢，所以统一放进线程池，避免卡住 Minecraft 主线程。
        MySQLExecutorService.init();

        // try-with-resources 会在代码块结束后自动关闭 Connection，防止连接泄漏。
        try (Connection mySQLConnection = InitMySQL.getMySQLConnection()) {
            // 创建所有表；如果表已经存在，SQL 中的 IF NOT EXISTS 会避免重复创建报错。
            DataBase.InitDataBaseTable(mySQLConnection);
        } catch (SQLException e) {
            LOGGER.error("Failed to connect to MySQL server!", e);
        }
    }

    /**
     * 服务器关闭时调用。
     *
     * @param event NeoForge 的服务器停止事件，这里不直接使用它。
     *
     * 注意：先关闭线程池，再关闭连接池，可以减少“任务还没结束但连接池没了”的概率。
     */
    public static void onServerStopped(ServerStoppedEvent event) {
        MySQLExecutorService.shutdown();
        AiExecutorService.shutdown();
        InitMySQL.closeHikari();
    }
}
