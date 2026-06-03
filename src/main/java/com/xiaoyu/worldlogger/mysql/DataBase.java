package com.xiaoyu.worldlogger.mysql;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库表结构初始化类。
 *
 * <p>服务器启动时会调用 InitDataBaseTable。每个 CREATE TABLE 都带有 IF NOT EXISTS，
 * 所以表已经存在时不会清空旧数据，也不会重复创建。</p>
 *
 * <p>注意：这个类只负责“创建缺失的表”，不负责后续表结构迁移。
 * 如果将来要新增字段，最好额外写 ALTER TABLE 检查逻辑。</p>
 */
public class DataBase {
    /** 日志对象，用于记录建表失败的 SQLException。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 创建 WorldLogger 需要的所有数据库表。
     *
     * @param mysqlConnection 已经从 HikariCP 连接池中取得的 MySQL 连接。
     *
     * 用法：服务器启动后调用一次。返回值为空，因为失败会通过日志输出。
     */
    public static void InitDataBaseTable(Connection mysqlConnection) {
        // Statement 适合执行固定 SQL。try-with-resources 会自动关闭它。
        try (Statement statement = mysqlConnection.createStatement()) {
            // PLAYER_BASE_INFO：玩家基础表。每个 UUID 只有一行，用于保存玩家名字和总游玩时间。
            statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS PLAYER_BASE_INFO(
                        player_uuid VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(16) NOT NULL,
                        player_game_time BIGINT
                    )
                    """
            );

            // PLAYER_LOGIN_INFO：玩家登录记录。每次登录都会新增一行，包含 IP、坐标和世界。
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

            // PLAYER_LOGOUT_INFO：玩家退出记录。每次退出都会新增一行，方便和登录记录配对查看。
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

            // PLAYER_DEATH_INFO：玩家死亡记录。death_id 用来和死亡掉落物、死亡经验记录关联。
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

            // PLAYER_LOST_ITEM：玩家丢出或死亡掉落的物品记录。lost_item 是 JSON 文本。
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

            // PLAYER_XP_INFO：玩家经验变化记录。死亡掉落经验和普通经验变化都写入这张表。
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

            // EXECUTE_COMMAND_INFO：命令执行记录。source 可以是玩家、命令方块、RCON 或控制台。
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

            // SERVER_CHAT_INFO：聊天记录。raw_message 是纯文本，component_message 是组件字符串。
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

            // PLAYER_CONTAINER_INFO：容器槽位变化记录。source_item 和 modify_item 是变化前后的物品 JSON。
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_CONTAINER_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(16) NOT NULL,
                            player_pos VARCHAR(64),
                            world VARCHAR(64),
                            container_id VARCHAR(64),
                            container_pos VARCHAR(64),
                            slot_index INT,
                            source_item TEXT,
                            modify_item TEXT,
                            modify_type VARCHAR(64)
                        )
                        """
            );

            // ENTITY_PLACE_BLOCK：任意实体放置方块记录。玩家放方块也会归为实体放置。
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS ENTITY_PLACE_BLOCK(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            entity_name VARCHAR(255) NOT NULL,
                            entity_pos VARCHAR(64),
                            world VARCHAR(64),
                            block_id VARCHAR(64),
                            block_nbt TEXT,
                            block_pos VARCHAR(64)
                        )
                        """
            );

            // PLAYER_BREAK_INFO：玩家破坏方块记录。block_nbt 保存方块实体数据，例如箱子内容。
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS PLAYER_BREAK_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            player_uuid VARCHAR(36) NOT NULL,
                            player_name VARCHAR(255) NOT NULL,
                            player_pos VARCHAR(64),
                            world VARCHAR(64),
                            block_id VARCHAR(64),
                            block_nbt TEXT,
                            block_pos VARCHAR(64)
                        )
                        """
            );

            // ENTITY_BREAK_INFO：非玩家实体破坏方块记录，例如生物破坏门、末影人搬方块等。
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS ENTITY_BREAK_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            entity_name VARCHAR(255) NOT NULL,
                            entity_pos VARCHAR(64),
                            world VARCHAR(64),
                            block_id VARCHAR(64),
                            block_nbt TEXT,
                            block_pos VARCHAR(64)
                        )
                        """
            );

            // EXPLOSION_BREAK_BLOCK：爆炸破坏方块记录。block_data 是被影响方块列表的 JSON。
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS EXPLOSION_BREAK_BLOCK(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            source_name VARCHAR(255) NOT NULL,
                            source_pos VARCHAR(64),
                            world VARCHAR(64),
                            block_data TEXT,
                            trigger_source VARCHAR(64)
                        )
                        """
            );

            // ENTITY_DEATH_INFO：非玩家实体死亡记录。玩家死亡由 PLAYER_DEATH_INFO 负责记录。
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS ENTITY_DEATH_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            entity_name VARCHAR(255) NOT NULL,
                            entity_pos VARCHAR(64),
                            entity_world VARCHAR(64),
                            source_name VARCHAR(255),
                            source_pos VARCHAR(64),
                            source_world VARCHAR(64)
                        )
                        """
            );

            // ENTITY_SPAWN_INFO：非玩家实体生成记录。玩家进入世界由 PLAYER_LOGIN_INFO 负责记录。
            statement.execute(
                    """
                        CREATE TABLE IF NOT EXISTS ENTITY_SPAWN_INFO(
                            data_id INT AUTO_INCREMENT PRIMARY KEY,
                            time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            entity_name VARCHAR(255) NOT NULL,
                            entity_pos VARCHAR(64),
                            world VARCHAR(64)
                        )
                        """
            );
        } catch (SQLException e) {
            // 任何一条建表 SQL 失败都会进入这里，日志中会带完整异常栈。
            LOGGER.error("Error while initializing DataBase!", e);
        }
    }
}
