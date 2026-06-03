package com.xiaoyu.worldlogger.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

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

        return getModifyValue(data, itemStack);
    }

    public static String getModifyValue(JsonObject data, ItemStack itemStack) {
        Gson gson = new Gson();

        String itemName = data.get("item").getAsString();
        int itemCount = data.get("count").getAsInt();
        String currentItemName = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();

        if (currentItemName.equals(itemName) && !(currentItemName.equals("minecraft:air"))) {
            if (itemStack.getCount() == itemCount) {
                return hasSameItemDetails(gson, data, itemStack) ? "no" : "Modify";
            }

            return itemStack.getCount() < itemCount ? "Take" : "Save";
        } else if (currentItemName.equals("minecraft:air")) {
            return itemName.equals("minecraft:air") ? "no" : "Take";
        } else if (itemName.equals("minecraft:air")) {
            return "Save";
        }

        return "Modify";
    }

    private static boolean hasSameItemDetails(Gson gson, JsonObject sourceData, ItemStack itemStack) {
        if (!(hasSameCustomName(sourceData, itemStack))) {
            return false;
        }

        if (!(hasSameJsonData(gson, sourceData, "enchantments", ItemDataUtils.getEnchantments(itemStack)))) {
            return false;
        }

        return hasSameJsonData(gson, sourceData, "custom_data", ItemDataUtils.getItemCustomData(itemStack));
    }

    private static boolean hasSameCustomName(JsonObject sourceData, ItemStack itemStack) {
        if (sourceData.has("name")) {
            return itemStack.getCustomName() != null
                    && sourceData.get("name").getAsString().equals(itemStack.getHoverName().getString());
        }

        return itemStack.getCustomName() == null;
    }

    private static boolean hasSameJsonData(Gson gson, JsonObject sourceData, String key, Object currentData) {
        if (sourceData.has(key)) {
            if (currentData == null) {
                return false;
            }

            JsonElement currentJson = gson.toJsonTree(currentData);
            return sourceData.get(key).equals(currentJson);
        }

        return currentData == null;
    }
}
