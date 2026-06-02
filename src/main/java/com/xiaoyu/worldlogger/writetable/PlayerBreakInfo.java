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

public class PlayerBreakInfo {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void PLAYER_BREAK_INFO(BreakBlockEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        Level level = player.level();

        BlockState blockState = event.getState();
        BlockPos blockPos = event.getPos();

        PlayerSessionData data = new PlayerSessionData(player, level);
        BlockInfoData blockData = new BlockInfoData(blockState, blockPos, level);

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

        MySQLExecutorService.getExecutor().execute(() -> {
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
