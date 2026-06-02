package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.BlockInfoData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class EntityBreakInfo {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void ENTITY_BREAK_INFO(LivingDestroyBlockEvent event) {
        LivingEntity entity = event.getEntity();
        Level level = entity.level();
        BlockState blockState = event.getState();
        BlockPos blockPos = event.getPos();

        BlockInfoData blockData = new BlockInfoData(blockState, blockPos, level);

        String name = StringData.getEntityName(entity);
        String pos = StringData.getPos(entity);
        String world = StringData.getLevelName(level);

        String SQL = """
                     INSERT INTO ENTITY_BREAK_INFO(
                         entity_name,
                         entity_pos,
                         world,
                         block_id,
                         block_nbt,
                         block_pos
                     ) VALUES (?, ?, ?, ?, ?, ?);
                     """;

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, name);
                    statement.setString(2, pos);
                    statement.setString(3, world);
                    statement.setString(4, blockData.blockID);
                    statement.setString(5, blockData.blockNBT);
                    statement.setString(6, blockData.blockPos);

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
