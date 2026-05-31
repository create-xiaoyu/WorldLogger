package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.event.PlayerInteractEvent.RightClickBlock;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.HashUtils;
import com.xiaoyu.worldlogger.utils.ItemDataUtils;
import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class PlayerContainerInfo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Object> openedContainerData = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerContainerEventOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AbstractContainerMenu container = event.getContainer();

        if (container == player.inventoryMenu || container instanceof InventoryMenu) return;

        Level level = player.level();

        PlayerSessionData playerData = new PlayerSessionData(player, level);

        String playerHash = HashUtils.sha1(playerData.uuid + playerData.name);
        int slotSize = container.slots.size() - 36;
        BlockState containerBlockID = RightClickBlock.getRightClickBlocks(playerHash);
        BlockPos containerBlockPos = RightClickBlock.getRightClickPos(playerHash);
        String containerWorld = StringData.getLevelName(level);

        openedContainerData.put(playerHash ,ItemDataUtils.getContainerAllData(container, playerData, slotSize));

        Gson gson = new Gson();

        container.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu abstractContainerMenu, int i, ItemStack itemStack) {
                if (i <  slotSize) {
                    JsonObject root = gson.toJsonTree(openedContainerData).getAsJsonObject();
                    JsonObject playerContainerData = root.getAsJsonObject(playerHash);
                    JsonObject containerData = playerContainerData.getAsJsonObject(
                            BuiltInRegistries.BLOCK.getKey(containerBlockID.getBlock()).toString()
                    );
                    JsonObject slotDataRoot = containerData.getAsJsonObject("slotData");
                    JsonObject slotData = slotDataRoot.getAsJsonObject(String.valueOf(i));
                    JsonObject data = slotData.getAsJsonObject("data");

                    String itemName = data.get("item").getAsString();
                    int itemCount = data.get("count").getAsInt();

                    AtomicReference<String> modifyType = new AtomicReference<>("no");

                    if (BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString().equals(itemName) && !(BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString().equals("minecraft:air"))) {
                        if (itemStack.getCount() == itemCount) {
                            if (data.has("name")) {
                                if (!(data.get("name").getAsString().equals(itemStack.getHoverName().getString()))) {
                                    modifyType.set("Modify");
                                }
                            } else if (!(itemStack.getHoverName().getString().equals(itemStack.getItemName().getString()))) {
                                modifyType.set("Modify");
                            } else {
                                modifyType.set("no");
                            }
                            if (data.has("enchantments")) {
                                JsonObject enchantment = data.getAsJsonObject("enchantments");
                                itemStack.getTagEnchantments().entrySet().forEach(entry -> {
                                    if (enchantment.has(entry.getKey().getRegisteredName())) {
                                        int level = enchantment.get(entry.getKey().getRegisteredName()).getAsInt();
                                        if (level != entry.getIntValue()) {
                                            modifyType.set("Modify");
                                        } else {
                                            modifyType.set("no");
                                        }
                                    } else {
                                        modifyType.set("Modify");
                                    }
                                });
                            } else if (itemStack.getTagEnchantments().isEmpty()) {
                                modifyType.set("no");
                            } else {
                                modifyType.set("Modify");
                            }
                            if (data.has("custom_data")) {
                                JsonObject customData = data.get("custom_data").getAsJsonObject();
                                if (!(customData.equals(gson.toJsonTree(ItemDataUtils.getItemCustomData(itemStack)).getAsJsonObject()))) {
                                    modifyType.set("Modify");
                                } else {
                                    modifyType.set("no");
                                }
                            } else if (ItemDataUtils.getItemCustomData(itemStack) == null) {
                                modifyType.set("no");
                            } else {
                                modifyType.set("Modify");
                            }
                        } else if (itemStack.getCount() < itemCount) {
                            modifyType.set("Take");
                        } else {
                            modifyType.set("Save");
                        }
                    } else if (BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString().equals("minecraft:air")) {
                        modifyType.set("Take");
                    } else if (itemName.equals("minecraft:air")) {
                        modifyType.set("Save");
                    } else {
                        modifyType.set("Modify");
                    }

                    if (!(modifyType.get().equals("no"))) {
                        String SQL = """
                                     INSERT INTO PLAYER_CONTAINER_INFO(
                                     player_uuid,
                                     player_name,
                                     player_pos,
                                     player_world,
                                     container_id,
                                     container_pos,
                                     container_world,
                                     slot_index,
                                     source_item,
                                     modify_item,
                                     modify_type
                                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                     """;

                        MySQLExecutorService.getExecutor().execute(() -> {
                            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                                    statement.setString(1, playerData.uuid);
                                    statement.setString(2, playerData.name);
                                    statement.setString(3, playerData.pos);
                                    statement.setString(4, playerData.world);
                                    statement.setString(5, BuiltInRegistries.BLOCK.getKey(containerBlockID.getBlock()).toString());
                                    statement.setString(6, containerBlockPos.toString());
                                    statement.setString(7, containerWorld);
                                    statement.setInt(8, i);
                                    statement.setString(9, data.toString());
                                    statement.setString(10, gson.toJson(ItemDataUtils.getItemData(itemStack)));
                                    statement.setString(11, modifyType.get());

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
            }

            @Override
            public void dataChanged(AbstractContainerMenu abstractContainerMenu, int i, int i1) {}
        });

        LOGGER.info(gson.toJson(openedContainerData));
    }
}
