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
                            player_login_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
                            player_logout_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
                            player_death_type VARCHAR(64) NOT NULL,
                            player_death_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_death_pos VARCHAR(64),
                            player_death_world VARCHAR(64),
                            player_death_source_name VARCHAR(255),
                            player_death_source_pos VARCHAR(64),
                            player_death_source_world VARCHAR(64),
                            player_death_source_item VARCHAR(64),
                            player_death_message VARCHAR(64)
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_LOST_ITEM(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            player_lost_type VARCHAR(64) NOT NULL,
                            player_lost_item_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_lost_pos VARCHAR(64),
                            player_lost_world VARCHAR(64),
                            player_lost_item TEXT
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_XP_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            player_xp_change_type VARCHAR(64)NOT NULL,
                            player_xp_change_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_xp_change_count INT,
                            player_xp_change_pos VARCHAR(64),
                            player_xp_change_world VARCHAR(64),
                            player_xp_count INT
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS EXECUTE_COMMAND_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            execute_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            execute_source VARCHAR(255) NOT NULL,
                            execute_pos VARCHAR(64),
                            execute_world VARCHAR(64),
                            execute_command TEXT
                        )
                        """
            );
        } catch (SQLException e) {
            LOGGER.error("Error while initializing DataBase!", e);
        }
    }
}
