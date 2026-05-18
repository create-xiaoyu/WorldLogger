package com.xiaoyu.worldlogger.event.PlayerEvent.PlayerLoggedInEvent.WriteTable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.mysql.WriteTable;
import com.xiaoyu.worldlogger.utils.TimeUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class WriteTablePlayer {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void PLAYER_BASE_INFO(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        String SQL = """
                     INSERT INTO PLAYER_BASE_INFO(player_uuid, player_name, player_game_time) VALUES (?, ?, ?)
                     ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), player_game_time = VALUES(player_game_time)
                     """;
        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, player.getStringUUID());
                    statement.setString(2, player.getName().getString());
                    statement.setLong(3, TimeUtils.getGameTime(serverPlayer));
                    statement.executeUpdate();
                } catch (SQLException e) {
                    LOGGER.error("Failed to execute SQL statement {}", SQL, e);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to connect to MySQL server!", e);
            }
        });

        WriteTable.PLAYER_SESSION_INFO(serverPlayer, level, true);
    }

    @SubscribeEvent
    public static void PLAYER_LOGOUT_INFO(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        Level level = player.level();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        WriteTable.PLAYER_SESSION_INFO(serverPlayer, level, false);
    }
}
