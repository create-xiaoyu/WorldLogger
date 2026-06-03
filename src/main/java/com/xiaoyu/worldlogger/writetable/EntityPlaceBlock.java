package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.BlockInfoData;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;


/**
 * 实体放置方块记录器。
 *
 * <p>玩家放方块、实体放方块或模组实体放置方块，都可能触发 EntityPlaceEvent。
 * 记录统一写入 ENTITY_PLACE_BLOCK。</p>
 */
public class EntityPlaceBlock {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 实体放置方块时触发。
     *
     * @param event 方块放置事件。
     */
    @SubscribeEvent
    public static void PLAYER_PLACE_INFO(BlockEvent.EntityPlaceEvent event) {
        // 某些情况下事件可能没有实体来源，必须判空。
        Entity entity = event.getEntity();
        if (entity == null) return;

        Level level = entity.level();

        // 默认按普通实体格式保存名称、坐标、世界。
        String entityName = StringData.getEntityName(entity);
        String entityPos = StringData.getPos(event.getEntity());
        String entityWorld = StringData.getLevelName(entity.level());

        // 如果放置方块的是玩家，改用包含 UUID 的玩家格式，方便追踪具体玩家。
        if (entity instanceof ServerPlayer player) {
            PlayerSessionData data = new PlayerSessionData(player, level);

            entityName = StringData.getEntityName(data);
        }

        // 事件中可以取得被放置方块的状态和坐标。
        BlockState blockState = event.getPlacedBlock();
        BlockPos blockPos = event.getPos();

        // 提取方块 ID、坐标、NBT 快照。
        BlockInfoData blockData = new BlockInfoData(blockState, blockPos, level);

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

        // lambda 里引用的局部变量必须是 final 或 effectively final，所以这里另存 finalEntityName。
        String finalEntityName = entityName;

        // 异步写入方块放置记录。
        MySQLExecutorService.execute(LOGGER, "write entity block place info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, finalEntityName);
                    statement.setString(2, entityPos);
                    statement.setString(3, entityWorld);
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
