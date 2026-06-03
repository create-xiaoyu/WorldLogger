package com.xiaoyu.worldlogger.network;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.ListData;
import com.xiaoyu.worldlogger.mysql.MySQLExecutorService;
import com.xiaoyu.worldlogger.network.payload.GuiTableRequestPayload;
import com.xiaoyu.worldlogger.network.payload.GuiTableResponsePayload;
import com.xiaoyu.worldlogger.network.payload.SearchRequestPayload;
import com.xiaoyu.worldlogger.network.payload.SearchResponsePayload;
import com.xiaoyu.worldlogger.network.payload.SelectTableRequestPayload;
import com.xiaoyu.worldlogger.network.payload.SelectTableResponsePayload;
import com.xiaoyu.worldlogger.query.GuiTableQueryService;
import com.xiaoyu.worldlogger.query.TableQueryService;
import com.xiaoyu.worldlogger.query.WorldSearchQueryService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

import java.sql.SQLException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * WorldLogger 自定义网络包注册和处理中心。
 *
 * <p>所有“客户端请求服务器查数据库、服务器返回结果给客户端”的逻辑都在这里。
 * 重要流程是：客户端发包 -> 服务器校验权限 -> MySQL 线程池查询 -> 回到服务器线程 -> 发响应包 -> 客户端显示。</p>
 *
 * <p>注意：数据库查询不能在网络线程或游戏主线程里执行，否则数据量大时会卡游戏。
 * 但向玩家发包、读取在线玩家列表等 Minecraft 操作应该回到服务器线程执行。</p>
 */
public final class WorldLoggerNetwork {
    /** 日志对象，用于记录查询失败、线程池拒绝任务、客户端 GUI 分发失败等错误。 */
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 网络协议版本。客户端和服务器版本不一致时，NeoForge 会拒绝互通这些包。 */
    private static final String NETWORK_VERSION = "6";

    /** 工具类不需要创建对象，所以构造方法私有化。 */
    private WorldLoggerNetwork() {}

    /**
     * 注册所有自定义网络包。
     *
     * @param event NeoForge 网络包注册事件。
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        // registrar 绑定协议版本，下面每个 playToServer/playToClient 都属于这个版本。
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);

        // select 请求：客户端 -> 服务器。
        registrar.playToServer(
                SelectTableRequestPayload.TYPE,
                SelectTableRequestPayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSelectRequest
        );

        // select 响应：服务器 -> 客户端。
        registrar.playToClient(
                SelectTableResponsePayload.TYPE,
                SelectTableResponsePayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSelectResponse
        );

        // nearby search 请求：客户端 -> 服务器。
        registrar.playToServer(
                SearchRequestPayload.TYPE,
                SearchRequestPayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSearchRequest
        );

        // nearby search 响应：服务器 -> 客户端。
        registrar.playToClient(
                SearchResponsePayload.TYPE,
                SearchResponsePayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSearchResponse
        );

        // GUI 表查询请求：客户端 -> 服务器。
        registrar.playToServer(
                GuiTableRequestPayload.TYPE,
                GuiTableRequestPayload.STREAM_CODEC,
                WorldLoggerNetwork::handleGuiTableRequest
        );

        // GUI 表查询响应：服务器 -> 客户端。
        registrar.playToClient(
                GuiTableResponsePayload.TYPE,
                GuiTableResponsePayload.STREAM_CODEC,
                WorldLoggerNetwork::handleGuiTableResponse
        );
    }

    /**
     * 服务器处理 /worldlogger select 请求。
     *
     * @param payload 客户端发来的查询表名和页码。
     * @param context 网络上下文，可取得发包玩家。
     */
    private static void handleSelectRequest(SelectTableRequestPayload payload, IPayloadContext context) {
        // 只有服务端玩家才允许查询。理论上 playToServer 包在服务器端处理，但这里仍然做类型保护。
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        // 权限必须在服务器重新检查，不能相信客户端命令侧的 requires。
        if (!serverPlayer.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(payload.table(), payload.page(), "text.worldlogger.command.worldlogger.select.no_permission"));
            return;
        }

