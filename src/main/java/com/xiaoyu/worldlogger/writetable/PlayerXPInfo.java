package com.xiaoyu.worldlogger.writetable;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.event.PlayerInteractEvent.RightClickBlock;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.HashUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 玩家普通经验变化记录器。
 *
 * <p>这里记录非死亡场景的经验增加和减少，例如捡经验球、使用铁砧、附魔台等。
 * 死亡掉落经验由 PlayerDeathInfo 中的 LivingExperienceDropEvent 记录，避免重复。</p>
 */
public class PlayerXPInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 玩家经验值变化时触发。
     *
     * @param event NeoForge 玩家经验变化事件。
     */
    @SubscribeEvent
    public static void PLAYER_XP_INFO(PlayerXpEvent.XpChange event) {
        // 只处理服务端玩家。
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // 玩家死亡时的经验掉落使用 LivingExperienceDropEvent 记录，这里跳过避免重复或记录不准。
        if (player.isDeadOrDying()) return;

        Level level = player.level();

        // 捕获玩家当前快照，供异步写库使用。
        PlayerSessionData data = new PlayerSessionData(player, level);

        // event.getAmount() 是这次变化量；player.totalExperience 是变化后的玩家总经验。
        long xpCount = event.getAmount();
        long playerXPCount = player.totalExperience;

        String changeType;

        // 正数表示增加经验，负数表示减少经验。
        if (event.getAmount() > 0) {
            changeType = "added";
        } else {
            changeType = "reduce";
        }

        // 通过最近右键方块推测经验变化来源，例如铁砧、附魔台、熔炉。
        BlockState block = RightClickBlock.getRightClickBlocks(HashUtils.sha1(data.uuid + data.name));
        String changeSource = getChangeSource(changeType, block);

        // death_id 不写是正常的，因为普通经验变化不属于一次死亡事件。
        String SQL = """
                     INSERT INTO PLAYER_XP_INFO(
                         player_uuid,
                         player_name,
                         xp_change_type,
                         xp_change_source,
                         xp_change_count,
                         pos,
                         world,
                         xp_count
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """;

        // 异步写库，避免经验变化事件频繁触发时卡主线程。
        MySQLExecutorService.execute(LOGGER, "write player xp change", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, changeType);
                    statement.setString(4, changeSource);
                    statement.setLong(5, xpCount);
                    statement.setString(6, data.pos);
                    statement.setString(7, data.world);
                    statement.setLong(8, playerXPCount);

                    statement.executeUpdate();
                } catch (SQLException e) {
                    LOGGER.error("Failed to execute SQL statement {}", SQL, e);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to connect to MySQL server!", e);
            }
        });
    }

    /**
     * 根据变化方向和最近右键方块推测经验来源。
     *
     * @param changeType added 或 reduce。
     * @param block 最近右键的方块状态，可能为 null。
     * @return 来源文本，会写入 xp_change_source。
     */
    private static String getChangeSource(String changeType, BlockState block) {
        // 没有右键方块上下文时，只能认为是普通变化，例如捡经验球。
        if (block == null) {
            return "normal";
        }

        // 方块注册 ID 比方块显示名更适合长期保存和判断。
        String blockId = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
        if (changeType.equals("added")) {
            // 增加经验时，砂轮和熔炉类方块是比较典型的来源。
            return switch (blockId) {
                case "minecraft:grindstone" -> "Use Grindstone";
                case "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" -> "Smelting Item";
                default -> "normal";
            };
        }

        // 减少经验时，铁砧和附魔台是比较典型的来源。
        return switch (blockId) {
            case "minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil" -> "Use Anvil";
            case "minecraft:enchanting_table" -> "Use Enchanting Table";
            default -> "Use Block";
        };
    }
}
