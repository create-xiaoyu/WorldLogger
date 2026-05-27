package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerDeathData;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.Hash;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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

public class PlayerDeathInfo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static long timeStamp;

    @SubscribeEvent
    public static void PLAYER_DEATH_INFO(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        timeStamp = System.currentTimeMillis();

        Level level = player.level();
        DamageSource source = event.getSource();

        PlayerSessionData playerData = new PlayerSessionData(player, level);
        PlayerDeathData deathData = new PlayerDeathData(source, player);

        String deathType = source.typeHolder().getRegisteredName();

        String seed = playerData.uuid + playerData.name + timeStamp;
        String death_id = Hash.sha1(seed);

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

        MySQLExecutorService.getExecutor().execute(() -> {
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

    @SubscribeEvent
    public static void PLAYER_LOST_ITEM(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);

        List<Map<String, Object>> lostItems = new ArrayList<>();

        for (ItemEntity drop : event.getDrops()) {
            Map<String, Object> item = new HashMap<>();
            ItemStack stack = drop.getItem();

            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);

            item.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            item.put("count", stack.getCount());

            if (customData != null) {
                CompoundTag tag = customData.copyTag();

                item.put("custom_data", tag.toString());
            } else {
                item.put("tag", null);
            }

            Map<String, Map<String, Object>> enchantments = new HashMap<>();

            stack.getTagEnchantments().entrySet().forEach(entry -> {
                String regId = entry.getKey().getRegisteredName();

                Map<String, Object> enchantmentInfo = new HashMap<>();
                enchantmentInfo.put("name", entry.getKey().value().description().getString());
                enchantmentInfo.put("level", entry.getIntValue());

                enchantments.put(regId, enchantmentInfo);
                item.put("enchantments", enchantments);
            });

            lostItems.add(item);
        }

        Gson gson = new Gson();
        String items = gson.toJson(lostItems);

        String seed = data.uuid + data.name + timeStamp;
        String death_id = Hash.sha1(seed);

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

        MySQLExecutorService.getExecutor().execute(() -> {
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

    @SubscribeEvent
    public static void PLAYER_XP_INFO(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();

        PlayerSessionData data = new PlayerSessionData(player, level);

        int XP = event.getDroppedExperience();
        int XPCount = player.totalExperience;

        String seed = data.uuid + data.name + timeStamp;
        String death_id = Hash.sha1(seed);

        String SQL = """
                     INSERT INTO PLAYER_XP_INFO(
                         death_id,
                         player_uuid,
                         player_name,
                         xp_change_type,
                         xp_change_count,
                         pos,
                         world,
                         xp_count
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """;

        MySQLExecutorService.getExecutor().execute(() -> {
           try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
               try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                   statement.setString(1, death_id);
                   statement.setString(2, data.uuid);
                   statement.setString(3, data.name);
                   statement.setString(4, "Death");
                   statement.setInt(5, XP);
                   statement.setString(6, data.pos);
                   statement.setString(7, data.world);
                   statement.setInt(8, XPCount);

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
