package com.xiaoyu.worldlogger.event.CommandEvent;

import com.mojang.brigadier.context.ParsedCommandNode;
import com.xiaoyu.worldlogger.data.PlayerSessionData;
import com.xiaoyu.worldlogger.utils.HashUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录玩家最近一次在服务端执行的命令。
 *
 * <p>ItemTossEvent 事件会被 give 指令触发，因此需要记录玩家最近执行的命令，
 * 这里用一个短期缓存保存上下文。玩家退出时必须调用 clear 清理，避免旧数据影响下次登录。</p>
 */
public class ExecuteCommands {
    /** 玩家哈希 -> 最近执行命令。ConcurrentHashMap 允许不同线程安全读取。 */
    private static final Map<String, String> executeCommands = new ConcurrentHashMap<>();

    /**
     * 执行命令时时触发。
     *
     * @param event NeoForge 的命令事件。
     */
    @SubscribeEvent
    public static void onCommandEvent(CommandEvent event) {
        // 获取对象快照
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        Level level = source.getLevel();
        ServerPlayer player = source.getPlayer();

        // 如果不是玩家执行的命令则直接返回
        if (player == null) return;
        // 玩家会话信息快照
        PlayerSessionData data = new PlayerSessionData(player, level);

        // 获取玩家执行的命令主名称
        List<ParsedCommandNode<CommandSourceStack>> nodes = event.getParseResults().getContext().getNodes();
        String rootCommand = nodes.isEmpty() ? "" : nodes.getFirst().getNode().getName();
        String playerHash = HashUtils.sha1(data.uuid + data.name);

        // 存入缓存
        executeCommands.put(playerHash, rootCommand);
    }

    /**
     * 获取玩家最近执行的命令。
     *
     * @param playerHash 玩家哈希 key。
     * @return 字符串
     */
    public static String getExecuteCommand(String playerHash) {
        return executeCommands.get(playerHash);
    }

    /**
     * 清理玩家缓存。
     *
     * @param playerHash 玩家哈希 key。
     *
     * 注意：玩家退出时一定要调用，否则 Map 会长期保存旧玩家数据。
     */
    public static void clear(String playerHash) {
        executeCommands.remove(playerHash);
    }
}
