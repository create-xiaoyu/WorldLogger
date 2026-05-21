package com.xiaoyu.worldlogger.writetable;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
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

public class ExecuteCommandInfo {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void EXECUTE_COMMAND_INFO(CommandEvent event) {
        ParseResults<CommandSourceStack> results = event.getParseResults();
        CommandContextBuilder<CommandSourceStack> ctx = results.getContext();
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();

        PlayerSessionData data;
        String executePos = null;
        String executeWorld = null;

        String executeSource = "unknown";
        String executeCommand = results.getReader().getString();

        if (player != null) {
            Level level = player.level();
            data = new PlayerSessionData(player, level);

            executeSource = String.format("[Player] UUID: %s, NAME: %s", data.uuid, data.name);
            executePos = data.pos;
            executeWorld = data.world;
        } else if (source.getEntity() != null) {
            executeSource = source.getEntity().getName().getString();
            executePos = StringData.getPos(source.getEntity());
            executeWorld = StringData.getLevelName(source.getEntity().level());
        } else if (source.source instanceof BaseCommandBlock) {
            executeSource = "CommandBlock";
            executePos = source.getPosition().toString();
            executeWorld = StringData.getLevelName(source.getLevel());
        } else if (source.source instanceof RconConsoleSource) {
            executeSource = "RCON";
        } else if (source.source instanceof MinecraftServer) {
            executeSource = "ServerConsole";
        }

        String SQL = """
                     INSERT INTO EXECUTE_COMMAND_INFO(
                         execute_source,
                         execute_pos,
                         execute_world,
                         execute_command
                     ) VALUES (?, ?, ?, ?)
                     """;

        try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
            try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                statement.setString(1, executeSource);
                statement.setString(2, executePos);
                statement.setString(3, executeWorld);
                statement.setString(4, executeCommand);

                statement.executeUpdate();
            } catch (SQLException e) {
                LOGGER.error("Failed to execute SQL statement {}", SQL, e);
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to connect to MySQL server!", e);
        }
    }
}
