package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerDeathData;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
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

    @SubscribeEvent
    public static void PLAYER_DEATH_INFO(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = player.level();
        DamageSource source = event.getSource();

        PlayerSessionData playerData = new PlayerSessionData(player, level);
        PlayerDeathData deathData = new PlayerDeathData(source, player);

        String deathType = source.typeHolder().getRegisteredName();

        String SQL = """
                     INSERT INTO PLAYER_DEATH_INFO(
                         player_uuid,
                         player_name,
                         player_death_type,
                         player_death_pos,
                         player_death_world,
                         player_death_source_name,
                         player_death_source_pos,
                         player_death_source_world,
                         player_death_source_item,
                         player_death_message
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """;

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, playerData.uuid);
                    statement.setString(2, playerData.name);
                    statement.setString(3, deathType);
                    statement.setString(4, playerData.pos);
                    statement.setString(5, playerData.world);
                    statement.setString(6, deathData.sourceName);
                    statement.setString(7, deathData.sourcePos);
                    statement.setString(8, deathData.sourceWorld);
                    statement.setString(9, deathData.sourceItem);
                    statement.setString(10, deathData.deathMessage);

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

        String SQL = """
                 INSERT INTO PLAYER_LOST_ITEM(
                     player_uuid,
                     player_name,
                     player_lost_type,
                     player_lost_pos,
                     player_lost_world,
                     player_lost_item
                 ) VALUES (?, ?, ?, ?, ?, ?, ?)
                 """;

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    statement.setString(1, data.uuid);
                    statement.setString(2, data.name);
                    statement.setString(3, "Death");
                    statement.setString(4, data.pos);
                    statement.setString(5, data.world);
                    statement.setString(6, items);

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

        String SQL = """
                     INSERT INTO PLAYER_XP_INFO(
                         player_uuid,
                         player_name,
                         player_xp_change_type,
                         player_xp_change_count,
                         player_xp_change_pos,
                         player_xp_change_world,
                         player_xp_count
                     ) VALUES (?, ?, ?, ?, ?, ?, ?)
                     """;

        MySQLExecutorService.getExecutor().execute(() -> {
           try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
               try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                   statement.setString(1, data.uuid);
                   statement.setString(2, data.name);
                   statement.setString(3, "Death");
                   statement.setInt(4, XP);
                   statement.setString(5, data.pos);
                   statement.setString(6, data.world);
                   statement.setInt(7, XPCount);

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
