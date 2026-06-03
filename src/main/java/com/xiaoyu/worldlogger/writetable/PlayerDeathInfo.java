package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerDeathData;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.HashUtils;
import com.xiaoyu.worldlogger.utils.ItemDataUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家死亡、死亡掉落物、死亡经验掉落记录器。
 *
 * <p>一次玩家死亡会触发多个事件：LivingDeathEvent 记录死亡本身，
 * LivingDropsEvent 记录掉落物，LivingExperienceDropEvent 记录掉落经验。
 * 这些事件不是同一个事件对象，所以这里用 timeStamp 缓存让它们生成同一个 death_id。</p>
 */
public class PlayerDeathInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 玩家哈希 -> 最近一次死亡时间戳。用于让死亡、掉落物、经验记录共享同一个 death_id。 */
    private static final Map<String, Long> timeStamp = new ConcurrentHashMap<>();

    /**
     * 玩家死亡时触发，写入 PLAYER_DEATH_INFO。
     *
     * @param event 生物死亡事件。玩家也是 LivingEntity，所以需要判断是否为 ServerPlayer。
     */
    @SubscribeEvent
    public static void PLAYER_DEATH_INFO(LivingDeathEvent event) {
        // 只处理玩家死亡；非玩家实体死亡由 EntityDeathInfo 负责。
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        Level level = player.level();
        DamageSource source = event.getSource();

        // 玩家快照和死亡来源快照都在主线程中提取，异步线程只拿普通字符串。
        PlayerSessionData playerData = new PlayerSessionData(player, level);
        PlayerDeathData deathData = new PlayerDeathData(source, player);

        // deathType 是伤害类型注册名，例如 minecraft:fall、minecraft:lava。
        String deathType = source.typeHolder().getRegisteredName();

        // 保存本次死亡时间戳，后续掉落物和经验事件会用它生成相同 death_id。
        timeStamp.put(HashUtils.sha1(playerData.uuid + playerData.name), System.currentTimeMillis());

        // death_id = sha1(UUID + 名字 + 死亡时间戳)，用于关联同一次死亡产生的多张表记录。
        String seed = playerData.uuid + playerData.name + timeStamp.get(HashUtils.sha1(playerData.uuid + playerData.name));
        String death_id = HashUtils.sha1(seed);

        // 死亡表保存玩家、死亡类型、位置、来源实体、来源武器和死亡消息。
        String SQL = """
                     INSERT INTO PLAYER_DEATH_INFO(
                         death_id,
                         player_uuid,
                         player_name,
                         death_type,
                         pos,
                         world,
                         source_name,
                         source_pos,
                         source_world,
                         source_weapon_item,
                         death_message
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """;

        // 异步写入死亡记录。
        MySQLExecutorService.execute(LOGGER, "write player death info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, death_id);
                    statement.setString(2, playerData.uuid);
                    statement.setString(3, playerData.name);
                    statement.setString(4, deathType);
                    statement.setString(5, playerData.pos);
                    statement.setString(6, playerData.world);
                    statement.setString(7, deathData.sourceName);
                    statement.setString(8, deathData.sourcePos);
                    statement.setString(9, deathData.sourceWorld);
                    statement.setString(10, deathData.sourceItem);
                    statement.setString(11, deathData.deathMessage);

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
     * 玩家死亡掉落物生成时触发，写入 PLAYER_LOST_ITEM。
     *
     * @param event 生物掉落物事件。
     */
    @SubscribeEvent
    public static void PLAYER_LOST_ITEM(LivingDropsEvent event) {
        // 只处理玩家死亡掉落。普通主动丢物由 PlayerLostItem 记录。
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);

        // 从死亡事件缓存中取同一次死亡的时间戳。
        Long deathTime = timeStamp.get(HashUtils.sha1(data.uuid + data.name));
        if (deathTime == null) {
            LOGGER.warn("Skipped death item log for {} because no matching death timestamp was recorded.", data.name);
            return;
        }

        // 用 List 保存本次死亡掉落的所有物品，每个物品是一个 Map，最后整体转成 JSON。
        List<Map<String, Object>> lostItems = new ArrayList<>();

        for (ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();

            // 保存物品 ID、数量、名称、附魔、custom_data 等信息。
            lostItems.add(ItemDataUtils.getItemData(stack));
        }

        // Gson 把 Java List/Map 转成 JSON 字符串，写入 lost_item TEXT 字段。
        Gson gson = new Gson();
        String items = gson.toJson(lostItems);

        // 使用和死亡记录相同的 seed，得到相同 death_id。
        String seed = data.uuid + data.name + deathTime;
        String death_id = HashUtils.sha1(seed);

        String SQL = """
                 INSERT INTO PLAYER_LOST_ITEM(
                     death_id,
                     player_uuid,
                     player_name,
                     lost_type,
                     pos,
                     world,
                     lost_item
                 ) VALUES (?, ?, ?, ?, ?, ?, ?)
                 """;

        // 异步写入死亡掉落物记录。
        MySQLExecutorService.execute(LOGGER, "write player lost death items", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, death_id);
                    statement.setString(2, data.uuid);
                    statement.setString(3, data.name);
                    statement.setString(4, "Death");
                    statement.setString(5, data.pos);
                    statement.setString(6, data.world);
                    statement.setString(7, items);

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
     * 玩家死亡经验掉落时触发，写入 PLAYER_XP_INFO。
     *
     * @param event 生物死亡经验掉落事件。
     */
    @SubscribeEvent
    public static void PLAYER_XP_INFO(LivingExperienceDropEvent event) {
        // 只处理玩家死亡经验。普通经验变化由 PlayerXPInfo 记录。
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);

        // 读取死亡事件缓存，保证经验记录能关联到对应死亡记录。
        Long deathTime = timeStamp.get(HashUtils.sha1(data.uuid + data.name));
        if (deathTime == null) {
            LOGGER.warn("Skipped death XP log for {} because no matching death timestamp was recorded.", data.name);
            return;
        }

        // 实际掉落到世界里的经验数量。这个比 PlayerXpEvent 更适合记录死亡经验。
        int XP = event.getDroppedExperience();

        // 玩家当前总经验。死亡时可能已经被游戏规则影响，记录下来方便排查。
        int XPCount = player.totalExperience;

        // 和死亡记录共享 death_id。
        String seed = data.uuid + data.name + deathTime;
        String death_id = HashUtils.sha1(seed);

        String SQL = """
                     INSERT INTO PLAYER_XP_INFO(
                         death_id,
                         player_uuid,
                         player_name,
                         xp_change_type,
                         xp_change_source,
                         xp_change_count,
                         pos,
                         world,
                         xp_count
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """;

        // 异步写入死亡经验变化。
        MySQLExecutorService.execute(LOGGER, "write death xp change", () -> {
           try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
               try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                   statement.setString(1, death_id);
                   statement.setString(2, data.uuid);
                   statement.setString(3, data.name);
                   statement.setString(4, "reduce");
                   statement.setString(5, "Death");
                   statement.setInt(6, XP);
                   statement.setString(7, data.pos);
                   statement.setString(8, data.world);
                   statement.setInt(9, XPCount);

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
     * 清理玩家死亡时间缓存。
     *
     * @param playerHash 玩家哈希 key。
     *
     * 用法：玩家退出时调用，避免缓存长期增长。
     */
    public static void clear(String playerHash) {
        timeStamp.remove(playerHash);
    }
}
