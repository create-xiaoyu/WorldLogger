package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.BlockInfoData;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 玩家破坏方块记录器。
 *
 * <p>当玩家破坏方块时，将玩家信息、方块 ID、方块 NBT 和方块坐标写入 PLAYER_BREAK_INFO。</p>
 */
public class PlayerBreakInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 玩家破坏方块时触发。
     *
     * @param event NeoForge 方块破坏事件。
     */
    @SubscribeEvent
    public static void PLAYER_BREAK_INFO(BreakBlockEvent event) {
        // 只记录服务端玩家破坏方块。
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        Level level = player.level();

        // 事件提供被破坏方块的状态和坐标。
        BlockState blockState = event.getState();
        BlockPos blockPos = event.getPos();

        // 玩家数据和方块数据都先做成快照，供异步线程使用。
        PlayerSessionData data = new PlayerSessionData(player, level);
        BlockInfoData blockData = new BlockInfoData(blockState, blockPos, level);

        // block_nbt 可能为 null，普通方块没有方块实体数据。
        String SQL = """
                     INSERT INTO PLAYER_BREAK_INFO(
                         player_uuid,
                         player_name,
                         player_pos,
                         world,
                         block_id,
                         block_nbt,
                         block_pos
                     ) VALUES (?, ?, ?, ?, ?, ?, ?)
                     """;

        // 异步写入破坏记录。
        MySQLExecutorService.execute(LOGGER, "write player block break info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, data.pos);
                    statement.setString(4, data.world);
                    statement.setString(5, blockData.blockID);
                    statement.setString(6, blockData.blockNBT);
                    statement.setString(7, blockData.blockPos);

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
