package com.xiaoyu.worldlogger.data;

import com.xiaoyu.worldlogger.utils.StringData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块信息快照。
 *
 * <p>事件对象里的方块状态、位置、世界对象都属于 Minecraft 运行时对象。
 * 写数据库前先把它们转成普通字符串，可以安全地交给异步线程使用。</p>
 */
public class BlockInfoData {
    /** 方块注册 ID，例如 minecraft:stone。 */
    public final String blockID;

    /** 方块坐标，格式固定为 [X: ?, Y: ?, Z: ?]，查询范围时会按这个格式解析。 */
    public final String blockPos;

    /** 方块实体 NBT。普通方块没有 BlockEntity，所以可能为 null。 */
    public final String blockNBT;

    /**
     * 从一个方块状态和位置中提取数据库需要的数据。
     *
     * @param block 当前方块状态，用来获取方块 ID。
     * @param pos 方块所在坐标。
     * @param level 方块所在世界，用来读取方块实体 NBT。
     */
    public BlockInfoData(BlockState block, BlockPos pos, Level level) {
        // 只有箱子、熔炉等带数据的方块才有 BlockEntity，普通石头泥土会得到 null。
        BlockEntity blockEntity = level.getBlockEntity(pos);

        // 注册表中的 key 是最稳定的方块标识，适合写进数据库长期保存。
        this.blockID = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();

        // 统一使用 StringData 的坐标格式，方便 GUI、聊天显示和附近搜索复用。
        this.blockPos = StringData.getPos(pos);

        // saveWithoutMetadata 会保存方块实体自身数据，不写入坐标等额外元数据。
        if (blockEntity != null) {
            this.blockNBT = blockEntity.saveWithoutMetadata(level.registryAccess()).toString();
        } else {
            // 没有方块实体时写 null，比写空字符串更能表达“没有数据”。
            this.blockNBT = null;
        }
    }
}
