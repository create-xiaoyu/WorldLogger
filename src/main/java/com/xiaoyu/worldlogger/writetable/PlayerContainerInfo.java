package com.xiaoyu.worldlogger.writetable;

import com.google.gson.Gson;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlayerContainerInfo {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Map<Integer, JsonObject>> lastContainerData = new HashMap<>();
    private static final Map<String, List<ContainerModification>> modifyContainerData = new HashMap<>();

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

        Gson gson = new Gson();
        Map<Integer, JsonObject> slotSnapshots = new HashMap<>();
        for (int i = 0; i < slotSize; i++) {
            slotSnapshots.put(i, getItemDataJson(gson, container.slots.get(i).getItem()));
        }
        lastContainerData.put(playerHash, slotSnapshots);

        List<ContainerModification> containerModifications = new ArrayList<>();
        modifyContainerData.put(playerHash, containerModifications);

        container.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu abstractContainerMenu, int i, ItemStack itemStack) {
                if (i <  slotSize) {
                    Map<Integer, JsonObject> lastSlotData = lastContainerData.get(playerHash);
                    if (lastSlotData == null) return;

                    JsonObject sourceItem = lastSlotData.get(i);
                    if (sourceItem == null) {
                        sourceItem = getItemDataJson(gson, ItemStack.EMPTY);
                    }
                    JsonObject itemData = getItemDataJson(gson, itemStack);

                    String modifyType = ContainerUtils.getModifyValue(sourceItem, itemStack);

                    if (!(modifyType.equals("no"))) {
                        containerModifications.add(new ContainerModification(
                                i,
                                sourceItem.deepCopy(),
                                itemData.deepCopy(),
                                StringData.getTime(),
                                modifyType,
                                containerID,
                                StringData.getPos(RightClickBlock.getRightClickPos(playerHash))
                        ));
                    }

                    lastSlotData.put(i, itemData.deepCopy());
                }
            }

            @Override
            public void dataChanged(AbstractContainerMenu abstractContainerMenu, int i, int i1) {}
        });

        LOGGER.info(gson.toJson(lastContainerData));
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

        List<ContainerModification> playerModifyData = modifyContainerData.get(playerHash);

        LOGGER.info(gson.toJson(modifyContainerData));

        if (playerModifyData == null || playerModifyData.isEmpty()) {
            lastContainerData.remove(playerHash);
            modifyContainerData.remove(playerHash);
            return;
        }

        List<ContainerModification> data = List.copyOf(playerModifyData);

        lastContainerData.remove(playerHash);
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
                    for (ContainerModification modification : data) {
                        statement.setString(1, modification.modifyTime());
                        statement.setString(2, playerData.uuid);
                        statement.setString(3, playerData.name);
                        statement.setString(4, playerData.pos);
                        statement.setString(5, playerData.world);
                        statement.setString(6, modification.containerID());
                        statement.setString(7, modification.containerPos());
                        statement.setInt(8, modification.slotIndex());
                        statement.setString(9, modification.sourceItem().toString());
                        statement.setString(10, modification.itemData().toString());
                        statement.setString(11, modification.modifyType());

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

    private static JsonObject getItemDataJson(Gson gson, ItemStack itemStack) {
        return gson.toJsonTree(ItemDataUtils.getItemData(itemStack)).getAsJsonObject();
    }

    private record ContainerModification(
            int slotIndex,
            JsonObject sourceItem,
            JsonObject itemData,
            String modifyTime,
            String modifyType,
            String containerID,
            String containerPos
    ) {}
}
