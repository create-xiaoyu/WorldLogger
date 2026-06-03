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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家容器变化记录器。
 *
 * <p>容器记录最容易出错的地方是：玩家可能打开箱子后先取出物品，再不关闭箱子就放回去。
 * 如果只比较“打开时”和“关闭时”的最终状态，就会误以为没有变化。
 * 所以这里监听每次 slotChanged，把每个槽位变化按顺序记录下来。</p>
 */
public class PlayerContainerInfo {
    /** 日志对象，用于记录数据库写入失败。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 玩家背包在容器菜单中的槽位数量。容器自身槽位 = menu.slots.size() - 36。 */
    private static final int PLAYER_INVENTORY_SLOT_COUNT = 36;

    /** 玩家哈希 -> 槽位索引 -> 上一次看到的物品 JSON。用于判断下一次 slotChanged 的变化类型。 */
    private static final Map<String, Map<Integer, JsonObject>> lastContainerData = new HashMap<>();

    /** 玩家哈希 -> 本次打开容器期间发生的所有槽位变化。关闭容器时统一写入数据库。 */
    private static final Map<String, List<ContainerModification>> modifyContainerData = new HashMap<>();

    /**
     * 玩家打开容器时触发。
     *
     * @param event 容器打开事件。
     */
    @SubscribeEvent
    public static void onPlayerContainerEventOpen(PlayerContainerEvent.Open event) {
        // 只记录服务端玩家。
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // 玩家自己的背包不是箱子这类外部容器，跳过。
        AbstractContainerMenu container = event.getContainer();
        if (container == player.inventoryMenu || container instanceof InventoryMenu) return;
        Level level = player.level();

        // 捕获玩家信息。
        PlayerSessionData playerData = new PlayerSessionData(player, level);

        // playerHash 用于从多个 Map 中找到同一个玩家的数据。
        String playerHash = HashUtils.sha1(playerData.uuid + playerData.name);

        // 容器菜单后 36 个槽位通常是玩家背包，只记录容器自身槽位。
        int slotSize = Math.max(0, container.slots.size() - PLAYER_INVENTORY_SLOT_COUNT);

        // 容器事件不一定自带方块位置，所以从最近右键缓存中取容器方块信息。
        BlockState containerBlockState = RightClickBlock.getRightClickBlocks(playerHash);
        BlockPos containerBlockPos = RightClickBlock.getRightClickPos(playerHash);
        // 有些菜单打开前没有右键方块事件，这时使用 unknown 和玩家坐标兜底，保证日志不丢。
        String containerID = containerBlockState == null
                ? "unknown"
                : BuiltInRegistries.BLOCK.getKey(containerBlockState.getBlock()).toString();
        String containerPos = containerBlockPos == null ? playerData.pos : StringData.getPos(containerBlockPos);

        // Gson 用来把物品 Map 转成 JsonObject，方便之后比较和 deepCopy。
        Gson gson = new Gson();

        // 打开容器时先给每个容器槽位拍一张快照。
        Map<Integer, JsonObject> slotSnapshots = new HashMap<>();
        for (int i = 0; i < slotSize; i++) {
            slotSnapshots.put(i, getItemDataJson(gson, container.slots.get(i).getItem()));
        }
        lastContainerData.put(playerHash, slotSnapshots);

        // 保存本次打开期间的变化列表，关闭容器时会批量写库。
        List<ContainerModification> containerModifications = new ArrayList<>();
        modifyContainerData.put(playerHash, containerModifications);

        // 容器监听器会在槽位物品变化时被 Minecraft 调用。
        container.addSlotListener(new ContainerListener() {
            @Override
            public void slotChanged(AbstractContainerMenu abstractContainerMenu, int i, ItemStack itemStack) {
                // 只处理容器自身槽位，不处理玩家背包槽位。
                if (i >= 0 && i < slotSize) {
                    Map<Integer, JsonObject> lastSlotData = lastContainerData.get(playerHash);
                    if (lastSlotData == null) return;

                    // 读取该槽位上一次的快照；如果不存在，按空气处理。
                    JsonObject sourceItem = lastSlotData.get(i);
                    if (sourceItem == null) {
                        sourceItem = getItemDataJson(gson, ItemStack.EMPTY);
                    }
                    // 当前物品也转成 JSON，作为 modify_item 写入数据库。
                    JsonObject itemData = getItemDataJson(gson, itemStack);

                    // 判断变化类型：Take、Save、Modify 或 no。
                    String modifyType = ContainerUtils.getModifyValue(sourceItem, itemStack);

                    // no 表示没有变化，不写入数据库；其他类型都记录为一次槽位变化。
                    if (!(modifyType.equals("no"))) {
                        containerModifications.add(new ContainerModification(
                                i,
                                sourceItem.deepCopy(),
                                itemData.deepCopy(),
                                StringData.getTime(),
                                modifyType,
                                containerID,
                                containerPos
                        ));
                    }

                    // 无论是否写库，都必须更新快照。这样“取出 32 再放回 32”会记录两次变化。
                    lastSlotData.put(i, itemData.deepCopy());
                }
            }

            @Override
            // dataChanged 是容器同步数值变化，例如熔炉进度；当前只记录物品槽位变化，所以留空。
            public void dataChanged(AbstractContainerMenu abstractContainerMenu, int i, int i1) {}
        });
    }

    /**
     * 玩家关闭容器时触发，把本次打开期间的槽位变化写入 PLAYER_CONTAINER_INFO。
     *
     * @param event 容器关闭事件。
     */
    @SubscribeEvent
    public static void PLAYER_CONTAINER_INFO(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        AbstractContainerMenu container = event.getContainer();
        if (container == player.inventoryMenu || container instanceof InventoryMenu) return;
        Level level = player.level();

        PlayerSessionData playerData = new PlayerSessionData(player, level);
        String playerHash = HashUtils.sha1(playerData.uuid + playerData.name);
        List<ContainerModification> playerModifyData = modifyContainerData.get(playerHash);

        // 没有任何槽位变化时，只清理缓存，不写数据库。
        if (playerModifyData == null || playerModifyData.isEmpty()) {
            lastContainerData.remove(playerHash);
            modifyContainerData.remove(playerHash);
            return;
        }

        // 复制一份变化列表给异步线程使用，避免异步执行时原 Map 已被清理或修改。
        List<ContainerModification> data = List.copyOf(playerModifyData);

        // 容器关闭后立即清理缓存，防止玩家下次打开容器时旧数据混入。
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

        // 异步逐条写入本次容器变化。
        MySQLExecutorService.execute(LOGGER, "write player container info", () -> {
            try (Connection mysqlConnection = InitMySQL.getMySQLConnection()) {
                try (PreparedStatement statement = mysqlConnection.prepareStatement(SQL)) {
                    // 每个 ContainerModification 对应一条数据库记录。
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

    /**
     * 把 ItemStack 转换成 JsonObject。
     *
     * @param gson JSON 工具。
     * @param itemStack 物品堆。
     * @return 物品数据 JSON。
     */
    private static JsonObject getItemDataJson(Gson gson, ItemStack itemStack) {
        return gson.toJsonTree(ItemDataUtils.getItemData(itemStack)).getAsJsonObject();
    }

    /**
     * 一次容器槽位变化快照。
     *
     * @param slotIndex 槽位索引。
     * @param sourceItem 变化前的物品 JSON。
     * @param itemData 变化后的物品 JSON。
     * @param modifyTime 变化发生时间。
     * @param modifyType 变化类型。
     * @param containerID 容器方块 ID。
     * @param containerPos 容器坐标。
     */
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
