package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.event.PlayerInteractEvent.RightClickBlock;
import com.xiaoyu.worldlogger.mysql.InitMySQL;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.utils.ContainerUtils;
import com.xiaoyu.worldlogger.utils.HashUtils;
import com.xiaoyu.worldlogger.utils.ItemDataUtils;
import com.xiaoyu.worldlogger.utils.StringData;
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

public class PlayerContainerInfo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Object> openedContainerData = new HashMap<>();
    private static final Map<String, Object> modifyContainerData = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerContainerEventOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AbstractContainerMenu container = event.getContainer();
        if (container == player.inventoryMenu || container instanceof InventoryMenu) return;
        Level level = player.level();

        PlayerSessionData playerData = new PlayerSessionData(player, level);

        String playerHash = HashUtils.sha1(playerData.uuid + playerData.name);
        int slotSize = container.slots.size() - 36;
        BlockState containerBlockState = RightClickBlock.getRightClickBlocks(playerHash);
        String containerID = BuiltInRegistries.BLOCK.getKey(containerBlockState.getBlock()).toString();

        openedContainerData.put(playerHash ,ItemDataUtils.getContainerAllData(container, playerData, slotSize));

        Gson gson = new Gson();

        Map<String, Object> containerDataMap = new HashMap<>();
        modifyContainerData.put(playerHash, containerDataMap);

        container.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu abstractContainerMenu, int i, ItemStack itemStack) {
                if (i <  slotSize) {
                    JsonObject root = gson.toJsonTree(openedContainerData).getAsJsonObject();
                    JsonObject playerContainerData = root.getAsJsonObject(playerHash);
                    JsonObject containerData = playerContainerData.getAsJsonObject(
                            BuiltInRegistries.BLOCK.getKey(containerBlockState.getBlock()).toString()
                    );
                    JsonObject slotDataRoot = containerData.getAsJsonObject("slotData");
                    JsonObject slotData = slotDataRoot.getAsJsonObject(String.valueOf(i));

                    String modifyType = ContainerUtils.getModifyValue(openedContainerData, playerHash, itemStack, containerBlockState, i);

                    Map<String , Object> data = new HashMap<>();
                    JsonObject sourceItem = slotData.getAsJsonObject("data");

                    if (!(modifyType.equals("no"))) {
                        data.put("itemData", ItemDataUtils.getItemData(itemStack));
                        data.put("sourceItem", sourceItem);
                        data.put("modifyTime", StringData.getTime());
                        data.put("modifyType", modifyType);
                        data.put("containerID", containerID);
                        data.put("containerPos", StringData.getPos(RightClickBlock.getRightClickPos(playerHash)));
                        containerDataMap.put(String.valueOf(i), data);
                    } else {
                        containerDataMap.remove(String.valueOf(i));
                    }
                }
            }

            @Override
            public void dataChanged(AbstractContainerMenu abstractContainerMenu, int i, int i1) {}
        });

        LOGGER.info(gson.toJson(openedContainerData));
    }

    @SubscribeEvent
    public static void PLAYER_CONTAINER_INFO(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AbstractContainerMenu container = event.getContainer();
        if (container == player.inventoryMenu || container instanceof InventoryMenu) return;
        Level level = player.level();

        PlayerSessionData playerData = new PlayerSessionData(player, level);
        String playerHash = HashUtils.sha1(playerData.uuid + playerData.name);
        Gson gson = new Gson();

        JsonObject root = gson.toJsonTree(modifyContainerData).getAsJsonObject();
        JsonObject playerModifyData = root.getAsJsonObject(playerHash);

        LOGGER.info(gson.toJson(modifyContainerData));

        if (playerModifyData == null || playerModifyData.isEmpty()) {
            openedContainerData.remove(playerHash);
            modifyContainerData.remove(playerHash);
            return;
        }

        JsonObject data = playerModifyData.deepCopy();

        openedContainerData.remove(playerHash);
        modifyContainerData.remove(playerHash);

        String SQL = """
                     INSERT INTO PLAYER_CONTAINER_INFO(
                         time,
                         player_uuid,
                         player_name,
                         player_pos,
                         world,
                         container_id,
                         container_pos,
                         slot_index,
                         source_item,
                         modify_item,
                         modify_type
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """;

        MySQLExecutorService.getExecutor().execute(() -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    for (Map.Entry<String, JsonElement> slotEntry : data.entrySet()) {
                        String slotIndex = slotEntry.getKey();
                        JsonObject slotData = slotEntry.getValue().getAsJsonObject();

                        String modifyType = slotData.get("modifyType").getAsString();
                        String modifyTime = slotData.get("modifyTime").getAsString();
                        String containerID = slotData.get("containerID").getAsString();
                        String containerPos = slotData.get("containerPos").getAsString();
                        JsonObject itemData = slotData.getAsJsonObject("itemData");
                        JsonObject sourceItem = slotData.getAsJsonObject("sourceItem");

                        statement.setString(1, modifyTime);
                        statement.setString(2, playerData.uuid);
                        statement.setString(3, playerData.name);
                        statement.setString(4, playerData.pos);
                        statement.setString(5, playerData.world);
                        statement.setString(6, containerID);
                        statement.setString(7, containerPos);
                        statement.setInt(8, Integer.parseInt(slotIndex));
                        statement.setString(9, sourceItem.toString());
                        statement.setString(10, itemData.toString());
                        statement.setString(11, modifyType);

                        statement.executeUpdate();
                    }
                } catch (SQLException e) {
                    LOGGER.error("Failed to execute SQL statement {}", SQL, e);
                }
            } catch (SQLException e) {
                LOGGER.error("Failed to insert player container info!", e);
            }
        });
    }
}
