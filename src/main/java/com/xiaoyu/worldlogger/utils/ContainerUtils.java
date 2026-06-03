package com.xiaoyu.worldlogger.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * 容器物品变化判断工具。
 *
 * <p>它负责比较“旧的槽位快照”和“现在的 ItemStack”，返回 Take、Save、Modify 或 no。
 * 返回值会写进 PLAYER_CONTAINER_INFO.modify_type，查询显示时再通过语言文件本地化。</p>
 */
public class ContainerUtils {
    /**
     * 从旧版完整容器快照中读取指定槽位并判断变化类型。
     *
     * @param Map 完整容器快照数据。
     * @param playerHash 玩家哈希 key。
     * @param itemStack 当前槽位物品。
     * @param blockState 容器方块状态。
     * @param i 槽位索引。
     * @return Take 表示取出，Save 表示放入，Modify 表示替换或数据变化，no 表示无变化。
     */
    public static String getModifyValue(Map<String, Object> Map, String playerHash, ItemStack itemStack, BlockState blockState, int i) {
        Gson gson = new Gson();

        // 下面几行是在 JSON 树中逐层定位到指定玩家、指定容器、指定槽位的数据。
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

    /**
     * 比较单个槽位的旧数据和当前物品。
     *
     * @param data 旧的物品 JSON，例如 {"item":"minecraft:stone","count":64}。
     * @param itemStack 当前槽位里的 ItemStack。
     * @return Take、Save、Modify 或 no。
     */
    public static String getModifyValue(JsonObject data, ItemStack itemStack) {
        Gson gson = new Gson();

        // 旧物品 ID 和旧数量来自数据库快照，当前物品 ID 和数量来自游戏中的 ItemStack。
        String itemName = data.get("item").getAsString();
        int itemCount = data.get("count").getAsInt();
        String currentItemName = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();

        // 同一种非空气物品：数量相同则继续比较名称、附魔、NBT；数量减少是取出，数量增加是放入。
        if (currentItemName.equals(itemName) && !(currentItemName.equals("minecraft:air"))) {
            if (itemStack.getCount() == itemCount) {
                return hasSameItemDetails(gson, data, itemStack) ? "no" : "Modify";
            }

            return itemStack.getCount() < itemCount ? "Take" : "Save";
        // 当前变成空气：旧物品也是空气代表没有变化，否则代表玩家取走了物品。
        } else if (currentItemName.equals("minecraft:air")) {
            return itemName.equals("minecraft:air") ? "no" : "Take";
        // 旧物品是空气但当前不是空气：代表玩家放入了物品。
        } else if (itemName.equals("minecraft:air")) {
            return "Save";
        }

        // 旧物品和当前物品都不是空气但 ID 不同，说明槽位内容被替换。
        return "Modify";
    }

    /**
     * 判断物品名称、附魔、自定义数据是否完全一致。
     *
     * @param gson JSON 工具，用于把当前附魔或 NBT 转成 JsonElement 后比较。
     * @param sourceData 旧物品 JSON。
     * @param itemStack 当前物品。
     * @return true 表示细节一致；false 表示同 ID 同数量但数据被修改。
     */
    private static boolean hasSameItemDetails(Gson gson, JsonObject sourceData, ItemStack itemStack) {
        if (!(hasSameCustomName(sourceData, itemStack))) {
            return false;
        }

        if (!(hasSameJsonData(gson, sourceData, "enchantments", ItemDataUtils.getEnchantments(itemStack)))) {
            return false;
        }

        return hasSameJsonData(gson, sourceData, "custom_data", ItemDataUtils.getItemCustomData(itemStack));
    }

    /**
     * 比较自定义名称。
     *
     * @param sourceData 旧物品 JSON。
     * @param itemStack 当前物品。
     * @return true 表示两边都没有名字，或者名字内容相同。
     */
    private static boolean hasSameCustomName(JsonObject sourceData, ItemStack itemStack) {
        // 旧数据有 name 时，当前物品也必须有自定义名称且文本相等。
        if (sourceData.has("name")) {
            return itemStack.getCustomName() != null
                    && sourceData.get("name").getAsString().equals(itemStack.getHoverName().getString());
        }

        // 旧数据没有 name 时，当前物品也不能有自定义名称。
        return itemStack.getCustomName() == null;
    }

    /**
     * 比较 JSON 字段，例如 enchantments 或 custom_data。
     *
     * @param gson JSON 工具。
     * @param sourceData 旧物品 JSON。
     * @param key 要比较的字段名。
     * @param currentData 当前物品对应字段的数据。
     * @return true 表示字段同时不存在，或者字段内容相等。
     */
    private static boolean hasSameJsonData(Gson gson, JsonObject sourceData, String key, Object currentData) {
        // 旧数据有这个字段时，当前数据必须存在并且转成 JSON 后完全相等。
        if (sourceData.has(key)) {
            if (currentData == null) {
                return false;
            }

            JsonElement currentJson = gson.toJsonTree(currentData);
            return sourceData.get(key).equals(currentJson);
        }

        // 旧数据没有这个字段时，当前数据也必须为空，才算没有变化。
        return currentData == null;
    }
}