        // 表名必须经过白名单转换，防止把玩家输入直接拼进 SQL。
        String table = ListData.findTable(payload.table());
        if (table == null) {
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(payload.table(), payload.page(), "text.worldlogger.command.worldlogger.select.unknown", payload.table()));
            return;
        }

        // CompletableFuture 使用 MySQL 线程池，慢查询不会阻塞网络线程或服务器主线程。
        ExecutorService executor = MySQLExecutorService.getExecutor();
        if (executor == null || executor.isShutdown()) {
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(table, payload.page(), "text.worldlogger.error.service"));
            return;
        }

        // 只保存 server 和 UUID。异步任务结束后再用 UUID 查当前在线玩家，避免玩家下线后还给旧对象发包。
        MinecraftServer server = serverPlayer.level().getServer();
        UUID playerId = serverPlayer.getUUID();
        try {
            // supplyAsync 在线程池中执行 queryTable；whenComplete 在查询结束时处理成功或失败。
            CompletableFuture.supplyAsync(() -> queryTable(table, payload.page()), executor)
                    // server.executeIfPossible 会把发包逻辑切回服务器线程，更符合 Minecraft 线程模型。
                    .whenComplete((queryPage, throwable) -> server.executeIfPossible(() -> {
                        ServerPlayer target = server.getPlayerList().getPlayer(playerId);
                        if (target == null || target.hasDisconnected()) {
                            return;
                        }

                        if (throwable != null) {
                            LOGGER.error("Failed to query WorldLogger table {}", table, throwable);
                            PacketDistributor.sendToPlayer(target, SelectTableResponsePayload.error(table, payload.page(), "text.worldlogger.error.select_error", rootMessage(throwable)));
                            return;
                        }

                        // 查询成功后，把服务层结果转换成网络 payload 行并发给客户端。
                        PacketDistributor.sendToPlayer(target, SelectTableResponsePayload.success(table, queryPage.page(), queryPage.hasNext(), toPayloadLines(queryPage)));
                    }));
        } catch (RejectedExecutionException e) {
            LOGGER.error("WorldLogger query executor rejected a task.", e);
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(table, payload.page(), "text.worldlogger.error.executor.reject"));
        }
    }

    /**
     * 客户端处理 select 响应并显示在聊天栏。
     *
     * @param payload 服务器返回的查询结果。
     * @param context 网络上下文，可取得本地玩家。
     */
    private static void handleSelectResponse(SelectTableResponsePayload payload, IPayloadContext context) {
        // 查询失败时显示红色错误文本，messageKey 会走客户端语言文件，所以能按客户端语言本地化。
        if (!payload.success()) {
            context.player().sendSystemMessage(Component.translatable(payload.messageKey(), payload.messageArgs().toArray())
                    .withStyle(ChatFormatting.RED));
            return;
        }

        // 表头、内容、页脚模仿 /help 风格显示。
        context.player().sendSystemMessage(Component.literal(divider(payload.table())).withStyle(ChatFormatting.GOLD));
        if (payload.lines().isEmpty()) {
            context.player().sendSystemMessage(Component.translatable("text.worldlogger.command.select.notfound", payload.page(), payload.table()));
        }
        // 每一列显示为“本地化列名: 值”。
        for (SelectTableResponsePayload.QueryLine line : payload.lines()) {
            context.player().sendSystemMessage(formatLine(line));
        }
        context.player().sendSystemMessage(Component.literal(pageDivider(payload.table(), payload.page())).withStyle(ChatFormatting.GRAY));

        // 有下一页时添加可点击文本，点击后自动执行下一页命令。
        if (payload.hasNext()) {
            context.player().sendSystemMessage(nextPageComponent(payload));
        }
    }

    /**
     * 服务器处理 /worldlogger search 请求。
     *
     * @param payload 搜索页码。
     * @param context 网络上下文。
     */
    private static void handleSearchRequest(SearchRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        // nearby search 也属于管理员查询功能，所以必须检查权限。
        if (!serverPlayer.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            sendToPlayer(serverPlayer, SearchResponsePayload.error(payload.page(), "text.worldlogger.command.worldlogger.select.no_permission"));
            return;
        }

        ExecutorService executor = MySQLExecutorService.getExecutor();
        if (executor == null || executor.isShutdown()) {
            sendToPlayer(serverPlayer, SearchResponsePayload.error(payload.page(), "text.worldlogger.error.service"));
            return;
        }

        // 记录玩家当前位置和维度，异步查询时只使用这些普通值，不再读取玩家对象。
        MinecraftServer server = serverPlayer.level().getServer();
        UUID playerId = serverPlayer.getUUID();
        int centerX = serverPlayer.blockPosition().getX();
        int centerY = serverPlayer.blockPosition().getY();
        int centerZ = serverPlayer.blockPosition().getZ();
        String worldId = serverPlayer.level().dimension().identifier().toString();

        try {
            // 在数据库线程池中搜索附近记录，完成后回服务器线程发包。
            CompletableFuture.supplyAsync(() -> searchNear(centerX, centerY, centerZ, worldId, payload.page()), executor)
                    .whenComplete((searchResult, throwable) -> server.executeIfPossible(() -> {
                        ServerPlayer target = server.getPlayerList().getPlayer(playerId);
                        if (target == null || target.hasDisconnected()) {
                            return;
                        }

                        if (throwable != null) {
                            LOGGER.error("Failed to search WorldLogger data near player {}", playerId, throwable);
                            PacketDistributor.sendToPlayer(target, SearchResponsePayload.error(payload.page(), "text.worldlogger.error.search_error", rootMessage(throwable)));
                            return;
                        }

                        // 搜索成功后只发当前页 5 条左右的结果，避免聊天栏刷屏。
                        PacketDistributor.sendToPlayer(target, SearchResponsePayload.success(searchResult.page(), searchResult.hasNext(), searchResult.truncated(), toSearchLines(searchResult)));
                    }));
        } catch (RejectedExecutionException e) {
            LOGGER.error("WorldLogger query executor rejected a search task.", e);
            sendToPlayer(serverPlayer, SearchResponsePayload.error(payload.page(), "text.worldlogger.error.executor.reject"));
        }
    }

    /**
     * 客户端处理 nearby search 响应。
     *
     * @param payload 搜索结果。
     * @param context 网络上下文。
     */
    private static void handleSearchResponse(SearchResponsePayload payload, IPayloadContext context) {
        if (!payload.success()) {
            context.player().sendSystemMessage(Component.translatable(payload.messageKey(), payload.messageArgs().toArray())
                    .withStyle(ChatFormatting.RED));
            return;
        }

        context.player().sendSystemMessage(Component.translatable("text.worldlogger.search.header").withStyle(ChatFormatting.GOLD));
        if (payload.lines().isEmpty()) {
            context.player().sendSystemMessage(Component.translatable("text.worldlogger.search.notfound"));
            return;
        }

        // 搜索结果每条都是语言 key + 参数，最终文本由客户端语言文件决定。
        for (SearchResponsePayload.SearchLine line : payload.lines()) {
            context.player().sendSystemMessage(Component.translatable(line.translationKey(), line.args().toArray()));
        }
        // 如果总结果超过上限且没有下一页，提示用户结果被截断。
        if (payload.truncated() && !payload.hasNext()) {
            context.player().sendSystemMessage(Component.translatable("text.worldlogger.search.truncated").withStyle(ChatFormatting.GRAY));
        }
        context.player().sendSystemMessage(Component.literal(pageDivider("WorldLogger Search", payload.page())).withStyle(ChatFormatting.GRAY));

        if (payload.hasNext()) {
            context.player().sendSystemMessage(nextSearchPageComponent(payload));
        }
    }

    /**
     * 服务器处理 GUI 表格查询请求。
     *
     * @param payload GUI 发来的表名、页码、过滤词和请求编号。
     * @param context 网络上下文。
     */
    private static void handleGuiTableRequest(GuiTableRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (!serverPlayer.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            sendToPlayer(serverPlayer, GuiTableResponsePayload.error(payload.table(), payload.page(), payload.requestId(), "text.worldlogger.command.worldlogger.select.no_permission"));
            return;
        }

        String table = ListData.findTable(payload.table());
        if (table == null) {
            sendToPlayer(serverPlayer, GuiTableResponsePayload.error(payload.table(), payload.page(), payload.requestId(), "text.worldlogger.command.worldlogger.select.unknown", payload.table()));
            return;
        }

        // GUI 查询可能返回长 JSON/NBT，所以使用专门的 GUI payload，但仍然走同一个异步线程池。
        ExecutorService executor = MySQLExecutorService.getExecutor();
        if (executor == null || executor.isShutdown()) {
            sendToPlayer(serverPlayer, GuiTableResponsePayload.error(table, payload.page(), payload.requestId(), "text.worldlogger.error.service"));
            return;
        }

        MinecraftServer server = serverPlayer.level().getServer();
        UUID playerId = serverPlayer.getUUID();
        try {
            // requestId 会原样带回客户端，用来防止旧查询结果覆盖新查询结果。
            CompletableFuture.supplyAsync(() -> queryGuiTable(table, payload.page(), payload.filter()), executor)
                    .whenComplete((queryPage, throwable) -> server.executeIfPossible(() -> {
                        ServerPlayer target = server.getPlayerList().getPlayer(playerId);
                        if (target == null || target.hasDisconnected()) {
                            return;
                        }

                        if (throwable != null) {
                            LOGGER.error("Failed to query WorldLogger GUI table {}", table, throwable);
                            PacketDistributor.sendToPlayer(target, GuiTableResponsePayload.error(table, payload.page(), payload.requestId(), "text.worldlogger.error.gui_error", rootMessage(throwable)));
                            return;
                        }

                        PacketDistributor.sendToPlayer(target, GuiTableResponsePayload.success(table, queryPage.page(), payload.requestId(), queryPage.hasNext(), toGuiCells(queryPage)));
                    }));
        } catch (RejectedExecutionException e) {
            LOGGER.error("WorldLogger query executor rejected a GUI task.", e);
            sendToPlayer(serverPlayer, GuiTableResponsePayload.error(table, payload.page(), payload.requestId(), "text.worldlogger.error.executor.reject"));
        }
    }

    /**
     * 客户端处理 GUI 响应。
     *
     * @param payload GUI 查询响应。
     * @param context 网络上下文。
     */
    private static void handleGuiTableResponse(GuiTableResponsePayload payload, IPayloadContext context) {
        // 客户端 UI 操作放进 enqueueWork，确保在客户端安全线程中执行。
        context.enqueueWork(() -> invokeClientGuiHandler(payload));
    }

    /** 把查询服务的列数据转换成 select 响应包的行数据。 */
    private static List<SelectTableResponsePayload.QueryLine> toPayloadLines(TableQueryService.QueryPage queryPage) {
        return queryPage.columns().stream()
                .map(column -> new SelectTableResponsePayload.QueryLine(column.columnName(), column.value()))
                .toList();
    }

    /** 把搜索服务的记录转换成搜索响应包的行数据。 */
    private static List<SearchResponsePayload.SearchLine> toSearchLines(WorldSearchQueryService.SearchResult searchResult) {
        return searchResult.records().stream()
                .map(record -> new SearchResponsePayload.SearchLine(record.translationKey(), record.args()))
                .toList();
    }

    /** 把 GUI 查询服务的单元格转换成 GUI 响应包的单元格。 */
    private static List<GuiTableResponsePayload.QueryCell> toGuiCells(GuiTableQueryService.QueryPage queryPage) {
        return queryPage.cells().stream()
                .map(cell -> new GuiTableResponsePayload.QueryCell(cell.columnName(), cell.value()))
                .toList();
    }

    /**
     * 格式化 select 的普通列。
     *
     * @param line 一列数据。
     * @return 可发送到聊天栏的组件。
     */
    private static MutableComponent formatLine(SelectTableResponsePayload.QueryLine line) {
        // 容器物品变化需要特殊格式，例如“修改: Take 了 minecraft:iron_ingot 原来是 minecraft:air”。
        if ("modify".equals(line.columnName())) {
            return formatModifyLine(line);
        }

        // translatableWithFallback 允许语言文件没有对应 key 时回退到原始列名。
        return Component.translatableWithFallback(columnTranslationKey(line.columnName()), line.columnName())
                .append(": ")
                .append(Component.literal(line.value()));
    }

    /**
     * 格式化 PLAYER_CONTAINER_INFO 的物品变化行。
     *
     * @param line 值格式为 modifyType + tab + modifyItem + tab + sourceItem。
     * @return 本地化后的聊天组件。
     */
    private static MutableComponent formatModifyLine(SelectTableResponsePayload.QueryLine line) {
        String[] parts = line.value().split("\t", -1);
        // 如果格式不符合预期，就退回普通显示，避免因为单条坏数据导致客户端报错。
        if (parts.length != 3) {
            return Component.translatableWithFallback(columnTranslationKey(line.columnName()), line.columnName())
                    .append(": ")
                    .append(Component.literal(line.value()));
        }

        return Component.translatableWithFallback(columnTranslationKey(line.columnName()), line.columnName())
                .append(": ")
                .append(Component.translatable(
                        "text.worldlogger.modify.format",
                        modifyTypeComponent(parts[0]),
                        parts[1],
                        parts[2]
                ));
    }

    /** 把 modifyType 转成可本地化组件。 */
    private static MutableComponent modifyTypeComponent(String modifyType) {
        return Component.translatableWithFallback(
                "text.worldlogger.modify_type." + normalizeModifyType(modifyType),
                modifyType
        );
    }

    /** 标准化 modifyType，保证它能安全拼进语言 key。 */
    private static String normalizeModifyType(String modifyType) {
        return modifyType.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
    }

    /** 根据列名生成语言文件 key。 */
    private static String columnTranslationKey(String columnName) {
        return "text.worldlogger.name." + columnName;
    }

    /** 包装 TableQueryService 的 SQLException，让 CompletableFuture 能统一处理异常。 */
    private static TableQueryService.QueryPage queryTable(String table, int page) {
        try {
            return TableQueryService.selectPage(table, page);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    /** 包装附近搜索服务的 SQLException。 */
    private static WorldSearchQueryService.SearchResult searchNear(int centerX, int centerY, int centerZ, String worldId, int page) {
        try {
            return WorldSearchQueryService.searchNear(centerX, centerY, centerZ, worldId, page);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    /** 包装 GUI 查询服务的 SQLException。 */
    private static GuiTableQueryService.QueryPage queryGuiTable(String table, int page, String filter) {
        try {
            return GuiTableQueryService.selectPage(table, page, filter);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    /** 发送 select 响应包给指定玩家。重载方法让调用处更简洁。 */
    private static void sendToPlayer(ServerPlayer player, SelectTableResponsePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** 发送 search 响应包给指定玩家。 */
    private static void sendToPlayer(ServerPlayer player, SearchResponsePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** 发送 GUI 响应包给指定玩家。 */
    private static void sendToPlayer(ServerPlayer player, GuiTableResponsePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * 取得异常链最底层的错误信息。
     *
     * @param throwable CompletableFuture 包装过的异常。
     * @return 更接近真实原因的错误文本。
     */
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 调用客户端 GUI 处理器。
     *
     * <p>这里故意用反射，而不是直接 import 客户端类。
     * 原因是 WorldLoggerNetwork 属于 common 代码，专用服务器加载它时不能直接加载 Minecraft 客户端类。</p>
     */
    private static void invokeClientGuiHandler(GuiTableResponsePayload payload) {
        try {
            // 通过类名字符串查找客户端桥接类，专用服务端不会在类加载阶段解析这个客户端类。
            Class<?> hooks = Class.forName("com.xiaoyu.worldlogger.client.WorldLoggerClientHooks");
            Method handler = hooks.getMethod("handleGuiTableResponse", GuiTableResponsePayload.class);
            handler.invoke(null, payload);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            LOGGER.error("Failed to dispatch WorldLogger GUI response to the client screen.", e);
        }
    }

    /** 创建“点击显示下一页”的 select 聊天组件。 */
    private static MutableComponent nextPageComponent(SelectTableResponsePayload payload) {
        String command = "/worldlogger select " + payload.table() + " " + (payload.page() + 1);
        return Component.translatable("text.worldlogger.command.select.next")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /** 创建“点击显示下一页”的 search 聊天组件。 */
    private static MutableComponent nextSearchPageComponent(SearchResponsePayload payload) {
        String command = "/worldlogger search " + (payload.page() + 1);
        return Component.translatable("text.worldlogger.command.select.next")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    /** 生成表头分隔线。 */
    private static String divider(String title) {
        return "----- " + title + " -----";
    }

    /**
     * 生成页码分隔线，并尽量和表头长度对齐。
     *
     * @param table 表名或搜索标题。
     * @param page 页码。
     * @return 形如 ----- Page 1 ----- 的字符串。
     */
    private static String pageDivider(String table, int page) {
        String header = divider(table);
        String label = " " + Component.translatableWithFallback("text.worldlogger.command.select.page", "Page %d", page).getString() + " ";
        int totalHyphens = Math.max(2, header.length() - label.length());
        int leftHyphens = totalHyphens / 2;
        int rightHyphens = totalHyphens - leftHyphens;
        return "-".repeat(leftHyphens) + label + "-".repeat(rightHyphens);
    }
}
