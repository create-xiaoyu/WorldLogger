package com.xiaoyu.worldlogger.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.xiaoyu.worldlogger.client.screen.WorldLoggerGuiScreen;
import com.xiaoyu.worldlogger.data.ListData;
import com.xiaoyu.worldlogger.network.payload.SearchRequestPayload;
import com.xiaoyu.worldlogger.network.payload.SelectTableRequestPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

public class MainCommand {
    @SubscribeEvent
    public static void registerSelectCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                    Commands.literal("worldlogger").then(
                        Commands.literal("select").requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN)).then(
                                Commands.argument("table", StringArgumentType.string())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(ListData.getTables(), builder))
                                        .executes(context -> sendSelectRequest(context.getSource(), StringArgumentType.getString(context, "table"), 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> sendSelectRequest(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "table"),
                                                        IntegerArgumentType.getInteger(context, "page")
                                                )))
                        )
                ).then(
                        Commands.literal("search")
                                .requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                                .executes(context -> sendSearchRequest(context.getSource(), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(context -> sendSearchRequest(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "page")
                                        )))
                ).then(
                        Commands.literal("gui")
                                .requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                                .executes(context -> openGui())
                )
        );
    }

    private static int sendSelectRequest(CommandSourceStack source, String value, int page) {
        String table = ListData.findTable(value);
        if (table == null) {
            source.sendSystemMessage(Component.translatable("text.worldlogger.command.worldlogger.select.no_equals"));
            return 0;
        }

        ClientPacketDistributor.sendToServer(new SelectTableRequestPayload(table, page));
        source.sendSystemMessage(Component.translatable("text.worldlogger.command.worldlogger.select.request", table, page));
        return 1;
    }

    private static int sendSearchRequest(CommandSourceStack source, int page) {
        ClientPacketDistributor.sendToServer(new SearchRequestPayload(page));
        source.sendSystemMessage(Component.translatable("text.worldlogger.command.worldlogger.search.request", page));
        return 1;
    }

    private static int openGui() {
        WorldLoggerGuiScreen.open();
        return 1;
    }
}
