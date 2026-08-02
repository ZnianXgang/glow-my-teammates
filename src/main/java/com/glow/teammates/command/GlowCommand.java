package com.glow.teammates.command;

import com.glow.teammates.config.GlowConfigManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Set;

public final class GlowCommand {

    private GlowCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("teamglow");

        // /teamglow on
        root.then(Commands.literal("on")
                .requires(s -> Commands.LEVEL_GAMEMASTERS.check(s.permissions()))
                .executes(ctx -> setEnabled(ctx.getSource(), true)));

        // /teamglow off
        root.then(Commands.literal("off")
                .requires(s -> Commands.LEVEL_GAMEMASTERS.check(s.permissions()))
                .executes(ctx -> setEnabled(ctx.getSource(), false)));

        // /teamglow status
        root.then(Commands.literal("status")
                .executes(ctx -> showStatus(ctx.getSource())));

        // /teamglow team ...
        var teamNode = Commands.literal("team");

        // /teamglow team add <team>
        teamNode.then(Commands.literal("add")
                .requires(s -> Commands.LEVEL_GAMEMASTERS.check(s.permissions()))
                .then(Commands.argument("team", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            // Suggest all existing teams from scoreboard
                            var server = ctx.getSource().getServer();
                            if (server != null) {
                                var enabled = GlowConfigManager.getInstance().getEnabledTeams();
                                for (var team : server.getScoreboard().getPlayerTeams()) {
                                    if (!enabled.contains(team.getName())) {
                                        builder.suggest(team.getName());
                                    }
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String team = StringArgumentType.getString(ctx, "team");
                            return addTeam(ctx.getSource(), team);
                        })));

        // /teamglow team remove <team>
        teamNode.then(Commands.literal("remove")
                .requires(s -> Commands.LEVEL_GAMEMASTERS.check(s.permissions()))
                .then(Commands.argument("team", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            // Suggest only enabled teams
                            for (String t : GlowConfigManager.getInstance().getEnabledTeams()) {
                                builder.suggest(t);
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String team = StringArgumentType.getString(ctx, "team");
                            return removeTeam(ctx.getSource(), team);
                        })));

        // /teamglow team list
        teamNode.then(Commands.literal("list")
                .executes(ctx -> listTeams(ctx.getSource())));

        root.then(teamNode);

        // Default (no argument) → show status
        root.executes(ctx -> showStatus(ctx.getSource()));

        dispatcher.register(root);
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        config.setEnabled(enabled);
        if (!config.save()) {
            source.sendFailure(
                    Component.literal("§cFailed to save config — this change will be lost on restart."));
            return 0;
        }

        String state = enabled ? "enabled" : "disabled";
        source.sendSuccess(
                () -> Component.literal("§aTeam glow " + state + "."),
                true);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        String state = config.isEnabled() ? "§aenabled" : "§cdisabled";
        Set<String> teams = config.getEnabledTeams();

        source.sendSuccess(
                () -> Component.literal("§6Team glow: " + state), false);

        if (teams.isEmpty()) {
            source.sendSuccess(
                    () -> Component.literal("§7No teams have glow enabled."), false);
        } else {
            source.sendSuccess(
                    () -> Component.literal("§eEnabled teams: §f" + String.join(", ", teams)),
                    false);
        }
        return 1;
    }

    private static int addTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.literal("§cTeam '" + teamName + "' already has glow enabled."));
            return 0;
        }

        config.addTeam(teamName);
        if (!config.save()) {
            source.sendFailure(
                    Component.literal("§cFailed to save config — this change will be lost on restart."));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("§aTeam '" + teamName + "' now has glow enabled."),
                true);
        return 1;
    }

    private static int removeTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (!config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.literal("§cTeam '" + teamName + "' does not have glow enabled."));
            return 0;
        }

        config.removeTeam(teamName);
        if (!config.save()) {
            source.sendFailure(
                    Component.literal("§cFailed to save config — this change will be lost on restart."));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("§aTeam '" + teamName + "' glow disabled."),
                true);
        return 1;
    }

    private static int listTeams(CommandSourceStack source) {
        Set<String> teams = GlowConfigManager.getInstance().getEnabledTeams();

        if (teams.isEmpty()) {
            source.sendSuccess(
                    () -> Component.literal("§7No teams have glow enabled."),
                    false);
        } else {
            source.sendSuccess(
                    () -> Component.literal("§eEnabled teams: §f" + String.join(", ", teams)),
                    false);
        }
        return 1;
    }
}
