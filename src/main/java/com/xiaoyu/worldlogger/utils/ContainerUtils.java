package com.xiaoyu.worldlogger.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class ContainerUtils {
    public static String getModifyValue(Map<String, Object> Map, String playerHash, ItemStack itemStack, BlockState blockState, int i) {
        Gson gson = new Gson();

        JsonObject root = gson.toJsonTree(Map).getAsJsonObject();
        JsonObject playerContainerData = root.getAsJsonObject(playerHash);
        JsonObject containerData = playerContainerData.getAsJsonObject(
                BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString()
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

        return modifyType.get();
    }
}
