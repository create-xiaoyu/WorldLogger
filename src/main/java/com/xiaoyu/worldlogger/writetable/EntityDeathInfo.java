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

public class EntityDeathInfo {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void ENTITY_DEATH_INFO(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) return;

        Entity entity = event.getEntity();
        Entity source = event.getSource().getEntity();
        Level level = entity.level();

        String entityName = StringData.getEntityName(entity);
        String entityPos = StringData.getPos(entity);
        String entityWorld = StringData.getLevelName(level);

        String sourceName;
        String sourcePos;
        String sourceWorld;

        if (source != null) {
            sourceName = StringData.getEntityName(source);
            sourcePos = StringData.getPos(source);
            sourceWorld = StringData.getLevelName(source.level());
        } else {
            sourceName = null;
            sourcePos = null;
            sourceWorld = null;
        }

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

        MySQLExecutorService.getExecutor().execute(() -> {
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
