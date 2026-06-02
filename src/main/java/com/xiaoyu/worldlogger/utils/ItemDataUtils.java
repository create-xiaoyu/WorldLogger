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

public class ItemDataUtils {
    public static Map<String, Object> getItemData(ItemStack itemStack) {
        Map<String, Object> item = new HashMap<>();

        item.put("item", BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString());
        item.put("count", itemStack.getCount());

        if (itemStack.getCustomName() != null) {
            item.put("name", itemStack.getHoverName().getString());
        }

        if (getItemCustomData(itemStack) != null) {
            item.put("custom_data", getItemCustomData(itemStack));
        }

        if (getEnchantments(itemStack) != null) {
            item.put("enchantments", getEnchantments(itemStack));
        }

        return item;
    }

    public static Map<String, Object> getContainerAllData(AbstractContainerMenu container, PlayerSessionData playerData, int slotSize) {
        Map<String, Object> containerData = new HashMap<>();
        Map<String, Object> slotData = new HashMap<>();
        Map<String, Object> slotIndexData = new HashMap<>();

        BlockState containerBlock = RightClickBlock.getRightClickBlocks(HashUtils.sha1(playerData.uuid + playerData.name));

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

    public static Map<String, Object> generateSlotData(int i, ItemStack itemStack) {
        Map<String, Object> slotData = new HashMap<>();
        Map<String, Object> slotIndexData = new HashMap<>();
        Map<String, Object> itemData = new HashMap<>();

        itemData.put("data", ItemDataUtils.getItemData(itemStack));
        slotIndexData.put(String.valueOf(i), itemData);
        slotData.put("slotData", slotIndexData);

        return slotData;
    }

    public static Map<String, Integer> getEnchantments(ItemStack itemStack) {
        if (itemStack.getTagEnchantments().isEmpty()) return null;

        Map<String, Integer> enchantments = new HashMap<>();

        itemStack.getTagEnchantments().entrySet().forEach(entry -> {
            String regId = entry.getKey().getRegisteredName();

            enchantments.put(regId, entry.getIntValue());
        });

        return enchantments;
    }

    public static CompoundTag getItemCustomData(ItemStack itemStack) {
        CustomData customData = itemStack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;

        return customData.copyTag();
    }
}
