package com.xiaoyu.worldlogger.writetable;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.level.BaseCommandBlock;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 命令执行记录器。
 *
 * <p>任何通过 Minecraft 命令系统执行的命令都会触发 CommandEvent。
 * 命令来源可能是玩家、实体、命令方块、RCON 或服务器控制台，所以这里需要分支判断来源类型。</p>
 */
public class ExecuteCommandInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 命令执行时触发。
     *
     * @param event 命令事件，包含解析结果和命令来源。
     */
    @SubscribeEvent
    public static void EXECUTE_COMMAND_INFO(CommandEvent event) {
        // ParseResults 保存命令解析结果，里面可以拿到原始命令字符串和命令上下文。
        ParseResults<CommandSourceStack> results = event.getParseResults();
        CommandContextBuilder<CommandSourceStack> ctx = results.getContext();
        CommandSourceStack source = ctx.getSource();

        // 如果命令来源是玩家，这里会拿到 ServerPlayer；否则为 null。
        ServerPlayer player = source.getPlayer();

        PlayerSessionData data;

        // 执行位置和世界可能不存在，例如 RCON 和服务器控制台。
        String executePos = null;
        String executeWorld = null;

        // 默认来源先写 unknown，后续根据真实来源覆盖。
        String executeSource = "unknown";

        // reader 中保存玩家或控制台输入的原始命令文本。
        String executeCommand = results.getReader().getString();

        // 玩家执行命令：保存 UUID、名字、位置和世界。
        if (player != null) {
            Level level = player.level();
            data = new PlayerSessionData(player, level);

            executeSource = String.format("[Player] UUID: %s, NAME: %s", data.uuid, data.name);
            executePos = data.pos;
            executeWorld = data.world;
        // 非玩家实体执行命令，例如某些模组实体或命令执行上下文。
        } else if (source.getEntity() != null) {
            executeSource = source.getEntity().getName().getString();
            executePos = StringData.getPos(source.getEntity());
            executeWorld = StringData.getLevelName(source.getEntity().level());
        // 命令方块执行命令。
        } else if (source.source instanceof BaseCommandBlock) {
            executeSource = "CommandBlock";
            executePos = source.getPosition().toString();
            executeWorld = StringData.getLevelName(source.getLevel());
        // RCON 远程控制台执行命令。
        } else if (source.source instanceof RconConsoleSource) {
            executeSource = "RCON";
        // 服务器控制台执行命令。
        } else if (source.source instanceof MinecraftServer) {
            executeSource = "ServerConsole";
        }

        // command 使用 TEXT，避免长命令被截断。
        String SQL = """
                     INSERT INTO EXECUTE_COMMAND_INFO(
                         source,
                         pos,
                         world,
                         command
                     ) VALUES (?, ?, ?, ?)
                     """;

        // lambda 中使用的变量需要 final 或 effectively final，所以复制到 final 局部变量。
        final String finalExecuteSource = executeSource;
        final String finalExecutePos = executePos;
        final String finalExecuteWorld = executeWorld;

        // 异步写入命令执行记录。
        MySQLExecutorService.execute(LOGGER, "write command execution info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, finalExecuteSource);
                    statement.setString(2, finalExecutePos);
                    statement.setString(3, finalExecuteWorld);
                    statement.setString(4, executeCommand);

                    statement.executeUpdate();
                } catch (SQLException e) {
                    LOGGER.error("Failed to execute SQL statement {}", SQL, e);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to connect to MySQL server!", e);
            }
        });
    }
}
