package com.neovetta.darknights.command;

import com.mojang.brigadier.CommandDispatcher;
import com.neovetta.darknights.handler.WerewolfHandler;
import com.neovetta.darknights.handler.ZombieHandler;
import com.neovetta.darknights.saveddata.LycanthropySavedData;
import com.neovetta.darknights.saveddata.ZombieSavedData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import static net.minecraft.commands.Commands.literal;

public class DarkNightsCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(DarkNightsCommand::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext, Commands.CommandSelection selection) {
        dispatcher.register(
            literal("darknights")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(literal("transform")
                    .executes(ctx -> {
                        CommandSourceStack source = ctx.getSource();
                        MinecraftServer server = source.getServer();
                        ServerPlayer player = source.getPlayerOrException();
                        LycanthropySavedData data = LycanthropySavedData.get(server);
                        LycanthropySavedData.PlayerLycanthropy lyc = data.get(player.getUUID());

                        if (!lyc.isCursed()) {
                            data.setCursed(player.getUUID(), true);
                            source.sendSuccess(() ->
                                Component.literal("[darknights] Cursed player with lycanthropy.")
                                    .withStyle(ChatFormatting.GOLD), false);
                        }

                        if (!lyc.isTransformed()) {
                            WerewolfHandler.adminTransform(player);
                            source.sendSuccess(() ->
                                Component.literal("[darknights] Forced werewolf transform.")
                                    .withStyle(ChatFormatting.GOLD), false);
                        } else {
                            WerewolfHandler.adminRevert(player);
                            source.sendSuccess(() ->
                                Component.literal("[darknights] Reverted werewolf transform.")
                                    .withStyle(ChatFormatting.GOLD), false);
                        }
                        return 1;
                    })
                )
                .then(literal("curse")
                    .executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        LycanthropySavedData data = LycanthropySavedData.get(server);
                        data.setCursed(player.getUUID(), true);
                        ctx.getSource().sendSuccess(() ->
                            Component.literal("[darknights] Lycanthropy curse applied.")
                                .withStyle(ChatFormatting.GOLD), false);
                        return 1;
                    })
                )
                .then(literal("cleanse")
                    .executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        LycanthropySavedData data = LycanthropySavedData.get(server);
                        WerewolfHandler.adminRevert(player);
                        data.setCursed(player.getUUID(), false);
                        ctx.getSource().sendSuccess(() ->
                            Component.literal("[darknights] Lycanthropy cleansed.")
                                .withStyle(ChatFormatting.GOLD), false);
                        return 1;
                    })
                )
                .then(literal("plague")
                    .executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ZombieHandler.adminInfect(player);
                        ctx.getSource().sendSuccess(() ->
                            Component.literal("[darknights] Zombie plague applied.")
                                .withStyle(ChatFormatting.DARK_GREEN), false);
                        return 1;
                    })
                )
                .then(literal("cure")
                    .executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ZombieSavedData data = ZombieSavedData.get(server);
                        if (data.get(player.getUUID()).isCursed()) {
                            ZombieHandler.adminCure(player);
                            ctx.getSource().sendSuccess(() ->
                                Component.literal("[darknights] Zombie plague cured.")
                                    .withStyle(ChatFormatting.GREEN), false);
                        } else {
                            ctx.getSource().sendSuccess(() ->
                                Component.literal("[darknights] Player is not zombie-cursed.")
                                    .withStyle(ChatFormatting.GRAY), false);
                        }
                        return 1;
                    })
                )
        );
    }
}
