package com.xiaoyu.worldlogger.utils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.Map;

public class ItemUtils {
    public static Map<String, Object> getItemData(ItemStack itemStack) {
        Map<String, Object> item = new HashMap<>();

        CustomData customData = itemStack.get(DataComponents.CUSTOM_DATA);

        item.put("item", BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString());
        item.put("count", itemStack.getCount());

        if (customData != null) {
            CompoundTag tag = customData.copyTag();

            item.put("custom_data", tag.toString());
        } else {
            item.put("tag", null);
        }

        Map<String, Map<String, Object>> enchantments = new HashMap<>();

        itemStack.getTagEnchantments().entrySet().forEach(entry -> {
            String regId = entry.getKey().getRegisteredName();

            Map<String, Object> enchantmentInfo = new HashMap<>();
            enchantmentInfo.put("name", entry.getKey().value().description().getString());
            enchantmentInfo.put("level", entry.getIntValue());

            enchantments.put(regId, enchantmentInfo);
            item.put("enchantments", enchantments);
        });

        return item;
    }
}
