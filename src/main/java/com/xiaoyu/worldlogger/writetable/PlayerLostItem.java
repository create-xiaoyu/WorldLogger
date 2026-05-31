package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.ItemDataUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PlayerLostItem {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void PLAYER_LOST_ITEN(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);

        String SQL = """
                     INSERT INTO PLAYER_LOST_ITEM(
                         player_uuid,
                         player_name,
                         lost_type,
                         pos,
                         world,
                         lost_item
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """;

        Gson gson = new Gson();
        String lostItem = gson.toJson(ItemDataUtils.getItemData(event.getEntity().getItem()));

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, "normal");
                    statement.setString(4, data.pos);
                    statement.setString(5, data.world);
                    statement.setString(6, lostItem);

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
