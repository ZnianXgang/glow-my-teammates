package com.glow.teammates.command;

import com.glow.teammates.config.GlowConfigManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;

import java.util.Set;
import java.util.function.Consumer;

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

        // /teamglow config ...
        var configNode = Commands.literal("config")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command/config"),
                        PermissionLevel.GAMEMASTERS));

        // /teamglow config list
        configNode.then(Commands.literal("list")
                .executes(ctx -> listConfig(ctx.getSource())));

        // /teamglow config locator_bar_teammates_only <true|false>
        configNode.then(Commands.literal("locator_bar_teammates_only")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> setConfigSwitch(
                                ctx.getSource(),
                                "locator_bar_teammates_only",
                                BoolArgumentType.getBool(ctx, "value"),
                                GlowConfigManager.getInstance()::setLocatorBarTeammatesOnly))));

        // /teamglow config non_player_glow <true|false>
        configNode.then(Commands.literal("non_player_glow")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> setConfigSwitch(
                                ctx.getSource(),
                                "non_player_glow",
                                BoolArgumentType.getBool(ctx, "value"),
                                GlowConfigManager.getInstance()::setNonPlayerGlow))));

        root.then(configNode);

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
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        source.sendSuccess(
                () -> Component.translatable(
                        enabled ? "glow.teammates.enabled" : "glow.teammates.disabled")
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        Component state = Component.translatable(
                config.isEnabled() ? "glow.teammates.enabled" : "glow.teammates.disabled")
                .withStyle(config.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED);
        Set<String> teams = config.getEnabledTeams();

        source.sendSuccess(
                () -> Component.translatable("glow.teammates.status.header", state)
                        .withStyle(ChatFormatting.GOLD),
                false);

        if (teams.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("glow.teammates.no_teams")
                            .withStyle(ChatFormatting.GRAY),
                    false);
        } else {
            source.sendSuccess(
                    () -> Component.translatable("glow.teammates.teams_list",
                            Component.literal(String.join(", ", teams))
                                    .withStyle(ChatFormatting.WHITE))
                            .withStyle(ChatFormatting.YELLOW),
                    false);
        }
        return 1;
    }

    private static int addTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.translatable("glow.teammates.team_already", teamName)
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        config.addTeam(teamName);
        if (!config.save()) {
            source.sendFailure(
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        source.sendSuccess(
                () -> Component.translatable("glow.teammates.team_added", teamName)
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int removeTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (!config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.translatable("glow.teammates.team_not_enabled", teamName)
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        config.removeTeam(teamName);
        if (!config.save()) {
            source.sendFailure(
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        source.sendSuccess(
                () -> Component.translatable("glow.teammates.team_removed", teamName)
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int listTeams(CommandSourceStack source) {
        Set<String> teams = GlowConfigManager.getInstance().getEnabledTeams();

        if (teams.isEmpty()) {
            source.sendSuccess(
                    () -> Component.translatable("glow.teammates.no_teams")
                            .withStyle(ChatFormatting.GRAY),
                    false);
        } else {
            source.sendSuccess(
                    () -> Component.translatable("glow.teammates.teams_list",
                            Component.literal(String.join(", ", teams))
                                    .withStyle(ChatFormatting.WHITE))
                            .withStyle(ChatFormatting.YELLOW),
                    false);
        }
        return 1;
    }

    private static int listConfig(CommandSourceStack source) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        String info = "\n  locator_bar_teammates_only = " + config.isLocatorBarTeammatesOnly()
                + "\n  non_player_glow = " + config.isNonPlayerGlow();
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.config.list",
                        Component.literal(info).withStyle(ChatFormatting.WHITE))
                        .withStyle(ChatFormatting.YELLOW),
                false);
        return 1;
    }

    private static int setConfigSwitch(CommandSourceStack source, String feature,
                                       boolean value, Consumer<Boolean> setter) {
        setter.accept(value);
        if (!GlowConfigManager.getInstance().save()) {
            source.sendFailure(
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.config.set",
                        feature, String.valueOf(value))
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }
}
