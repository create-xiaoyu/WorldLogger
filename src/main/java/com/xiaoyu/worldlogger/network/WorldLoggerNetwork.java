package com.xiaoyu.worldlogger.network;

import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.client.screen.WorldLoggerGuiScreen;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public final class WorldLoggerNetwork {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NETWORK_VERSION = "6";

    private WorldLoggerNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);
        registrar.playToServer(
                SelectTableRequestPayload.TYPE,
                SelectTableRequestPayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSelectRequest
        );
        registrar.playToClient(
                SelectTableResponsePayload.TYPE,
                SelectTableResponsePayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSelectResponse
        );
        registrar.playToServer(
                SearchRequestPayload.TYPE,
                SearchRequestPayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSearchRequest
        );
        registrar.playToClient(
                SearchResponsePayload.TYPE,
                SearchResponsePayload.STREAM_CODEC,
                WorldLoggerNetwork::handleSearchResponse
        );
        registrar.playToServer(
                GuiTableRequestPayload.TYPE,
                GuiTableRequestPayload.STREAM_CODEC,
                WorldLoggerNetwork::handleGuiTableRequest
        );
        registrar.playToClient(
                GuiTableResponsePayload.TYPE,
                GuiTableResponsePayload.STREAM_CODEC,
                WorldLoggerNetwork::handleGuiTableResponse
        );
    }

    private static void handleSelectRequest(SelectTableRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (!serverPlayer.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(payload.table(), payload.page(), "text.worldlogger.command.worldlogger.select.no_permission"));
            return;
        }

        String table = ListData.findTable(payload.table());
        if (table == null) {
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(payload.table(), payload.page(), "text.worldlogger.command.worldlogger.select.unknown", payload.table()));
            return;
        }

        ExecutorService executor = MySQLExecutorService.getExecutor();
        if (executor == null || executor.isShutdown()) {
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(table, payload.page(), "text.worldlogger.error.service"));
            return;
        }

        MinecraftServer server = serverPlayer.level().getServer();
        UUID playerId = serverPlayer.getUUID();
        try {
            CompletableFuture.supplyAsync(() -> queryTable(table, payload.page()), executor)
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

                        PacketDistributor.sendToPlayer(target, SelectTableResponsePayload.success(table, queryPage.page(), queryPage.hasNext(), toPayloadLines(queryPage)));
                    }));
        } catch (RejectedExecutionException e) {
            LOGGER.error("WorldLogger query executor rejected a task.", e);
            sendToPlayer(serverPlayer, SelectTableResponsePayload.error(table, payload.page(), "text.worldlogger.error.executor.reject"));
        }
    }

    private static void handleSelectResponse(SelectTableResponsePayload payload, IPayloadContext context) {
        if (!payload.success()) {
            context.player().sendSystemMessage(Component.translatable(payload.messageKey(), payload.messageArgs().toArray())
                    .withStyle(ChatFormatting.RED));
            return;
        }

        context.player().sendSystemMessage(Component.literal(divider(payload.table())).withStyle(ChatFormatting.GOLD));
        if (payload.lines().isEmpty()) {
            context.player().sendSystemMessage(Component.translatable("text.worldlogger.command.select.notfound", payload.page(), payload.table()));
        }
        for (SelectTableResponsePayload.QueryLine line : payload.lines()) {
            context.player().sendSystemMessage(formatLine(line));
        }
        context.player().sendSystemMessage(Component.literal(pageDivider(payload.table(), payload.page())).withStyle(ChatFormatting.GRAY));

        if (payload.hasNext()) {
            context.player().sendSystemMessage(nextPageComponent(payload));
        }
    }

    private static void handleSearchRequest(SearchRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (!serverPlayer.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
            sendToPlayer(serverPlayer, SearchResponsePayload.error(payload.page(), "text.worldlogger.command.worldlogger.select.no_permission"));
            return;
        }

        ExecutorService executor = MySQLExecutorService.getExecutor();
        if (executor == null || executor.isShutdown()) {
            sendToPlayer(serverPlayer, SearchResponsePayload.error(payload.page(), "text.worldlogger.error.service"));
            return;
        }

        MinecraftServer server = serverPlayer.level().getServer();
        UUID playerId = serverPlayer.getUUID();
        int centerX = serverPlayer.blockPosition().getX();
        int centerY = serverPlayer.blockPosition().getY();
        int centerZ = serverPlayer.blockPosition().getZ();
        String worldId = serverPlayer.level().dimension().identifier().toString();

        try {
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

                        PacketDistributor.sendToPlayer(target, SearchResponsePayload.success(searchResult.page(), searchResult.hasNext(), searchResult.truncated(), toSearchLines(searchResult)));
                    }));
        } catch (RejectedExecutionException e) {
            LOGGER.error("WorldLogger query executor rejected a search task.", e);
            sendToPlayer(serverPlayer, SearchResponsePayload.error(payload.page(), "text.worldlogger.error.executor.reject"));
        }
    }

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

        for (SearchResponsePayload.SearchLine line : payload.lines()) {
            context.player().sendSystemMessage(Component.translatable(line.translationKey(), line.args().toArray()));
        }
        if (payload.truncated() && !payload.hasNext()) {
            context.player().sendSystemMessage(Component.translatable("text.worldlogger.search.truncated").withStyle(ChatFormatting.GRAY));
        }
        context.player().sendSystemMessage(Component.literal(pageDivider("WorldLogger Search", payload.page())).withStyle(ChatFormatting.GRAY));

        if (payload.hasNext()) {
            context.player().sendSystemMessage(nextSearchPageComponent(payload));
        }
    }

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

        ExecutorService executor = MySQLExecutorService.getExecutor();
        if (executor == null || executor.isShutdown()) {
            sendToPlayer(serverPlayer, GuiTableResponsePayload.error(table, payload.page(), payload.requestId(), "text.worldlogger.error.service"));
            return;
        }

        MinecraftServer server = serverPlayer.level().getServer();
        UUID playerId = serverPlayer.getUUID();
        try {
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

    private static void handleGuiTableResponse(GuiTableResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> WorldLoggerGuiScreen.handleResponse(payload));
    }

    private static List<SelectTableResponsePayload.QueryLine> toPayloadLines(TableQueryService.QueryPage queryPage) {
        return queryPage.columns().stream()
                .map(column -> new SelectTableResponsePayload.QueryLine(column.columnName(), column.value()))
                .toList();
    }

    private static List<SearchResponsePayload.SearchLine> toSearchLines(WorldSearchQueryService.SearchResult searchResult) {
        return searchResult.records().stream()
                .map(record -> new SearchResponsePayload.SearchLine(record.translationKey(), record.args()))
                .toList();
    }

    private static List<GuiTableResponsePayload.QueryCell> toGuiCells(GuiTableQueryService.QueryPage queryPage) {
        return queryPage.cells().stream()
                .map(cell -> new GuiTableResponsePayload.QueryCell(cell.columnName(), cell.value()))
                .toList();
    }

    private static MutableComponent formatLine(SelectTableResponsePayload.QueryLine line) {
        if ("modify".equals(line.columnName())) {
            return formatModifyLine(line);
        }

        return Component.translatableWithFallback(columnTranslationKey(line.columnName()), line.columnName())
                .append(": ")
                .append(Component.literal(line.value()));
    }

    private static MutableComponent formatModifyLine(SelectTableResponsePayload.QueryLine line) {
        String[] parts = line.value().split("\t", -1);
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

    private static MutableComponent modifyTypeComponent(String modifyType) {
        return Component.translatableWithFallback(
                "text.worldlogger.modify_type." + normalizeModifyType(modifyType),
                modifyType
        );
    }

    private static String normalizeModifyType(String modifyType) {
        return modifyType.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", "_");
    }

    private static String columnTranslationKey(String columnName) {
        return "text.worldlogger.name." + columnName;
    }

    private static TableQueryService.QueryPage queryTable(String table, int page) {
        try {
            return TableQueryService.selectPage(table, page);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    private static WorldSearchQueryService.SearchResult searchNear(int centerX, int centerY, int centerZ, String worldId, int page) {
        try {
            return WorldSearchQueryService.searchNear(centerX, centerY, centerZ, worldId, page);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    private static GuiTableQueryService.QueryPage queryGuiTable(String table, int page, String filter) {
        try {
            return GuiTableQueryService.selectPage(table, page, filter);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    private static void sendToPlayer(ServerPlayer player, SelectTableResponsePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static void sendToPlayer(ServerPlayer player, SearchResponsePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static void sendToPlayer(ServerPlayer player, GuiTableResponsePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static MutableComponent nextPageComponent(SelectTableResponsePayload payload) {
        String command = "/worldlogger select " + payload.table() + " " + (payload.page() + 1);
        return Component.translatable("text.worldlogger.command.select.next")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    private static MutableComponent nextSearchPageComponent(SearchResponsePayload payload) {
        String command = "/worldlogger search " + (payload.page() + 1);
        return Component.translatable("text.worldlogger.command.select.next")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
    }

    private static String divider(String title) {
        return "----- " + title + " -----";
    }

    private static String pageDivider(String table, int page) {
        String header = divider(table);
        String label = " " + Component.translatableWithFallback("text.worldlogger.command.select.page", "Page %d", page).getString() + " ";
        int totalHyphens = Math.max(2, header.length() - label.length());
        int leftHyphens = totalHyphens / 2;
        int rightHyphens = totalHyphens - leftHyphens;
        return "-".repeat(leftHyphens) + label + "-".repeat(rightHyphens);
    }
}
