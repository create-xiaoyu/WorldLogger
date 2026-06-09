package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.event.CommandEvent.ExecuteCommands;
import com.xiaoyu.worldlogger.event.PlayerInteractEvent.RightClickBlock;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.mysql.WriteTable;
import com.xiaoyu.worldlogger.utils.HashUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 玩家基础信息、登录信息和退出信息记录器。
 *
 * <p>登录时会更新 PLAYER_BASE_INFO，并额外写入 PLAYER_LOGIN_INFO。
 * 退出时会写入 PLAYER_LOGOUT_INFO，并清理玩家相关的临时缓存。</p>
 */
public class PlayerInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 玩家登录时触发。
     *
     * @param event NeoForge 玩家登录事件。
     */
    @SubscribeEvent
    public static void PLAYER_BASE_LOGIN_INFO(PlayerEvent.PlayerLoggedInEvent event) {
        // 只处理服务端玩家，避免客户端侧重复记录。
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        // 先把玩家数据转换为普通快照，后面异步线程只使用这些字段。
        PlayerSessionData data = new PlayerSessionData(player, level);

        // ON DUPLICATE KEY UPDATE 表示：UUID 已存在则更新名字和游玩时间，不存在则插入新行。
        String SQL = """
                     INSERT INTO PLAYER_BASE_INFO(player_uuid, player_name, player_game_time) VALUES (?, ?, ?)
                     ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), player_game_time = VALUES(player_game_time)
                     """;
        // 提交到 MySQL 线程池，避免登录流程被数据库阻塞。
        MySQLExecutorService.execute(LOGGER, "upsert player base info", () -> {
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

        // 记录本次登录的明细，包括坐标、世界和 IP。
        WriteTable.PLAYER_SESSION_INFO(player, level, true);
    }

    /**
     * 玩家退出时触发。
     *
     * @param event NeoForge 玩家退出事件。
     */
    @SubscribeEvent
    public static void PLAYER_LOGOUT_INFO(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        // 记录退出时的位置和世界。
        WriteTable.PLAYER_SESSION_INFO(player, level, false);

        // 退出时清理临时缓存，避免玩家下次登录时读到上次的右键方块或死亡时间。
        String playerHash = HashUtils.sha1(player.getStringUUID() + player.getName().getString());
        RightClickBlock.clear(playerHash);
        PlayerDeathInfo.clear(playerHash);
        ExecuteCommands.clear(playerHash);
    }
}
