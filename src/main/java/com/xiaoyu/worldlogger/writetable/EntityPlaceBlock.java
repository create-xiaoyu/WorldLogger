package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;


public class EntityPlaceBlock {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void PLAYER_PLACE_INFO(BlockEvent.EntityPlaceEvent event) {
        Level level = event.getEntity().level();
        Entity entity = event.getEntity();

        String entityName = StringData.getEntityName(entity);
        String entityPos = StringData.getPos(event.getEntity());
        String entityWorld = StringData.getLevelName(entity.level());
        String blockNBT;

        if (entity instanceof ServerPlayer player) {
            PlayerSessionData data = new PlayerSessionData(player, level);

            entityName = StringData.getEntityName(data);
        }

        BlockState blockState = event.getPlacedBlock();
        BlockPos blockPos = event.getPos();
        BlockEntity blockEntity = level.getBlockEntity(blockPos);

        String blockID = BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();

        if (blockEntity != null) {
            blockNBT = blockEntity.saveWithoutMetadata(level.registryAccess()).toString();
        } else {
            blockNBT = "not found";
        }

        String SQL = """
                     INSERT INTO ENTITY_PLACE_BLOCK(
                         entity_name,
                         entity_pos,
                         world,
                         block_id,
                         block_nbt,
                         block_pos
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """;

        String finalEntityName = entityName;

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, finalEntityName);
                    statement.setString(2, entityPos);
                    statement.setString(3, entityWorld);
                    statement.setString(4, blockID);
                    statement.setString(5, blockNBT);
                    statement.setString(6, StringData.getPos(blockPos));

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
