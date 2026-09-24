package me.znianxgang.glow.command;

import me.znianxgang.glow.GlowConstants;
import me.znianxgang.glow.WaypointSync;
import me.znianxgang.glow.config.GlowConfigManager;
import me.znianxgang.glow.mixin.EntityAccessor;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class GlowCommand {

    private GlowCommand() {}

    /**
     * Permission gates. A group gate sits on the {@code team} / {@code config}
     * literal, not on its children — Brigadier ANDs a parent's
     * {@code requires()} into every child, so per-subcommand gates would be
     * redundant. A group's read commands therefore inherit its write
     * permission ({@code team list} / {@code config list} / {@code config get}
     * are OP 2); {@code status} is the only public entry point.
     *
     * <p>The bare {@code /teamglow} shortcut checks {@link #STATUS_REQUIREMENT}
     * inside its executor rather than on the root node, because a root gate
     * would be ANDed into every subcommand too.
     */
    private static final Predicate<CommandSourceStack> STATUS_REQUIREMENT =
            permission("command.status", PermissionLevel.ALL);
    private static final Predicate<CommandSourceStack> TOGGLE_REQUIREMENT =
            permission("command.toggle", PermissionLevel.GAMEMASTERS);
    private static final Predicate<CommandSourceStack> TEAM_REQUIREMENT =
            permission("command.team", PermissionLevel.GAMEMASTERS);
    private static final Predicate<CommandSourceStack> CONFIG_REQUIREMENT =
            permission("command.config", PermissionLevel.GAMEMASTERS);

    private static Predicate<CommandSourceStack> permission(String path,
                                                            PermissionLevel fallback) {
        return PermissionPredicates.require(
                Identifier.fromNamespaceAndPath("glow-my-teammates", path), fallback);
    }

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

    /**
     * One feature switch: its command name, reset value, accessors and side
     * effect. The command tree, {@code config list} and {@code config reset}
     * all derive from this table — adding a switch is one constant here and
     * nothing else (the messages are generic).
     */
    private enum FeatureSwitch {
        LOCATOR_BAR_TEAMMATES_ONLY(
                "locator_bar_teammates_only",
                GlowConfigManager.DEFAULT_LOCATOR_BAR_TEAMMATES_ONLY,
                () -> GlowConfigManager.getInstance().isLocatorBarTeammatesOnly(),
                v -> GlowConfigManager.getInstance().setLocatorBarTeammatesOnly(v),
                SwitchEffect.REBUILD_WAYPOINTS),
        NON_PLAYER_GLOW(
                "non_player_glow",
                GlowConfigManager.DEFAULT_NON_PLAYER_GLOW,
                () -> GlowConfigManager.getInstance().isNonPlayerGlow(),
                v -> GlowConfigManager.getInstance().setNonPlayerGlow(v),
                SwitchEffect.CLEAR_NON_PLAYER_GLOW);

        private final String id;
        private final boolean defaultValue;
        private final BooleanSupplier reader;
        private final Consumer<Boolean> writer;
        private final SwitchEffect effect;

        FeatureSwitch(String id, boolean defaultValue, BooleanSupplier reader,
                      Consumer<Boolean> writer, SwitchEffect effect) {
            this.id = id;
            this.defaultValue = defaultValue;
            this.reader = reader;
            this.writer = writer;
            this.effect = effect;
        }

        boolean read() {
            return reader.getAsBoolean();
        }

        void write(boolean value) {
            writer.accept(value);
        }

        /**
         * Exact-match lookup, or {@code null} for an unknown name (the command
         * turns that into a failure message). Iterating {@code values()} beats
         * a static index: no enum-vs-static-field initialization-order trap,
         * and the table is tiny.
         */
        static FeatureSwitch byId(String id) {
            for (FeatureSwitch sw : values()) {
                if (sw.id.equals(id)) {
                    return sw;
                }
            }
            return null;
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("teamglow");

        // /teamglow toggle
        root.then(Commands.literal("toggle")
                .requires(TOGGLE_REQUIREMENT)
                .executes(ctx -> setEnabled(ctx.getSource(),
                        !GlowConfigManager.getInstance().isEnabled())));

        // /teamglow status
        root.then(Commands.literal("status")
                .requires(STATUS_REQUIREMENT)
                .executes(ctx -> showStatus(ctx.getSource())));

        // /teamglow team ... — every subcommand inherits TEAM_REQUIREMENT
        var teamNode = Commands.literal("team").requires(TEAM_REQUIREMENT);

        // /teamglow team add <team>
        teamNode.then(Commands.literal("add")
                .then(Commands.argument("team", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            // Suggest existing, not-yet-enabled teams narrowed by
                            // the typed prefix, exactly like vanilla's own team
                            // argument narrows /team add|remove as you type.
                            var server = ctx.getSource().getServer();
                            var enabled = GlowConfigManager.getInstance().getEnabledTeams();
                            List<String> candidates = new ArrayList<>();
                            for (var team : server.getScoreboard().getPlayerTeams()) {
                                if (!enabled.contains(team.getName())) {
                                    candidates.add(team.getName());
                                }
                            }
                            suggestMatchingTypedPrefix(builder, candidates);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            String team = StringArgumentType.getString(ctx, "team");
                            return addTeam(ctx.getSource(), team);
                        })));

        // /teamglow team remove <team>
        teamNode.then(Commands.literal("remove")
                .then(Commands.argument("team", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            // Suggest only enabled teams narrowed by the typed
                            // prefix (same behavior as the add subcommand).
                            suggestMatchingTypedPrefix(builder,
                                    GlowConfigManager.getInstance().getEnabledTeams());
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

        // /teamglow config ... — every subcommand inherits CONFIG_REQUIREMENT
        var configNode = Commands.literal("config").requires(CONFIG_REQUIREMENT);

        // /teamglow config list
        configNode.then(Commands.literal("list")
                .executes(ctx -> listConfig(ctx.getSource())));

        // /teamglow config get <switch>
        configNode.then(Commands.literal("get")
                .then(Commands.argument("switch", StringArgumentType.word())
                        .suggests(GlowCommand::suggestSwitches)
                        .executes(ctx -> getConfig(ctx.getSource(),
                                StringArgumentType.getString(ctx, "switch")))));

        // /teamglow config set <switch> <true|false>
        configNode.then(Commands.literal("set")
                .then(Commands.argument("switch", StringArgumentType.word())
                        .suggests(GlowCommand::suggestSwitches)
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> setConfig(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "switch"),
                                        BoolArgumentType.getBool(ctx, "value"))))));

        // /teamglow config reset <switch>
        configNode.then(Commands.literal("reset")
                .then(Commands.argument("switch", StringArgumentType.word())
                        .suggests(GlowCommand::suggestSwitches)
                        .executes(ctx -> resetConfig(ctx.getSource(),
                                StringArgumentType.getString(ctx, "switch")))));

        root.then(configNode);

        // Default (no argument) → show status. The permission check lives in
        // the executor (not on the root node) because Brigadier ANDs a parent
        // node's requires() into every child — a status restriction on the
        // root would wrongly gate the toggle/team/config subcommands too.
        root.executes(ctx -> {
            if (!STATUS_REQUIREMENT.test(ctx.getSource())) {
                ctx.getSource().sendFailure(
                        Component.translatable("glow.teammates.permission.denied")
                                .withStyle(ChatFormatting.RED));
                return 0;
            }
            return showStatus(ctx.getSource());
        });

        dispatcher.register(root);
    }

    /**
     * Suggest the {@code candidates} whose name starts with whatever the player
     * has typed so far. Vanilla narrows its own argument suggestions by that
     * prefix (case-insensitively), so {@code /teamglow team add/remove} must do
     * the same or its completion list would stop following the player's input
     * while vanilla's own team argument keeps narrowing.
     */
    private static void suggestMatchingTypedPrefix(SuggestionsBuilder builder,
                                                   Iterable<String> candidates) {
        String typed = builder.getRemainingLowerCase();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
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
                    Component.translatable("glow.teammates.save.failed")
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
                        enabled ? "glow.teammates.toggle.enabled" : "glow.teammates.toggle.disabled")
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    /**
     * Reports the global on/off state and nothing else — team and switch
     * details live behind {@code team list} / {@code config list} / {@code
     * config get} (all OP 2), so the public {@code status} node never exposes
     * them.
     */
    private static int showStatus(CommandSourceStack source) {
        boolean enabled = GlowConfigManager.getInstance().isEnabled();
        Component state = Component.translatable(
                enabled ? "glow.teammates.status.enabled"
                        : "glow.teammates.status.disabled")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED);

        source.sendSuccess(
                () -> Component.translatable("glow.teammates.status.header", state)
                        .withStyle(ChatFormatting.GOLD),
                false);
        return 1;
    }

    private static int addTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.translatable("glow.teammates.team.already", teamName)
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
                    Component.translatable("glow.teammates.save.failed")
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
                () -> Component.translatable("glow.teammates.team.added", teamName)
                        .withStyle(ChatFormatting.GREEN),
                true);
        if (!exists) {
            // Pre-configuring a team that does not exist yet is allowed, but
            // the admin should know glow only applies once it is created.
            source.sendSuccess(
                    () -> Component.translatable("glow.teammates.team.not_found", teamName)
                            .withStyle(ChatFormatting.GOLD),
                    false);
        }
        return 1;
    }

    private static int removeTeam(CommandSourceStack source, String teamName) {
        GlowConfigManager config = GlowConfigManager.getInstance();

        if (!config.isTeamEnabled(teamName)) {
            source.sendFailure(
                    Component.translatable("glow.teammates.team.not_enabled", teamName)
                            .withStyle(ChatFormatting.RED));
            return 0;
        }

        config.removeTeam(teamName);
        if (!config.save()) {
            config.addTeam(teamName); // Roll back the in-memory state.
            source.sendFailure(
                    Component.translatable("glow.teammates.save.failed")
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
                () -> Component.translatable("glow.teammates.team.removed", teamName)
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
                    () -> Component.translatable("glow.teammates.team.list.empty")
                            .withStyle(ChatFormatting.GRAY),
                    false);
        } else {
            source.sendSuccess(
                    () -> Component.translatable("glow.teammates.team.list",
                            Component.literal(String.join(", ", teams))
                                    .withStyle(ChatFormatting.WHITE))
                            .withStyle(ChatFormatting.YELLOW),
                    false);
        }
    }

    private static int listConfig(CommandSourceStack source) {
        StringBuilder info = new StringBuilder();
        for (FeatureSwitch sw : FeatureSwitch.values()) {
            info.append("\n  ").append(sw.id).append(": ").append(sw.read());
        }
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.config.list",
                        Component.literal(info.toString()).withStyle(ChatFormatting.WHITE))
                        .withStyle(ChatFormatting.YELLOW),
                false);
        return 1;
    }

    private static int getConfig(CommandSourceStack source, String id) {
        FeatureSwitch sw = FeatureSwitch.byId(id);
        if (sw == null) {
            return unknownSwitch(source, id);
        }
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.config.entry",
                        sw.id, String.valueOf(sw.read()))
                        .withStyle(ChatFormatting.YELLOW),
                false);
        return 1;
    }

    private static int setConfig(CommandSourceStack source, String id, boolean value) {
        FeatureSwitch sw = FeatureSwitch.byId(id);
        if (sw == null) {
            return unknownSwitch(source, id);
        }
        return applySwitch(source, sw, value, "glow.teammates.config.set");
    }

    private static int resetConfig(CommandSourceStack source, String id) {
        FeatureSwitch sw = FeatureSwitch.byId(id);
        if (sw == null) {
            return unknownSwitch(source, id);
        }
        return applySwitch(source, sw, sw.defaultValue, "glow.teammates.config.reset");
    }

    /**
     * The single exit for switch mutations: apply, persist, roll back on a
     * failed save, then run the switch's side effect. {@code set} and {@code
     * reset} differ only in the value and the message key.
     */
    private static int applySwitch(CommandSourceStack source, FeatureSwitch sw,
                                   boolean value, String messageKey) {
        boolean oldValue = sw.read();
        boolean waypointsAffected = sw.effect == SwitchEffect.REBUILD_WAYPOINTS
                && oldValue != value;
        boolean glowCleared = sw.effect == SwitchEffect.CLEAR_NON_PLAYER_GLOW
                && oldValue && !value;
        sw.write(value);
        if (!GlowConfigManager.getInstance().save()) {
            sw.write(oldValue); // Roll back the in-memory state.
            source.sendFailure(
                    Component.translatable("glow.teammates.save.failed")
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
                () -> Component.translatable(messageKey, sw.id, String.valueOf(value))
                        .withStyle(ChatFormatting.GREEN),
                true);
        return 1;
    }

    private static int unknownSwitch(CommandSourceStack source, String id) {
        source.sendFailure(
                Component.translatable("glow.teammates.config.unknown", id)
                        .withStyle(ChatFormatting.RED));
        return 0;
    }

    /** Suggests every switch name, narrowed by the typed prefix (like teams). */
    private static CompletableFuture<Suggestions> suggestSwitches(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        List<String> ids = new ArrayList<>();
        for (FeatureSwitch sw : FeatureSwitch.values()) {
            ids.add(sw.id);
        }
        suggestMatchingTypedPrefix(builder, ids);
        return builder.buildFuture();
    }

    /**
     * Rebuild every locator-bar connection so the
     * {@code locator_bar_teammates_only} filter takes effect immediately —
     * the same re-evaluation {@code ScoreboardMixin} applies to individual
     * members on team changes, here applied everywhere because the rules
     * themselves changed.
     */
    private static void rebuildWaypointConnections(MinecraftServer server) {
        WaypointSync.rebuildAll(server);
    }

    /**
     * Clear the mod-overlaid glow on every non-player entity by broadcasting
     * a no-glow entity-data packet. Required because a stationary mob never
     * produces dirty entity data, so the stale 0x40 bit would otherwise stay
     * on clients forever. Vanilla-glowing entities are skipped (the mod never
     * touched those). Packets go only to players whose chunk-tracking view
     * covers the entity's chunk — a <em>superset</em> of the exact tracking
     * set (it ignores {@code entityTrackingRange}), so a few non-tracking
     * players may receive a redundant clear packet; over-sending is safe
     * (under-sending would leave stale glow). One-shot at command frequency —
     * the chunk → tracking-players map keeps per-entity work at a single hash
     * lookup (DEVELOPMENT.md §9.3).
     */
    private static void clearNonPlayerGlow(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            // Build a chunk → tracking-players map once instead of calling
            // ChunkMap.getPlayers (O(online players) per call) for every entity.
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
                // Vanilla glow (effect for living, glow-tag bit for the rest)
                // must be left alone — otherwise the clear packet would
                // wrongly extinguish it.
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
                                // GLOW_CLEAR_MASK clears the glow bit, keeping
                                // every other shared flag intact.
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
