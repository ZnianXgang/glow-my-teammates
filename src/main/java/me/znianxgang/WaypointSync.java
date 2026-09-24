package me.znianxgang;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.waypoints.WaypointTransmitter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Rebuilds locator-bar waypoint connections so the
 * {@code locator_bar_teammates_only} filter is re-evaluated.
 *
 * <p>The filter is <em>receiver-driven</em> (decided by the receiver's team),
 * but vanilla's rebuilds only cover the changed player as a <em>sender</em>.
 * The receiver side — what the changed player sees on their own bar — is only
 * re-evaluated when a connection turns {@code isBroken()}, which may never
 * happen for an AFK player. {@link #rebuildForMember} closes that gap by
 * rebuilding every connection transmitted in the affected member's dimension,
 * covering both directions — players and non-players alike, since a
 * non-player transmitter's connections are exactly the ones vanilla's
 * player-list rebuilds drop.
 *
 * <p>All methods run on the server thread (command execution and scoreboard
 * events).
 */
public final class WaypointSync {
    private WaypointSync() {}

    /**
     * Dimensions whose connections need a rebuild for a team change, drained
     * once at the next tick boundary (see {@link #flushPendingRebuilds}).
     *
     * <p>Deferring collapses a burst of membership changes into a single pass
     * and guarantees the pass sees the <em>final</em> team state:
     * {@code addPlayerToTeam} removes-then-adds inside one tick, so an inline
     * rebuild on the remove hook would evaluate a team switcher mid-transition
     * as teamless.
     */
    private static final Set<ServerLevel> pendingRebuilds =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Ceiling on the level-drain rounds one {@link #flushPendingRebuilds} call
     * may run before deferring the rest to the next tick.
     *
     * <p>The drain loop has to tolerate reentrancy (a rebuild can trigger a
     * team change, which re-marks a level), but no loop with an unbounded
     * iteration count belongs on the tick path: if a rebuild re-marks a level
     * on <em>every</em> pass — an external mod rewriting team membership from
     * inside its own connection callbacks, which vanilla never does — it would
     * spin here forever and wedge the tick loop instead of merely being slow.
     *
     * <p>Rounds, not dimensions: one round drains exactly one mark, so the
     * budget covers the burst that accumulated <em>before</em> this drain (one
     * mark per affected dimension, plus whatever the command paths marked)
     * before it covers anything reentrant. 32 is far above a realistic
     * multi-dimension burst and any real reentrancy depth, while still bounding
     * the work this hook may do in a single tick.
     */
    private static final int MAX_FLUSH_ROUNDS = 32;

    /**
     * Rebuild every transmitted connection in every dimension — used by the
     * command paths ({@code /teamglow toggle}, team add/remove, config
     * toggles) where the filter rules themselves changed. Dimensions with the
     * locator-bar game rule off are skipped — no receiver can have connections.
     */
    public static void rebuildAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.getGameRules().get(GameRules.LOCATOR_BAR)) {
                continue;
            }
            rebuildLevel(level);
        }
    }

    /**
     * Rebuild every connection transmitted from {@code level}.
     *
     * <p>The iteration source is the waypoint manager's transmitter set, not
     * {@code level.players()}: a non-player {@code LivingEntity} whose
     * {@code WAYPOINT_TRANSMIT_RANGE} was raised above 0 is a transmitter too
     * (vanilla defaults the attribute to 0, but a command, a datapack or an
     * equipment modifier can change that), and its connections need the same
     * re-evaluation. Iterating the player list would leave those connections
     * on the old filter rules until something happened to break them — which
     * may never happen for a stationary entity and an AFK viewer, exactly the
     * case this class exists for.
     *
     * <p>The set is copied before iterating: {@code remakeConnections} runs
     * connection callbacks, and one of them mutating the transmitter set would
     * otherwise corrupt this loop. Copying also covers the
     * {@code transmitters().contains} pre-check the player-only version needed
     * — the set is the exact, already-filtered work list.
     */
    private static void rebuildLevel(ServerLevel level) {
        for (WaypointTransmitter transmitter
                : List.copyOf(level.getWaypointManager().transmitters())) {
            level.getWaypointManager().remakeConnections(transmitter);
        }
    }

    /**
     * Mark the affected member's dimension for a rebuild at the next tick
     * boundary. Called from {@code ScoreboardMixin} on glow-enabled team
     * changes while {@code locator_bar_teammates_only} is on.
     *
     * <p>Players resolve through the player list. A non-player member (a mob
     * joined via its UUID string, since {@code Entity.getScoreboardName()} is
     * the entity UUID) is resolved as a UUID against every dimension's entity
     * lookup instead of being skipped: it can be a waypoint transmitter in its
     * own right, so its transmitted connections need the same re-evaluation.
     * Offline players and names that are neither a player nor a live entity
     * have nothing to rebuild.
     */
    public static void rebuildForMember(MinecraftServer server, String memberName) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(memberName);
        if (player != null) {
            markLevel(player.level());
            return;
        }
        UUID uuid = parseUuid(memberName);
        if (uuid == null) {
            return; // An offline player's name is never a UUID string.
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) != null) {
                markLevel(level);
                return;
            }
        }
    }

    /** {@code null} unless {@code name} is a well-formed UUID string. */
    private static UUID parseUuid(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Queue {@code level} for the next drain, unless the locator bar is off
     * there — no receiver can have connections, so there is nothing to rebuild.
     */
    private static void markLevel(ServerLevel level) {
        if (!level.getGameRules().get(GameRules.LOCATOR_BAR)) {
            return;
        }
        pendingRebuilds.add(level);
    }

    /**
     * Drain the pending rebuilds. Called on
     * {@code ServerTickEvents.END_SERVER_TICK}: by then the whole tick's
     * membership changes are final, so one pass per dimension re-evaluates
     * every connection against the settled team state.
     *
     * <p>Drains one level at a time instead of iterating-then-clearing: a
     * reentrant team change during the rebuild re-adds the level, and the
     * drain loop keeps it pending (a live for-each plus a trailing
     * {@code clear()} would either drop the re-added mark or throw).
     *
     * <p>The drain is bounded by {@link #MAX_FLUSH_ROUNDS}, so a burst that
     * keeps re-marking levels cannot spin here forever. Breaking out is safe:
     * the drained marks are gone and each round rebuilds against the team state
     * as of that round, so whatever re-marked a level has already been acted
     * on. Any mark left behind stays pending and is drained at the next tick
     * boundary, and a rebuild is idempotent, so re-running one is wasted work
     * but never wrong.
     */
    public static void flushPendingRebuilds(MinecraftServer server) {
        for (int round = 0; round < MAX_FLUSH_ROUNDS && !pendingRebuilds.isEmpty(); round++) {
            ServerLevel level = pendingRebuilds.iterator().next();
            pendingRebuilds.remove(level);
            // Crash-recovery guard: a server that died without firing
            // SERVER_STOPPING (OOM, JVM error) leaves its dimensions in the
            // pending set, and the integrated server may start again on the
            // same JVM and drain them here. Never touch a level that is not
            // part of the running server — see isPartOf.
            if (!isPartOf(server, level)) {
                continue;
            }
            if (!level.getGameRules().get(GameRules.LOCATOR_BAR)) {
                continue; // The rule was turned off after the mark — nothing to rebuild.
            }
            rebuildLevel(level);
        }
        // Out of budget with work still queued. The remaining marks are NOT
        // dropped — they stay pending for the next tick — so this is a
        // diagnostic, not a data loss. The message names the possible causes
        // rather than asserting one: a large multi-dimension burst spends the
        // budget just as legitimately as a rebuild that keeps re-marking its
        // level, so blaming reentrancy outright would misreport the former.
        //
        // Deliberately NOT rate-limited: a stuck queue re-fills every tick
        // while the condition lasts, so one warning per tick is what "this is
        // still happening" looks like, and muting it would hide that. The flood
        // is the alarm, and it only exists while something else is badly wrong.
        if (!pendingRebuilds.isEmpty()) {
            GlowMyTeammates.LOGGER.warn(
                    "Locator-bar rebuild drain hit its {}-round budget for one tick with {} "
                            + "dimension(s) still pending; the remainder is deferred to the next "
                            + "tick (large multi-dimension burst, or team membership being changed "
                            + "during the rebuild).",
                    MAX_FLUSH_ROUNDS, pendingRebuilds.size());
        }
    }

    /**
     * Whether {@code level} is one of the dimensions of the running server.
     * {@code MinecraftServer.getAllLevels()} returns an {@code Iterable},
     * so identity is checked with an explicit loop rather than
     * {@code contains}. Drops stale entries from the pending set by
     * returning {@code false} for levels of a previous, crashed server.
     */
    private static boolean isPartOf(MinecraftServer server, ServerLevel level) {
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate == level) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drop the pending-rebuild set. Called on {@code SERVER_STOPPING} so
     * unloaded dimensions don't linger across integrated-server restarts.
     */
    public static void clear() {
        pendingRebuilds.clear();
    }
}
