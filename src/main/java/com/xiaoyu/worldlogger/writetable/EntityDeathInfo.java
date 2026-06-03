package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 非玩家实体死亡记录器。
 *
 * <p>玩家死亡由 PlayerDeathInfo 记录，这里只记录其他实体，例如动物、怪物、模组实体。</p>
 */
public class EntityDeathInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 生物死亡时触发。
     *
     * @param event 生物死亡事件。
     */
    @SubscribeEvent
    public static void ENTITY_DEATH_INFO(LivingDeathEvent event) {
        // 玩家死亡跳过，避免和 PLAYER_DEATH_INFO 重复。
        if (event.getEntity() instanceof ServerPlayer player) return;

        // 读取死亡实体、伤害来源实体和世界。
        Entity entity = event.getEntity();
        Entity source = event.getSource().getEntity();
        Level level = entity.level();

        // 死亡实体信息。
        String entityName = StringData.getEntityName(entity);
        String entityPos = StringData.getPos(entity);
        String entityWorld = StringData.getLevelName(level);

        // 来源实体信息可能不存在，例如环境伤害。
        String sourceName;
        String sourcePos;
        String sourceWorld;

        // 有来源实体时记录来源实体名称、坐标和世界；没有时写 null。
        if (source != null) {
            sourceName = StringData.getEntityName(source);
            sourcePos = StringData.getPos(source);
            sourceWorld = StringData.getLevelName(source.level());
        } else {
            sourceName = null;
            sourcePos = null;
            sourceWorld = null;
        }

        // 实体死亡表保存死者和来源双方的基础信息。
        String SQL = """
                     INSERT INTO ENTITY_DEATH_INFO(
                         entity_name,
                         entity_pos,
                         entity_world,
                         source_name,
                         source_pos,
                         source_world
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """;

        // 异步写入实体死亡记录。
        MySQLExecutorService.execute(LOGGER, "write entity death info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, entityName);
                    statement.setString(2, entityPos);
                    statement.setString(3, entityWorld);
                    statement.setString(4, sourceName);
                    statement.setString(5, sourcePos);
                    statement.setString(6, sourceWorld);

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
