package com.xiaoyu.worldlogger.event.PlayerInteractEvent;

import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.utils.HashUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录玩家最近一次在服务端右键的方块。
 *
 * <p>容器打开事件和经验变化事件不一定会告诉我们“玩家刚刚点了哪个方块”，
 * 因此这里用一个短期缓存保存上下文。玩家退出时必须调用 clear 清理，避免旧数据影响下次登录。</p>
 */
public class RightClickBlock {
    /** 玩家哈希 -> 最近右键方块状态。ConcurrentHashMap 允许不同线程安全读取。 */
    private static final Map<String, BlockState> rightClickBlockState = new ConcurrentHashMap<>();

    /** 玩家哈希 -> 最近右键方块坐标。容器日志会用它判断箱子位置。 */
    private static final Map<String, BlockPos> rightClickBlockPos = new ConcurrentHashMap<>();

    /**
     * 玩家右键方块时触发。
     *
     * @param event NeoForge 的右键方块事件。
     */
    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.RightClickBlock event) {
        // 只记录服务端玩家。客户端玩家的数据不能直接用于服务器写库。
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Level level = event.getLevel();

        // 根据右键位置读取方块状态，例如箱子、熔炉、铁砧等。
        BlockState blockState = level.getBlockState(event.getPos());

        // PlayerSessionData 用来统一取得 UUID、名字等信息。
        PlayerSessionData data = new PlayerSessionData(player, level);

        // UUID + 名字再哈希，作为 Map 的 key，避免直接到处拼接长字符串。
        String send = data.uuid + data.name;

        // 存方块状态和方块坐标。后续容器事件或 XP 事件会用同一个 playerHash 取回来。
        rightClickBlockState.put(HashUtils.sha1(send), blockState);

        rightClickBlockPos.put(HashUtils.sha1(send), event.getPos());
    }

    /**
     * 获取玩家最近右键的方块状态。
     *
     * @param playerHash 玩家哈希 key。
     * @return 方块状态；如果没有记录则返回 null。
     */
    public static BlockState getRightClickBlocks(String playerHash) {
        return rightClickBlockState.get(playerHash);
    }

    /**
     * 获取玩家最近右键的方块坐标。
     *
     * @param playerHash 玩家哈希 key。
     * @return 方块坐标；如果没有记录则返回 null。
     */
    public static BlockPos getRightClickPos(String playerHash) {
        return rightClickBlockPos.get(playerHash);
    }

    /**
     * 清理玩家缓存。
     *
     * @param playerHash 玩家哈希 key。
     *
     * 注意：玩家退出时一定要调用，否则 Map 会长期保存旧玩家数据。
     */
    public static void clear(String playerHash) {
        rightClickBlockState.remove(playerHash);
        rightClickBlockPos.remove(playerHash);
    }
}
