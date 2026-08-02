package com.glow.teammates.command;

import com.glow.teammates.config.GlowConfigManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Set;

public final class GlowCommand {

    private GlowCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("teamglow");

        // /teamglow on
        root.then(Commands.literal("on")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command/on"),
                        PermissionLevel.GAMEMASTERS))
                .executes(ctx -> setEnabled(ctx.getSource(), true)));

        // /teamglow off
        root.then(Commands.literal("off")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command/off"),
                        PermissionLevel.GAMEMASTERS))
                .executes(ctx -> setEnabled(ctx.getSource(), false)));

        // /teamglow status
        root.then(Commands.literal("status")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command/status"),
                        PermissionLevel.ALL))
                .executes(ctx -> showStatus(ctx.getSource())));

        // /teamglow team ...
        var teamNode = Commands.literal("team");

        // /teamglow team add <team>
        teamNode.then(Commands.literal("add")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command/team/add"),
                        PermissionLevel.GAMEMASTERS))
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
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command/team/remove"),
                        PermissionLevel.GAMEMASTERS))
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
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command/team/list"),
                        PermissionLevel.ALL))
                .executes(ctx -> listTeams(ctx.getSource())));

        root.then(teamNode);

        // Default (no argument) → show status (same permission as /teamglow status)
        root.requires(PermissionPredicates.require(
                Identifier.fromNamespaceAndPath(
                        "glow-my-teammates", "command/status"),
                PermissionLevel.ALL))
                .executes(ctx -> showStatus(ctx.getSource()));

        dispatcher.register(root);
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        config.setEnabled(enabled);
        if (!config.save()) {
            source.sendFailure(
                    Component.literal("Failed to save config — this change will be lost on restart.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        String state = enabled ? "enabled" : "disabled";
        source.sendSuccess(
                () -> Component.literal("Team glow " + state + ".")
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        Component state = Component.literal(config.isEnabled() ? "enabled" : "disabled")
                .withStyle(config.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED);
        Set<String> teams = config.getEnabledTeams();

        source.sendSuccess(
                () -> Component.literal("Team glow: ").withStyle(ChatFormatting.GOLD)
                        .append(state),
                false);

        if (teams.isEmpty()) {
            source.sendSuccess(
                    () -> Component.literal("No teams have glow enabled.")
                            .withStyle(ChatFormatting.GRAY),
                    false);
        } else {
            source.sendSuccess(
                    () -> Component.literal("Enabled teams: ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(String.join(", ", teams))
                                    .withStyle(ChatFormatting.WHITE)),
                    false);
        }
        return 1;
    }

    private static int addTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.literal("Team '" + teamName + "' already has glow enabled.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        config.addTeam(teamName);
        if (!config.save()) {
            source.sendFailure(
                    Component.literal("Failed to save config — this change will be lost on restart.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Team '" + teamName + "' now has glow enabled.")
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int removeTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (!config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.literal("Team '" + teamName + "' does not have glow enabled.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        config.removeTeam(teamName);
        if (!config.save()) {
            source.sendFailure(
                    Component.literal("Failed to save config — this change will be lost on restart.")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("Team '" + teamName + "' glow disabled.")
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int listTeams(CommandSourceStack source) {
        Set<String> teams = GlowConfigManager.getInstance().getEnabledTeams();

        if (teams.isEmpty()) {
            source.sendSuccess(
                    () -> Component.literal("No teams have glow enabled.")
                            .withStyle(ChatFormatting.GRAY),
                    false);
        } else {
            source.sendSuccess(
                    () -> Component.literal("Enabled teams: ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(String.join(", ", teams))
                                    .withStyle(ChatFormatting.WHITE)),
                    false);
        }
        return 1;
    }
}
