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

/**
 * 实体破坏方块记录器。
 *
 * <p>这里记录 LivingEntityDestroyBlockEvent，例如生物或模组实体破坏方块。
 * 玩家普通挖掘由 PlayerBreakInfo 记录。</p>
 */
public class EntityBreakInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 生物实体破坏方块时触发。
     *
     * @param event 生物破坏方块事件。
     */
    @SubscribeEvent
    public static void ENTITY_BREAK_INFO(LivingDestroyBlockEvent event) {
        // 事件提供破坏方块的实体、世界、方块状态和坐标。
        LivingEntity entity = event.getEntity();
        Level level = entity.level();
        BlockState blockState = event.getState();
        BlockPos blockPos = event.getPos();

        // 方块快照。
        BlockInfoData blockData = new BlockInfoData(blockState, blockPos, level);

        // 实体快照。
        String name = StringData.getEntityName(entity);
        String pos = StringData.getPos(entity);
        String world = StringData.getLevelName(level);

        // 记录实体名称、实体坐标、世界、方块 ID、方块 NBT、方块坐标。
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

        // 异步写库。
        MySQLExecutorService.execute(LOGGER, "write entity block break info", () -> {
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
