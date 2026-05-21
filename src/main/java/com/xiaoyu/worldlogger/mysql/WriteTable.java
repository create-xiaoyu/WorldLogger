package com.xiaoyu.worldlogger.mysql;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class WriteTable {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void PLAYER_SESSION_INFO(ServerPlayer player, Level level, boolean login) {
        PlayerSessionData data = new PlayerSessionData(player, level);

        String SQL;

        if (login) {
            SQL = """
                    INSERT INTO PLAYER_LOGIN_INFO(
                        player_uuid,
                        player_name,
                        player_login_pos,
                        player_login_world,
                        player_IP
                    ) VALUES (?, ?, ?, ?, ?)
                    """;
        } else {
            SQL = """
                     INSERT INTO PLAYER_LOGOUT_INFO(
                         player_uuid,
                         player_name,
                         player_logout_pos,
                         player_logout_world
                     ) VALUES (?, ?, ?, ?)
                     """;
        }

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, data.pos);
                    statement.setString(4, data.world);

                    if (login) {
                        statement.setString(5, data.ip);
                    }

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
