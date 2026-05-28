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
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            IP VARCHAR(32),
                            pos VARCHAR(64),
                            world VARCHAR(64)
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_LOGOUT_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            pos VARCHAR(64),
                            world VARCHAR(64)
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_DEATH_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            death_id VARCHAR(160),
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            death_type VARCHAR(64) NOT NULL,
                            pos VARCHAR(64),
                            world VARCHAR(64),
                            source_name VARCHAR(255),
                            source_pos VARCHAR(64),
                            source_world VARCHAR(64),
                            source_weapon_item VARCHAR(64),
                            death_message VARCHAR(64)
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_LOST_ITEM(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            death_id VARCHAR(160),
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            lost_type VARCHAR(64) NOT NULL,
                            pos VARCHAR(64),
                            world VARCHAR(64),
                            lost_item TEXT
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_XP_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            death_id VARCHAR(160),
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            xp_change_type VARCHAR(64) NOT NULL,
                            xp_change_source VARCHAR(64) NOT NULL,
                            xp_change_count BIGINT,
                            pos VARCHAR(64),
                            world VARCHAR(64),
                            xp_count BIGINT
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS EXECUTE_COMMAND_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            source VARCHAR(255) NOT NULL,
                            pos VARCHAR(64),
                            world VARCHAR(64),
                            command TEXT
                        )
                        """
            );
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS SERVER_CHAT_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            pos VARCHAR(64),
                            world VARCHAR(64),
                            raw_message TEXT,
                            component_message TEXT
                        )
                        """
            );
        } catch (SQLException e) {
            LOGGER.error("Error while initializing DataBase!", e);
        }
    }
}
