package com.xiaoyu.worldlogger.event.PlayerEvent.PlayerLoggedInEvent.WriteTable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.mysql.WriteTable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PlayerInfo {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void PLAYER_BASE_INFO(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);

        String SQL = """
                     INSERT INTO PLAYER_BASE_INFO(player_uuid, player_name, player_game_time) VALUES (?, ?, ?)
                     ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), player_game_time = VALUES(player_game_time)
                     """;
        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setLong(3, data.gameTime);
                    statement.executeUpdate();
                } catch (SQLException e) {
                    LOGGER.error("Failed to execute SQL statement {}", SQL, e);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to connect to MySQL server!", e);
            }
        });

        WriteTable.PLAYER_SESSION_INFO(player, level, true);
    }

    @SubscribeEvent
    public static void PLAYER_LOGOUT_INFO(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        WriteTable.PLAYER_SESSION_INFO(player, level, false);
    }
}
