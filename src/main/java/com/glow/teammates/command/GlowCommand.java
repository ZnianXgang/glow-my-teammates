package com.glow.teammates.command;

import com.glow.teammates.GlowConstants;
import com.glow.teammates.WaypointSync;
import com.glow.teammates.config.GlowConfigManager;
import com.glow.teammates.mixin.EntityAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.scores.PlayerTeam;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class GlowCommand {

    private GlowCommand() {}

    /**
     * Permission gate for the bare {@code /teamglow} shortcut, checked inside
     * the executor (not on the root node) so subcommands don't inherit it —
     * Brigadier ANDs a parent's {@code requires()} into every child.
     */
    private static final Predicate<CommandSourceStack> STATUS_REQUIREMENT =
            PermissionPredicates.require(
                    Identifier.fromNamespaceAndPath(
                            "glow-my-teammates", "command.status"),
                    PermissionLevel.ALL);

    /**
     * Side effects a config-switch change may trigger beyond persisting the
     * value. Named constants keep the call sites readable — a raw boolean
     * pair is easy to transpose and would silently wire the wrong side
     * effect to a switch.
     */
    private enum SwitchEffect {
        /** Re-evaluate every locator-bar connection when the switch changes. */
        REBUILD_WAYPOINTS,
        /** Clear mod-overlaid glow on non-player entities when the switch turns off. */
        CLEAR_NON_PLAYER_GLOW
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("teamglow");

        // /teamglow on
        root.then(Commands.literal("on")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command.on"),
                        PermissionLevel.GAMEMASTERS))
                .executes(ctx -> setEnabled(ctx.getSource(), true)));

        // /teamglow off
        root.then(Commands.literal("off")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command.off"),
                        PermissionLevel.GAMEMASTERS))
                .executes(ctx -> setEnabled(ctx.getSource(), false)));

        // /teamglow status
        root.then(Commands.literal("status")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command.status"),
                        PermissionLevel.ALL))
                .executes(ctx -> showStatus(ctx.getSource())));

        // /teamglow team ...
        var teamNode = Commands.literal("team");

        // /teamglow team add <team>
        teamNode.then(Commands.literal("add")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command.team.add"),
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
                                "glow-my-teammates", "command.team.remove"),
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
                                "glow-my-teammates", "command.team.list"),
                        PermissionLevel.ALL))
                .executes(ctx -> listTeams(ctx.getSource())));

        root.then(teamNode);

        // /teamglow config ... (no argument → show current feature switches)
        var configNode = Commands.literal("config")
                .requires(PermissionPredicates.require(
                        Identifier.fromNamespaceAndPath(
                                "glow-my-teammates", "command.config"),
                        PermissionLevel.GAMEMASTERS))
                .executes(ctx -> listConfig(ctx.getSource()));

        // /teamglow config locator_bar_teammates_only <true|false>
        configNode.then(Commands.literal("locator_bar_teammates_only")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> setConfigSwitch(
                                ctx.getSource(),
                                "locator_bar_teammates_only",
                                BoolArgumentType.getBool(ctx, "value"),
                                () -> GlowConfigManager.getInstance().isLocatorBarTeammatesOnly(),
                                GlowConfigManager.getInstance()::setLocatorBarTeammatesOnly,
                                SwitchEffect.REBUILD_WAYPOINTS))));

        // /teamglow config non_player_glow <true|false>
        configNode.then(Commands.literal("non_player_glow")
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(ctx -> setConfigSwitch(
                                ctx.getSource(),
                                "non_player_glow",
                                BoolArgumentType.getBool(ctx, "value"),
                                () -> GlowConfigManager.getInstance().isNonPlayerGlow(),
                                GlowConfigManager.getInstance()::setNonPlayerGlow,
                                SwitchEffect.CLEAR_NON_PLAYER_GLOW))));

        root.then(configNode);

        // Default (no argument) → show status. The permission check lives in
        // the executor (not on the root node) because Brigadier ANDs a parent
        // node's requires() into every child — a status restriction on the
        // root would wrongly gate the on/off/team.config subcommands too.
        root.executes(ctx -> {
            if (!STATUS_REQUIREMENT.test(ctx.getSource())) {
                ctx.getSource().sendFailure(
                        Component.translatable("glow.teammates.no_permission")
                                .withStyle(ChatFormatting.RED));
                return 0;
            }
            return showStatus(ctx.getSource());
        });

        dispatcher.register(root);
    }

    private static int setEnabled(CommandSourceStack source, boolean enabled) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        boolean oldValue = config.isEnabled();
        boolean waypointsAffected = config.isLocatorBarTeammatesOnly()
                && oldValue != enabled;
        config.setEnabled(enabled);
        if (!config.save()) {
            config.setEnabled(oldValue); // Roll back the in-memory state.
            source.sendFailure(
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        // The locator-bar filter also keys off isEnabled(), so rebuild the
        // connections when the mod is turned on or off — the filter must
        // apply/lift immediately, not on the next team change.
        if (waypointsAffected) {
            rebuildWaypointConnections(source.getServer());
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
                config.isEnabled() ? "glow.teammates.status.enabled"
                        : "glow.teammates.status.disabled")
                .withStyle(config.isEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED);

        source.sendSuccess(
                () -> Component.translatable("glow.teammates.status.header", state)
                        .withStyle(ChatFormatting.GOLD),
                false);

        sendTeamsList(source, config.getEnabledTeams());
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

        // getPlayerTeam (teamsByName) checks whether the TEAM exists —
        // getPlayersTeam (teamsByPlayer) would look up a member by that name.
        boolean exists = source.getServer().getScoreboard()
                .getPlayerTeam(teamName) != null;
        config.addTeam(teamName);
        if (!config.save()) {
            config.removeTeam(teamName); // Roll back the in-memory state.
            source.sendFailure(
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Team glow eligibility feeds the locator-bar filter — rebuild the
        // waypoint connections so already-established ones are re-evaluated
        // immediately instead of lingering under the old rules. Skipped while
        // the mod is off: the filter is inert then (LivingEntityMixin's first
        // guard passes everything through), and toggling the mod back on
        // rebuilds via setEnabled anyway.
        if (config.isEnabled() && config.isLocatorBarTeammatesOnly()) {
            rebuildWaypointConnections(source.getServer());
        }
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.team_added", teamName)
                        .withStyle(ChatFormatting.GREEN),
                true);
        if (!exists) {
            // Pre-configuring a team that does not exist yet is allowed, but
            // the admin should know glow only applies once it is created.
            source.sendSuccess(
                    () -> Component.translatable("glow.teammates.team_not_found", teamName)
                            .withStyle(ChatFormatting.GOLD),
                    false);
        }
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
            config.addTeam(teamName); // Roll back the in-memory state.
            source.sendFailure(
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        // Same re-evaluation as addTeam: removing a team from the glow config
        // must let existing (previously filtered) locator-bar connections
        // appear right away. Skipped while the mod is off — same reasoning as
        // addTeam.
        if (config.isEnabled() && config.isLocatorBarTeammatesOnly()) {
            rebuildWaypointConnections(source.getServer());
        }
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.team_removed", teamName)
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int listTeams(CommandSourceStack source) {
        sendTeamsList(source, GlowConfigManager.getInstance().getEnabledTeams());
        return 1;
    }

    private static void sendTeamsList(CommandSourceStack source, Set<String> teams) {
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
    }

    private static int listConfig(CommandSourceStack source) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        String info = "\n  locator_bar_teammates_only = "
                + config.isLocatorBarTeammatesOnly()
                + "\n  non_player_glow = " + config.isNonPlayerGlow();
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.config.list",
                        Component.literal(info).withStyle(ChatFormatting.WHITE))
                        .withStyle(ChatFormatting.YELLOW),
                false);
        return 1;
    }

    private static int setConfigSwitch(CommandSourceStack source, String feature,
                                       boolean value, BooleanSupplier oldValueGetter,
                                       Consumer<Boolean> setter,
                                       SwitchEffect effect) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        boolean oldValue = oldValueGetter.getAsBoolean();
        boolean waypointsAffected = effect == SwitchEffect.REBUILD_WAYPOINTS
                && oldValue != value;
        boolean glowCleared = effect == SwitchEffect.CLEAR_NON_PLAYER_GLOW
                && oldValue && !value;
        setter.accept(value);
        if (!config.save()) {
            setter.accept(oldValue); // Roll back the in-memory state.
            source.sendFailure(
                    Component.translatable("glow.teammates.save_failed")
                            .withStyle(ChatFormatting.RED));
            return 0;
        }
        if (waypointsAffected) {
            rebuildWaypointConnections(source.getServer());
        }
        if (glowCleared) {
            clearNonPlayerGlow(source.getServer());
        }
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.config.set",
                        feature, String.valueOf(value))
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    /**
     * Rebuild every locator-bar waypoint connection so the
     * {@code locator_bar_teammates_only} filter takes effect
     * immediately. Delegates to {@link WaypointSync#rebuildAll} — the same
     * re-evaluation that {@code ScoreboardMixin} applies to individual
     * players on team changes, here applied everywhere because the filter
     * rules themselves changed.
     */
    private static void rebuildWaypointConnections(MinecraftServer server) {
        WaypointSync.rebuildAll(server);
    }

    /**
     * Clear the mod-overlaid glow on every non-player entity by broadcasting
     * a no-glow entity-data packet. Turning the {@code non_player_glow} switch
     * off must also stop already-glowing mobs: a stationary mob never produces
     * dirty entity data, so without this it would keep the stale 0x40 bit on
     * clients forever.
     *
     * <p>Entities that glow for vanilla reasons are skipped — the mod never
     * touched those: {@code isCurrentlyGlowing()} for living entities (effect
     * or glow tag), and the same shared-flags bit checked directly for
     * non-living entities (which have no effect, only the tag). Packets go
     * only to players whose chunk-tracking view covers the entity's chunk.
     *
     * <p>One-shot at command frequency; the chunk → tracking-players map keeps
     * the per-entity work at a single hash lookup. See AGENTS.md §10.3.
     */
    private static void clearNonPlayerGlow(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            // Build a chunk → tracking-players map once instead of calling
            // ChunkMap.getPlayers (O(online players) per call) for every
            // entity — this is one O(players × tracked chunks) pass plus
            // O(entities) hash lookups.
            Map<ChunkPos, List<ServerPlayer>> chunkViewers = new HashMap<>();
            for (ServerPlayer player : level.players()) {
                player.getChunkTrackingView().forEach(chunk -> {
                    List<ServerPlayer> viewers = chunkViewers.computeIfAbsent(
                            chunk, c -> new ArrayList<>());
                    viewers.add(player);
                });
            }
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Player) {
                    continue;
                }
                // Vanilla glow must be left alone: LivingEntity.isCurrentlyGlowing()
                // covers effect + glow tag; non-living entities can only carry the
                // glow tag (same shared-flags bit), so check it directly —
                // otherwise the clear packet would wrongly extinguish it.
                boolean vanillaGlow = entity instanceof LivingEntity living
                        ? living.isCurrentlyGlowing()
                        : (entity.getEntityData().get(EntityAccessor.getSharedFlagsId())
                                & GlowConstants.FLAG_GLOWING) != 0;
                if (vanillaGlow) {
                    continue; // Vanilla glow — the mod never overlaid these.
                }
                PlayerTeam team = entity.getTeam();
                if (team == null
                        || !GlowConfigManager.getInstance().isTeamEnabled(team.getName())) {
                    continue; // Never had mod-overlaid glow.
                }
                List<ServerPlayer> tracking = chunkViewers.get(entity.chunkPosition());
                if (tracking == null) {
                    continue;
                }
                byte flags = entity.getEntityData().get(EntityAccessor.getSharedFlagsId());
                List<SynchedEntityData.DataValue<?>> items = List.of(
                        new SynchedEntityData.DataValue<>(
                                EntityAccessor.getSharedFlagsId().id(),
                                EntityDataSerializers.BYTE,
                                // GlowConstants.GLOW_CLEAR_MASK clears the glow
                                // bit, keeping every other shared flag intact.
                                (byte) (flags & GlowConstants.GLOW_CLEAR_MASK)));
                ClientboundSetEntityDataPacket packet =
                        new ClientboundSetEntityDataPacket(entity.getId(), items);
                for (ServerPlayer player : tracking) {
                    if (player.connection != null) {
                        player.connection.send(packet);
                    }
                }
            }
        }
    }
}
