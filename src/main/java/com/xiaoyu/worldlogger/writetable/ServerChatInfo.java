package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ServerChatInfo {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void SERVER_CHAT_INFO(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);
        String rawMessage = event.getRawText();
        String componentMessage = event.getMessage().toString();

        String SQL = """
                     INSERT INTO SERVER_CHAT_INFO(
                         player_uuid,
                         player_name,
                         pos,
                         world,
                         raw_message,
                         component_message
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """;

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, data.pos);
                    statement.setString(4, data.world);
                    statement.setString(5, rawMessage);
                    statement.setString(6, componentMessage);

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
