package me.znianxgang.glow.command;

import me.znianxgang.glow.GlowConstants;
import me.znianxgang.glow.WaypointSync;
import me.znianxgang.glow.config.GlowConfigManager;
import me.znianxgang.glow.mixin.EntityAccessor;
import com.mojang.brigadier.CommandDispatcher;
//? if 26.4 {
import net.minecraft.commands.arguments.BooleanArgument;
import net.minecraft.commands.arguments.StringArgument;
import net.minecraft.commands.suggestions.ServerOnlyCustomSuggestion;
import net.minecraft.commands.suggestions.SuggestLiteralString;
//?} else {
/*import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
*///?}
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class GlowCommand {

    private GlowCommand() {}

    /**
     * Permission gates. A group gate sits on the {@code team} / {@code config}
     * literal because Brigadier ANDs a parent's {@code requires()} into every
     * child — so {@code team list} / {@code config list} inherit OP 2 too. The
     * bare {@code /teamglow} checks its own gate in the executor, since a root
     * gate would apply to every subcommand.
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

    /** Side effects a switch change may trigger beyond persisting the value. */
    private enum SwitchEffect {
        /** Re-evaluate every locator-bar connection when the switch changes. */
        REBUILD_WAYPOINTS,
        /** Clear mod-overlaid glow on non-player entities when the switch turns off. */
        CLEAR_NON_PLAYER_GLOW
    }

    /**
     * One feature switch. The command tree, {@code config list} and
     * {@code config reset} derive from this table, so adding a switch is one
     * constant here and nothing else.
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

        /** Exact match, or {@code null} for an unknown name. */
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
                .then(Commands.argument("team", wordArgument())
                        .suggests(teamAddSuggestions())
                        .executes(ctx -> {
                            String team = ctx.getArgument("team", String.class);
                            return addTeam(ctx.getSource(), team);
                        })));

        // /teamglow team remove <team>
        teamNode.then(Commands.literal("remove")
                .then(Commands.argument("team", wordArgument())
                        .suggests(teamRemoveSuggestions())
                        .executes(ctx -> {
                            String team = ctx.getArgument("team", String.class);
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
                .then(Commands.argument("switch", wordArgument())
                        .suggests(switchSuggestions())
                        .executes(ctx -> getConfig(ctx.getSource(),
                                ctx.getArgument("switch", String.class)))));

        // /teamglow config set <switch> <true|false>
        configNode.then(Commands.literal("set")
                .then(Commands.argument("switch", wordArgument())
                        .suggests(switchSuggestions())
                        .then(Commands.argument("value", boolArgument())
                                .executes(ctx -> setConfig(ctx.getSource(),
                                        ctx.getArgument("switch", String.class),
                                        ctx.getArgument("value", Boolean.class))))));

        // /teamglow config reset <switch>
        configNode.then(Commands.literal("reset")
                .then(Commands.argument("switch", wordArgument())
                        .suggests(switchSuggestions())
                        .executes(ctx -> resetConfig(ctx.getSource(),
                                ctx.getArgument("switch", String.class)))));

        root.then(configNode);

        // Default (no argument) → show status. The gate lives in the executor:
        // a gate on the root node would apply to every subcommand.
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

    /** Narrow {@code candidates} by the typed prefix, like vanilla's own team argument. */
    private static void suggestMatchingTypedPrefix(SuggestionsBuilder builder,
                                                   Iterable<String> candidates) {
        String typed = builder.getRemainingLowerCase();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
    }

    // ========== Argument types & suggestions (version-gated) ==========
    //
    // 26.4 converts the whole dispatcher through SuggestionBox.fromFullTree
    // when Commands is constructed, and only accepts serializable nodes: an
    // argument type must be a SerializableArgument wrapper, a suggestion
    // provider a CustomSuggestion wrapper. A raw Brigadier type or a bare
    // lambda aborts Commands.createVanilla with
    // "UnsupportedOperationException: Invalid argument type: string()", which
    // kills every world load. The getters are not gated — a wrapper still
    // stores a plain String/Boolean, so ctx.getArgument works on all versions.

    /** {@code <team>} / {@code <switch>}: a single unquoted word. */
    private static ArgumentType<String> wordArgument() {
        //? if 26.4 {
        return StringArgument.word();
        //?} else {
        /*return StringArgumentType.word();
        *///?}
    }

    /** {@code <true|false>} for {@code config set}. */
    private static ArgumentType<Boolean> boolArgument() {
        //? if 26.4 {
        return BooleanArgument.bool();
        //?} else {
        /*return BoolArgumentType.bool();
        *///?}
    }

    /** Teams that are not glow-enabled yet — the {@code team add} candidates. */
    private static List<String> candidateTeamsToAdd(MinecraftServer server) {
        Set<String> enabled = GlowConfigManager.getInstance().getEnabledTeams();
        List<String> candidates = new ArrayList<>();
        for (PlayerTeam team : server.getScoreboard().getPlayerTeams()) {
            if (!enabled.contains(team.getName())) {
                candidates.add(team.getName());
            }
        }
        return candidates;
    }

    /** Switch ids in declaration order; static, so 26.4 can ship them to the client. */
    private static String[] featureSwitchIds() {
        FeatureSwitch[] values = FeatureSwitch.values();
        String[] ids = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            ids[i] = values[i].id;
        }
        return ids;
    }

    /**
     * The candidates are runtime state, so 26.4 uses a
     * {@link ServerOnlyCustomSuggestion}: its {@code serializable()} defaults to
     * {@code AskServer.INSTANCE}, which makes the client ask the server.
     */
    private static SuggestionProvider<CommandSourceStack> teamAddSuggestions() {
        //? if 26.4 {
        ServerOnlyCustomSuggestion suggestion = (server, ctx, builder) -> {
            suggestMatchingTypedPrefix(builder, candidateTeamsToAdd(server));
            return builder.buildFuture();
        };
        return suggestion.toBrigadier();
        //?} else {
        /*return (ctx, builder) -> {
            suggestMatchingTypedPrefix(builder,
                    candidateTeamsToAdd(ctx.getSource().getServer()));
            return builder.buildFuture();
        };
        *///?}
    }

    /** {@code /teamglow team remove} suggestions — enabled teams only. */
    private static SuggestionProvider<CommandSourceStack> teamRemoveSuggestions() {
        //? if 26.4 {
        ServerOnlyCustomSuggestion suggestion = (server, ctx, builder) -> {
            suggestMatchingTypedPrefix(builder,
                    GlowConfigManager.getInstance().getEnabledTeams());
            return builder.buildFuture();
        };
        return suggestion.toBrigadier();
        //?} else {
        /*return (ctx, builder) -> {
            suggestMatchingTypedPrefix(builder,
                    GlowConfigManager.getInstance().getEnabledTeams());
            return builder.buildFuture();
        };
        *///?}
    }

    /** The ids never change, so 26.4 serializes them into the command tree. */
    private static SuggestionProvider<CommandSourceStack> switchSuggestions() {
        //? if 26.4 {
        return SuggestLiteralString.suggestLiteral(featureSwitchIds());
        //?} else {
        /*return (ctx, builder) -> {
            suggestMatchingTypedPrefix(builder, List.of(featureSwitchIds()));
            return builder.buildFuture();
        };
        *///?}
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
        // The filter also keys off isEnabled(), so rebuild now — it must
        // apply or lift immediately, not on the next team change.
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
     * Reports the global on/off state only: team and switch details stay behind
     * the OP 2 commands, so the public node never exposes them.
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

        // getPlayerTeam checks whether the TEAM exists; getPlayersTeam would
        // look up a member by that name.
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

        // Eligibility feeds the locator-bar filter, so re-evaluate existing
        // connections. Skipped while the mod is off: the filter is inert, and
        // enabling it rebuilds anyway.
        if (config.isEnabled() && config.isLocatorBarTeammatesOnly()) {
            rebuildWaypointConnections(source.getServer());
        }
        source.sendSuccess(
                () -> Component.translatable("glow.teammates.team.added", teamName)
                        .withStyle(ChatFormatting.GREEN),
                true);
        if (!exists) {
            // Pre-configuring a missing team is allowed; the admin should know
            // glow only applies once it exists.
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

        // Same as addTeam: removing a team must let previously filtered
        // connections reappear immediately.
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
     * Single exit for switch mutations: apply, persist, roll back on a failed
     * save, then run the switch's side effect.
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

    /** Re-evaluate every connection, because the filter rules themselves changed. */
    private static void rebuildWaypointConnections(MinecraftServer server) {
        WaypointSync.rebuildAll(server);
    }

    /**
     * Clear mod-overlaid glow on every non-player entity by broadcasting a
     * no-glow packet: a stationary mob produces no dirty data, so the stale bit
     * would otherwise stay on clients. Vanilla-glowing entities are skipped.
     *
     * <p>Recipients are the entity's chunk-tracking viewers, a superset of the
     * exact tracking set — over-sending is safe, under-sending is not.
     */
    private static void clearNonPlayerGlow(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            // One chunk → viewers map beats ChunkMap.getPlayers per entity.
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
                boolean vanillaGlow = entity instanceof LivingEntity living
                        ? living.isCurrentlyGlowing()
                        : (entity.getEntityData().get(EntityAccessor.getSharedFlagsId())
                                & GlowConstants.FLAG_GLOWING) != 0;
                if (vanillaGlow) {
                    continue;
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
