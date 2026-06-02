package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.BlockInfoData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

public class ExplosionBreakBlock {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void EXPLOSION_BREAK_BLOCK(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        Entity sourceEntity = event.getExplosion().getDirectSourceEntity();
        List<BlockPos> blockPosList = event.getAffectedBlocks();
        Entity indirect = event.getExplosion().getIndirectSourceEntity();

        String entityName;
        String entityPos;
        String entityWorld;
        String trigger;
        List<Map<String, Object>> blocks = new ArrayList<>();

        if (sourceEntity != null) {
            entityName = StringData.getEntityName(sourceEntity);
            entityPos = StringData.getPos(sourceEntity);
            entityWorld = StringData.getLevelName(level);
        } else {
            entityName = "UNKNOWN_SOURCE";
            entityPos = null;
            entityWorld = null;
        }

        for (BlockPos blockPos : blockPosList) {
            Map<String, Object> block = new HashMap<>();
            Map<String, Object> blockData = new HashMap<>();

            BlockState blockState = level.getBlockState(blockPos);
            BlockInfoData data = new BlockInfoData(blockState, blockPos, level);

            blockData.put("pos", data.blockPos);
            blockData.put("nbt", data.blockNBT);
            block.put(data.blockID, blockData);
            blocks.add(block);
        }

        if (indirect != null) {
            if (indirect instanceof Player player) {
                trigger = StringData.getEntityName(player);
            } else {
                trigger = StringData.getEntityName(indirect);
            }
        } else {
            trigger = "system";
        }

        String SQL = """
                     INSERT INTO EXPLOSION_BREAK_BLOCK(
                         source_name,
                         source_pos,
                         world,
                         block_data,
                         trigger_source
                     ) VALUES (?, ?, ?, ?, ?)
                     """;

        Gson gson = new Gson();

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, entityName);
                    statement.setString(2, entityPos);
                    statement.setString(3, entityWorld);
                    statement.setString(4, gson.toJson(blocks));
                    statement.setString(5, trigger);

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
