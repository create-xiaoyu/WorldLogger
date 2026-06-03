package com.xiaoyu.worldlogger.utils;

import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.event.PlayerInteractEvent.RightClickBlock;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * 物品序列化工具类。
 *
 * <p>Minecraft 的 ItemStack 不能直接长期保存到数据库里，所以这里把它转换成 Map，
 * 再由 Gson 转成 JSON 字符串。查询聊天栏时可以只取 item 字段，GUI 中则可以显示完整 JSON。</p>
 */
public class ItemDataUtils {
    /**
     * 把一个 ItemStack 转换成可写入数据库的普通 Map。
     *
     * @param itemStack Minecraft 物品堆对象。
     * @return 包含物品 ID、数量、名称、自定义数据、附魔等信息的 Map。
     */
    public static Map<String, Object> getItemData(ItemStack itemStack) {
        // HashMap 用来保存一组 key-value 数据，之后 Gson 可以把它转成 JSON。
        Map<String, Object> item = new HashMap<>();

        // item 是物品注册 ID，例如 minecraft:iron_ingot；count 是物品数量。
        item.put("item", BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString());
        item.put("count", itemStack.getCount());

        // 自定义名称只在存在时写入，避免普通物品 JSON 太长。
        if (itemStack.getCustomName() != null) {
            item.put("name", itemStack.getHoverName().getString());
        }

        // custom_data 可能包含模组数据或物品特殊标签，数量可能很大，所以只有存在时保存。
        if (getItemCustomData(itemStack) != null) {
            item.put("custom_data", getItemCustomData(itemStack));
        }

        // 附魔为空时返回 null，不写 enchantments 字段，让 JSON 更简洁。
        if (getEnchantments(itemStack) != null) {
            item.put("enchantments", getEnchantments(itemStack));
        }

        return item;
    }

    /**
     * 生成容器中所有槽位的数据。
     *
     * @param container 当前打开的容器菜单。
     * @param playerData 玩家信息快照，用来找到该玩家最近右键的方块。
     * @param slotSize 需要记录的容器槽位数量，不包含玩家背包槽位。
     * @return 以容器方块 ID 为外层 key 的完整槽位数据。
     * <p>
     * 注意：当前增量记录逻辑主要使用 PlayerContainerInfo 中的新快照方式，
     * 这个方法保留给之后需要“一次性保存完整容器状态”的场景。
     */
    public static Map<String, Object> getContainerAllData(AbstractContainerMenu container, PlayerSessionData playerData, int slotSize) {
        Map<String, Object> containerData = new HashMap<>();
        Map<String, Object> slotData = new HashMap<>();
        Map<String, Object> slotIndexData = new HashMap<>();

        // 容器事件本身不一定告诉我们打开的是哪个方块，所以通过最近右键缓存获取。
        BlockState containerBlock = RightClickBlock.getRightClickBlocks(HashUtils.sha1(playerData.uuid + playerData.name));

        // 遍历容器槽位，按槽位编号保存每个 ItemStack 的序列化结果。
        for (int i = 0; i < slotSize; i++) {
            Slot slot = container.slots.get(i);
            ItemStack stack = slot.getItem();

            Map<String, Object> itemData = new HashMap<>();

            itemData.put("data", ItemDataUtils.getItemData(stack));

            slotIndexData.put(String.valueOf(i), itemData);
        }

        slotData.put("slotData", slotIndexData);
        containerData.put(BuiltInRegistries.BLOCK.getKey(containerBlock.getBlock()).toString(), slotData);

        return containerData;
    }

    /**
     * 生成单个槽位的数据。
     *
     * @param i 槽位索引。
     * @param itemStack 槽位中的物品。
     * @return 只包含一个槽位的 Map 数据。
     */
    public static Map<String, Object> generateSlotData(int i, ItemStack itemStack) {
        Map<String, Object> slotData = new HashMap<>();
        Map<String, Object> slotIndexData = new HashMap<>();
        Map<String, Object> itemData = new HashMap<>();

        itemData.put("data", ItemDataUtils.getItemData(itemStack));
        slotIndexData.put(String.valueOf(i), itemData);
        slotData.put("slotData", slotIndexData);

        return slotData;
    }

    /**
     * 读取物品附魔。
     *
     * @param itemStack 物品堆。
     * @return key 为附魔注册 ID、value 为等级的 Map；没有附魔则返回 null。
     */
    public static Map<String, Integer> getEnchantments(ItemStack itemStack) {
        // 空附魔直接返回 null，方便调用者判断是否要写入 JSON 字段。
        if (itemStack.getTagEnchantments().isEmpty()) return null;

        Map<String, Integer> enchantments = new HashMap<>();

        // entrySet 中包含每个附魔及其等级，RegisteredName 是适合持久化的字符串 ID。
        itemStack.getTagEnchantments().entrySet().forEach(entry -> {
            String regId = entry.getKey().getRegisteredName();

            enchantments.put(regId, entry.getIntValue());
        });

        return enchantments;
    }

    /**
     * 读取物品自定义数据。
     *
     * @param itemStack 物品堆。
     * @return 自定义 NBT 数据副本；不存在自定义数据时返回 null。
     */
    public static CompoundTag getItemCustomData(ItemStack itemStack) {
        // NeoForge/Minecraft 1.21 使用 DataComponents 保存物品组件数据。
        CustomData customData = itemStack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;

        // copyTag 返回副本，避免外部修改影响原始 ItemStack。
        return customData.copyTag();
    }
}
