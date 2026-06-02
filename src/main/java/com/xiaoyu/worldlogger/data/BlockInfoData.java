package com.xiaoyu.worldlogger.data;

import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BlockInfoData {
    public final String blockID;
    public final String blockPos;
    public final String blockNBT;

    public BlockInfoData(BlockState block, BlockPos pos, Level level) {
        BlockEntity blockEntity = level.getBlockEntity(pos);

        this.blockID = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
        this.blockPos = StringData.getPos(pos);

        if (blockEntity != null) {
            this.blockNBT = blockEntity.saveWithoutMetadata(level.registryAccess()).toString();
        } else {
            this.blockNBT = null;
        }
    }
}
