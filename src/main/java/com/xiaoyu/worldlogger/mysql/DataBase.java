package com.xiaoyu.worldlogger.mysql;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DataBase {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void InitDataBaseTable(Connection mysqlConnection) {
        try (Statement statement = mysqlConnection.createStatement()) {
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_BASE_INFO(
                            player_uuid VARCHAR(36) PRIMARY KEY,
                            player_name VARCHAR(16) NOT NULL,
                            player_game_time BIGINT
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_LOGIN_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            player_IP VARCHAR(32),
                            player_login_time DATETIME,
                            player_login_pos VARCHAR(64),
                            player_login_world VARCHAR(64)
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_LOGOUT_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            player_logout_time DATETIME,
                            player_logout_pos VARCHAR(64),
                            player_logout_world VARCHAR(64)
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_DEATH_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            player_death_time DATETIME,
                            player_death_pos VARCHAR(64),
                            player_death_world VARCHAR(64),
                            player_death_source TEXT,
                            player_death_lost_xp DOUBLE,
                            player_death_lost_item TEXT
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_XP_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            player_xp_add_time DATETIME,
                            player_xp_add_count DOUBLE,
                            player_xp_add_pos VARCHAR(64),
                            player_xp_add_world VARCHAR(64),
                            player_xp_count DOUBLE
                        )
                        """
            );
        } catch (SQLException e) {
            LOGGER.error("Error while initializing DataBase!", e);
        }
    }
}
