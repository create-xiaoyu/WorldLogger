package com.xiaoyu.worldlogger.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.xiaoyu.worldlogger.client.WorldLoggerClientAiService;
import com.xiaoyu.worldlogger.client.screen.WorldLoggerGuiScreen;
import com.xiaoyu.worldlogger.data.ListData;
import com.xiaoyu.worldlogger.network.payload.AiApprovalRequestPayload;
import com.xiaoyu.worldlogger.network.payload.AiChatRequestPayload;
import com.xiaoyu.worldlogger.network.payload.AiResetRequestPayload;
import com.xiaoyu.worldlogger.network.payload.SearchRequestPayload;
import com.xiaoyu.worldlogger.network.payload.SelectTableRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * /worldlogger 客户端命令入口。
 *
 * <p>这里注册的是客户端命令，因为玩家输入命令后要先在客户端生成网络请求。
 * 真正查询数据库的代码仍然在服务器端执行，避免客户端直接接触数据库。</p>
 */
public class MainCommand {
    /**
     * 注册 /worldlogger 的三个子命令：select、search、gui。
     *
     * @param event NeoForge 客户端命令注册事件。
     */
    @SubscribeEvent
    public static void registerSelectCommand(RegisterClientCommandsEvent event) {
        // Brigadier 使用链式写法描述命令树：literal 是固定文本，argument 是可变参数。
        event.getDispatcher().register(
                    Commands.literal("worldlogger").then(
                        // /worldlogger select <table> [page]：分页查看某张表的一条完整记录。
                        Commands.literal("select").requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN)).then(
                                Commands.argument("table", StringArgumentType.string())
                                        // 自动补全表名。ListData 是白名单，能让提示和安全校验共用同一份表列表。
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(ListData.getTables(), builder))
                                        // 没有输入页码时默认第一页。
                                        .executes(context -> sendSelectRequest(context.getSource(), StringArgumentType.getString(context, "table"), 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                // 页码参数限制为 >= 1，防止出现第 0 页或负数页。
                                                .executes(context -> sendSelectRequest(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "table"),
                                                        IntegerArgumentType.getInteger(context, "page")
                                                )))
                        )
                ).then(
                        // /worldlogger search [page]：以玩家当前位置为中心，搜索附近 16 格内的记录。
                        Commands.literal("search")
                                .executes(context -> sendSearchRequest(context.getSource(), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> sendSearchRequest(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "page")
                                        )))
                ).then(
                        // /worldlogger gui：打开客户端 GUI，通过网络向服务器请求表数据。
                        Commands.literal("gui")
                                .requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                                .executes(context -> openGui())
                ).then(
                        // /worldlogger ai <message>：和 AI 对话；多人服务器发给服务器，单人游戏本地客户端直接调用。
                        Commands.literal("ai")
                                .requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                                .then(Commands.literal("reset")
                                        .executes(context -> resetAi(context.getSource())))
                                .then(Commands.literal("approve")
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .executes(context -> approveAi(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "id")
                                                ))))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> sendAiChat(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "message")
                                        )))
                )
        );
    }

    /**
     * 发送 select 查询请求。
     *
     * @param source 命令来源，用于给玩家回显提示。
     * @param value 玩家输入的表名。
     * @param page 要查询的页码。
     * @return Brigadier 命令结果，1 表示成功发送请求，0 表示输入无效。
     */
    private static int sendSelectRequest(CommandSourceStack source, String value, int page) {
        // 先用白名单把玩家输入修正成标准表名，非法表名直接拒绝。
        String table = ListData.findTable(value);
        if (table == null) {
            source.sendSystemMessage(Component.translatable("text.worldlogger.command.worldlogger.select.no_equals"));
            return 0;
        }

        // 客户端不能直接查数据库，所以把请求包发给服务器。
        ClientPacketDistributor.sendToServer(new SelectTableRequestPayload(table, page));
        // 立即显示“请求已发送”，真正结果要等服务器异步查询后回包。
        source.sendSystemMessage(Component.translatable("text.worldlogger.command.worldlogger.select.request", table, page));
        return 1;
    }

    /**
     * 发送附近搜索请求。
     *
     * @param source 命令来源。
     * @param page 页码。
     * @return Brigadier 命令结果，1 表示请求已发送。
     */
    private static int sendSearchRequest(CommandSourceStack source, int page) {
        ClientPacketDistributor.sendToServer(new SearchRequestPayload(page));
        source.sendSystemMessage(Component.translatable("text.worldlogger.command.worldlogger.search.request", page));
        return 1;
    }

    /**
     * 打开 WorldLogger GUI。
     *
     * @return Brigadier 命令结果，1 表示 GUI 已打开。
     */
    private static int openGui() {
        WorldLoggerGuiScreen.open();
        return 1;
    }

    /**
     * 发送 AI 聊天请求。
     *
     * @param source 命令来源。
     * @param message 玩家输入。
     * @return Brigadier 命令结果。
     */
    private static int sendAiChat(CommandSourceStack source, String message) {
        if (message.isBlank()) {
            source.sendSystemMessage(Component.translatable("text.worldlogger.ai.error.empty"));
            return 0;
        }

        String language = currentLanguage();
        if (isSingleplayer()) {
            WorldLoggerClientAiService.chat(message, language);
        } else {
            ClientPacketDistributor.sendToServer(new AiChatRequestPayload(message, language));
            source.sendSystemMessage(Component.translatable("text.worldlogger.ai.request.sent", message));
        }
        return 1;
    }

    /**
     * 批准 AI 的超深度数据库工具调用。
     *
     * @param source 命令来源。
     * @param approvalId 审批 ID。
     * @return Brigadier 命令结果。
     */
    private static int approveAi(CommandSourceStack source, String approvalId) {
        if (isSingleplayer()) {
            source.sendSystemMessage(Component.translatable("text.worldlogger.ai.approval.client_unavailable"));
            return 0;
        }

        ClientPacketDistributor.sendToServer(new AiApprovalRequestPayload(approvalId));
        source.sendSystemMessage(Component.translatable("text.worldlogger.ai.approval.sent", approvalId));
        return 1;
    }

    /**
     * 重置 AI 对话上下文。
     *
     * @param source 命令来源。
     * @return Brigadier 命令结果。
     */
    private static int resetAi(CommandSourceStack source) {
        if (isSingleplayer()) {
            WorldLoggerClientAiService.reset();
        } else {
            ClientPacketDistributor.sendToServer(new AiResetRequestPayload());
            source.sendSystemMessage(Component.translatable("text.worldlogger.ai.reset.sent"));
        }
        return 1;
    }

    /**
     * 判断当前客户端是否在单人游戏中。
     *
     * @return true 表示当前存在内置单人服务器。
     */
    private static boolean isSingleplayer() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.hasSingleplayerServer();
    }

    /**
     * 读取当前客户端语言代码。
     *
     * @return Minecraft 语言代码，例如 zh_cn、en_us。
     */
    private static String currentLanguage() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getLanguageManager() == null) {
            return "en_us";
        }
        return minecraft.getLanguageManager().getSelected();
    }
}
