package com.xiaoyu.worldlogger.event.PlayerInteractEvent;

import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.utils.HashUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;

public class RightClickBlock {
    private static final Map<String, BlockState> rightClickBlockState = new HashMap<>();
    private static final Map<String, BlockPos> rightClickBlockPos = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = event.getLevel();

        BlockState blockState = level.getBlockState(event.getPos());

        PlayerSessionData data = new PlayerSessionData(player, level);

        String send = data.uuid + data.name;

        rightClickBlockState.put(HashUtils.sha1(send), blockState);

        rightClickBlockPos.put(HashUtils.sha1(send), event.getPos());
    }

    public static BlockState getRightClickBlocks(String playerHash) {
        return rightClickBlockState.get(playerHash);
    }

    public static BlockPos getRightClickPos(String playerHash) {
        return rightClickBlockPos.get(playerHash);
    }
}
