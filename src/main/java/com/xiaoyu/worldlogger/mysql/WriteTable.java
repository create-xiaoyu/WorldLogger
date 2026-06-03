package com.xiaoyu.worldlogger.mysql;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 通用写表方法集合。
 *
 * <p>目前主要负责登录和退出记录，因为它们的字段非常相似，可以用一个方法根据 login 参数选择写入哪张表。</p>
 */
public class WriteTable {
    /** 日志对象，用于输出 SQL 执行失败信息。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 写入玩家登录或退出信息。
     *
     * @param player 触发登录/退出事件的服务端玩家。
     * @param level 玩家所在世界。
     * @param login true 表示登录，写 PLAYER_LOGIN_INFO；false 表示退出，写 PLAYER_LOGOUT_INFO。
     */
    public static void PLAYER_SESSION_INFO(ServerPlayer player, Level level, boolean login) {
        // 先把玩家运行时对象转成普通数据，避免异步线程里继续读取 Minecraft 对象。
        PlayerSessionData data = new PlayerSessionData(player, level);

        String SQL;

        // 登录表多一个 IP 字段，所以登录和退出使用两段 SQL。
        if (login) {
            SQL = """
                  INSERT INTO PLAYER_LOGIN_INFO(
                      player_uuid,
                      player_name,
                      pos,
                      world,
                      IP
                  ) VALUES (?, ?, ?, ?, ?)
                  """;
        } else {
            SQL = """
                  INSERT INTO PLAYER_LOGOUT_INFO(
                      player_uuid,
                      player_name,
                      pos,
                      world
                  ) VALUES (?, ?, ?, ?)
                  """;
        }

        // 把数据库写入交给线程池，避免登录/退出时因为 MySQL 卡顿而影响服务器。
        MySQLExecutorService.execute(LOGGER, "write player session info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                // PreparedStatement 用 ? 占位，能避免手动拼接字符串导致 SQL 注入或转义错误。
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, data.pos);
                    statement.setString(4, data.world);

                    // 只有登录 SQL 有第 5 个参数 IP；退出 SQL 没有这个字段。
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
