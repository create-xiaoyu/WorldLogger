package com.xiaoyu.worldlogger.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import com.xiaoyu.worldlogger.data.ListData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

public class MainCommand {
    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void registerSelectCommand(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("worldlogger").then(
                        Commands.literal("select").requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN)).then(
                                Commands.argument("table", StringArgumentType.string()).suggests((context, builder) -> {
                                    for (String table : ListData.getTables()) {
                                        builder.suggest(table);
                                    }
                                    return builder.buildFuture();
                                }).executes(context -> {
                                    CommandSourceStack source = context.getSource();
                                    String vaule = StringArgumentType.getString(context, "table");
                                    boolean hasTable = false;
                                    for (String table : ListData.getTables()) {
                                        if (vaule.equals(table)) {
                                            hasTable = true;
                                            break;
                                        }
                                    }
                                    if (!(hasTable)) {
                                        source.sendSystemMessage(Component.translatable("text.worldlogger.command.worldlogger.select.no_equals"));
                                        return 0;
                                    }
                                    return 1;
                                })
                        )
                )
        );
    }
}
