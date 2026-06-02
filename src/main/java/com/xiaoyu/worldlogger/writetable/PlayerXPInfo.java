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
import java.util.concurrent.atomic.AtomicReference;

public class PlayerXPInfo {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void PLAYER_XP_INFO(PlayerXpEvent.XpChange event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.isDeadOrDying()) return;

        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);

        long xpCount = event.getAmount();
        long playerXPCount = player.totalExperience;

        String changeType;

        if (event.getAmount() > 0) {
            changeType = "added";
        } else {
            changeType = "reduce";
        }

        AtomicReference<String> changeSource = new AtomicReference<>("normal");

        BlockState block = RightClickBlock.getRightClickBlocks(HashUtils.sha1(data.uuid + data.name));
        if (changeType.equals("added")) {
            switch (BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString()) {
                case "minecraft:grindstone":
                    changeSource.set("Use Grindstone");
                    break;
                case "minecraft:furnace":
                case "minecraft:blast_furnace":
                case "minecraft:smoker":
                    changeSource.set("Smelting Item");
                    break;
            }
        } else {
            switch (BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString()) {
                case "minecraft:anvil":
                case "minecraft:chipped_anvil":
                case "minecraft:damaged_anvil":
                    changeSource.set("Use Anvil");
                    break;
                case "minecraft:enchanting_table":
                    changeSource.set("Use Enchanting Table");
                    break;
                default:
                    changeSource.set("Use Block");
            }
        }

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

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, changeType);
                    statement.setString(4, changeSource.get());
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
}
