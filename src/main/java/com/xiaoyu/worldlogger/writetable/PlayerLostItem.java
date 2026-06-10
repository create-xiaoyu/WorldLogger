package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.event.CommandEvent.ExecuteCommands;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.HashUtils;
import com.xiaoyu.worldlogger.utils.ItemDataUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 玩家主动丢出物品记录器。
 *
 * <p>玩家按 Q 或拖出物品时会触发 ItemTossEvent。
 * 玩家死亡掉落物品由 PlayerDeathInfo.PLAYER_LOST_ITEM 记录，这里记录的是普通丢弃。</p>
 */
public class PlayerLostItem {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 玩家丢出物品时触发。
     *
     * @param event 物品丢出事件。
     */
    @SubscribeEvent
    public static void PLAYER_LOST_ITEN(ItemTossEvent event) {
        // 只记录服务端玩家丢物，避免客户端重复。
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        Level level = player.level();

        // 捕获玩家快照。
        PlayerSessionData data = new PlayerSessionData(player, level);

        // 玩家哈希值
        String playerHash = HashUtils.sha1(data.uuid + data.name);
        String command = ExecuteCommands.getExecuteCommand(playerHash);

        // 不记录 give 命令的触发
        if (command != null) {
            if (command.equals("give")) {
                ExecuteCommands.clear(playerHash);
                return;
            }
        }

        // 及时清理，以确保之后的丢弃物品逻辑不会被跳过
        ExecuteCommands.clear(playerHash);

        // 普通丢物没有 death_id，所以只写 lost_type、位置、世界和物品 JSON。
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

        // 把 ItemStack 转成 JSON，方便保存数量、ID、名称、附魔和 custom_data。
        Gson gson = new Gson();
        String lostItem = gson.toJson(ItemDataUtils.getItemData(event.getEntity().getItem()));

        // 异步写库。
        MySQLExecutorService.execute(LOGGER, "write tossed item info", () -> {
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
