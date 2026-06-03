package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 非玩家实体生成记录器。
 *
 * <p>玩家进入世界由 PlayerInfo 记录，这里只记录普通实体生成。</p>
 */
public class EntitySpawnInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 实体加入世界时触发。
     *
     * @param event 实体加入世界事件。
     */
    @SubscribeEvent
    public static void ENTITY_SPAWN_INFO(EntityJoinLevelEvent event) {
        // 玩家加入世界跳过，避免和登录记录重复。
        if (event.getEntity() instanceof ServerPlayer player) return;

        // 读取实体和世界。
        Entity entity = event.getEntity();
        Level level = event.getLevel();

        // 提取实体名称、坐标、世界。
        String name = StringData.getEntityName(entity);
        String pos = StringData.getPos(entity);
        String world = StringData.getLevelName(level);

        // 写入实体生成表。
        String SQL = """
                     INSERT INTO ENTITY_SPAWN_INFO(
                         entity_name,
                         entity_pos,
                         world
                     ) VALUES (?, ?, ?)
                     """;

        // 异步写库，避免实体大量生成时卡主线程。
        MySQLExecutorService.execute(LOGGER, "write entity spawn info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, name);
                    statement.setString(2, pos);
                    statement.setString(3, world);

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
