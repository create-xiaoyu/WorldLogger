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

/**
 * 玩家聊天记录器。
 *
 * <p>服务器聊天事件中同时有原始文本和 Component 消息。
 * 原始文本方便搜索，人类更易读；Component 字符串能保留更多 Minecraft 聊天结构信息。</p>
 */
public class ServerChatInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 玩家聊天时触发。
     *
     * @param event 服务器聊天事件。
     */
    @SubscribeEvent
    public static void SERVER_CHAT_INFO(ServerChatEvent event) {
        // ServerChatEvent 已经是服务端聊天事件，可以直接取得 ServerPlayer。
        ServerPlayer player = event.getPlayer();
        Level level = player.level();

        // 捕获玩家信息和两种消息文本。
        PlayerSessionData data = new PlayerSessionData(player, level);
        String rawMessage = event.getRawText();
        String componentMessage = event.getMessage().toString();

        // raw_message 和 component_message 使用 TEXT，避免长消息被 VARCHAR 截断。
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

        // 异步写入聊天记录。
        MySQLExecutorService.execute(LOGGER, "write server chat info", () -> {
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
