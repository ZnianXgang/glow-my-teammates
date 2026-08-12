package com.glow.teammates;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Rebuilds locator-bar waypoint connections so the
 * {@code locator_bar_teammates_only} filter is re-evaluated.
 *
 * <p>The filter in {@code LivingEntityMixin} is <em>receiver-driven</em>
 * ({@code makeWaypointConnectionWith(receiver)} decides by the receiver's
 * team), but vanilla's {@code remakeConnections(waypoint)} only rebuilds
 * connections <em>from one sender</em>, and vanilla team-change hooks
 * ({@code ServerScoreboard.updatePlayerWaypoint} /
 * {@code updateTeamWaypoints}) only cover the changed player as a
 * <em>sender</em>. The receiver side — what the changed player sees on their
 * own locator bar — is only re-evaluated when a connection turns
 * {@code isBroken()} (distance/chunk state changes), which may never happen
 * for an AFK player. {@link #rebuildForPlayer} closes that gap: it rebuilds
 * every player-sent connection in the affected player's dimension, which
 * covers both directions.
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
     * <p>Deferring (instead of rebuilding inline) both collapses a burst of
     * membership changes inside one tick into a single pass and guarantees
     * the pass sees the <em>final</em> team state:
     * {@code Scoreboard.addPlayerToTeam} removes-then-adds inside one tick,
     * so an inline rebuild on the remove hook would evaluate the switcher
     * mid-transition as teamless and leave their own locator bar showing
     * everyone until the next rebuild trigger.
     */
    private static final Set<ServerLevel> pendingRebuilds =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Rebuild every player-transmitted connection in every dimension.
     * Used by the command paths ({@code /teamglow on|off}, team add/remove,
     * config switch toggles) where the filter rules themselves changed.
     *
     * <p>Only player transmitters are rebuilt. Non-player entities do not
     * transmit waypoints by default ({@code WAYPOINT_TRANSMIT_RANGE} defaults
     * to 0), so this is sufficient unless a third-party mod raises that
     * attribute.
     *
     * <p>If the locator-bar game rule is off, no receiver can have
     * connections, so the whole dimension is skipped.
     */
    public static void rebuildAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.getGameRules().get(GameRules.LOCATOR_BAR).booleanValue()) {
                continue;
            }
            for (ServerPlayer player : level.players()) {
                level.getWaypointManager().remakeConnections(player);
            }
        }
    }

    /**
     * Mark the affected player's dimension for a receiver-side rebuild at
     * the next tick boundary: every player-sent connection in that player's
     * dimension, which re-evaluates what shows up on their locator bar (and,
     * redundantly with vanilla's sender-side rebuild, what other players see
     * of them).
     *
     * <p>Called from {@code ScoreboardMixin} when a player joins or leaves a
     * glow-enabled team while {@code locator_bar_teammates_only} is on.
     * Offline or non-player members (mobs joined via their UUID string) are
     * skipped — they have no connections to rebuild.
     */
    public static void rebuildForPlayer(MinecraftServer server, String playerName) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            return;
        }
        ServerLevel level = player.level();
        if (!level.getGameRules().get(GameRules.LOCATOR_BAR).booleanValue()) {
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
     */
    public static void flushPendingRebuilds() {
        while (!pendingRebuilds.isEmpty()) {
            ServerLevel level = pendingRebuilds.iterator().next();
            pendingRebuilds.remove(level);
            if (!level.getGameRules().get(GameRules.LOCATOR_BAR).booleanValue()) {
                continue; // The rule was turned off after the mark — nothing to rebuild.
            }
            for (ServerPlayer other : level.players()) {
                level.getWaypointManager().remakeConnections(other);
            }
        }
    }

    /**
     * Drop the pending-rebuild set. Called on {@code SERVER_STOPPING} so
     * unloaded dimension instances don't linger (an integrated server can
     * start and stop several times in one JVM).
     */
    public static void clear() {
        pendingRebuilds.clear();
    }
}
