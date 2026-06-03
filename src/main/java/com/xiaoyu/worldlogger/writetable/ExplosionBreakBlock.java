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

/**
 * 爆炸破坏方块记录器。
 *
 * <p>一次爆炸可能影响很多方块，所以这张表不是“一块方块一行”，
 * 而是把本次爆炸影响的所有方块打包成 JSON 写入 block_data。</p>
 */
public class ExplosionBreakBlock {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 爆炸计算完成时触发。
     *
     * @param event 爆炸引爆事件，包含受影响方块列表。
     */
    @SubscribeEvent
    public static void EXPLOSION_BREAK_BLOCK(ExplosionEvent.Detonate event) {
        // 取得爆炸所在世界、直接来源实体、影响方块列表和间接来源实体。
        Level level = event.getLevel();
        Entity sourceEntity = event.getExplosion().getDirectSourceEntity();
        List<BlockPos> blockPosList = event.getAffectedBlocks();
        Entity indirect = event.getExplosion().getIndirectSourceEntity();

        // 这些变量最终写入数据库：爆炸来源、位置、世界、触发来源和方块列表。
        String entityName;
        String entityPos;
        String entityWorld;
        String trigger;
        List<Map<String, Object>> blocks = new ArrayList<>();

        // 有直接来源实体时使用实体位置；没有时用第一个受影响方块位置兜底。
        if (sourceEntity != null) {
            entityName = StringData.getEntityName(sourceEntity);
            entityPos = StringData.getPos(sourceEntity);
        } else {
            entityName = "UNKNOWN_SOURCE";
            entityPos = blockPosList.isEmpty() ? null : StringData.getPos(blockPosList.getFirst());
        }
        entityWorld = StringData.getLevelName(level);

        // 遍历所有被爆炸影响的方块，保存方块 ID、坐标和 NBT。
        for (BlockPos blockPos : blockPosList) {
            Map<String, Object> block = new HashMap<>();
            Map<String, Object> blockData = new HashMap<>();

            // 注意：Detonate 时方块状态可能已经接近爆炸处理阶段，具体状态取决于事件调用时机。
            BlockState blockState = level.getBlockState(blockPos);
            BlockInfoData data = new BlockInfoData(blockState, blockPos, level);

            blockData.put("pos", data.blockPos);
            blockData.put("nbt", data.blockNBT);
            block.put(data.blockID, blockData);
            blocks.add(block);
        }

        // indirect 表示间接触发者，例如玩家点燃 TNT 时，玩家可能是 indirect。
        if (indirect != null) {
            if (indirect instanceof Player player) {
                trigger = StringData.getEntityName(player);
            } else {
                trigger = StringData.getEntityName(indirect);
            }
        } else {
            trigger = "system";
        }

        // block_data 使用 TEXT 存 JSON，trigger_source 保存真正触发来源。
        String SQL = """
                     INSERT INTO EXPLOSION_BREAK_BLOCK(
                         source_name,
                         source_pos,
                         world,
                         block_data,
                         trigger_source
                     ) VALUES (?, ?, ?, ?, ?)
                     """;

        // Gson 负责把 blocks 列表转成 JSON 字符串。
        Gson gson = new Gson();

        // 异步写入爆炸记录。
        MySQLExecutorService.execute(LOGGER, "write explosion block break info", () -> {
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
