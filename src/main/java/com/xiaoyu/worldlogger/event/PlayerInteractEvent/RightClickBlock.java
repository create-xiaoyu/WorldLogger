package com.xiaoyu.worldlogger.event.PlayerInteractEvent;

import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.utils.HashUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;

public class RightClickBlock {
    private static final Map<String, Block> rightClickBlocks = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = event.getLevel();

        BlockState blockState = level.getBlockState(event.getPos());
        Block block = blockState.getBlock();

        PlayerSessionData data = new PlayerSessionData(player, level);

        String send = data.uuid + data.name;

        rightClickBlocks.put(HashUtils.sha1(send), block);
    }

    public static Map<String, Block> getRightClickBlocks() {
        return rightClickBlocks;
    }
}
