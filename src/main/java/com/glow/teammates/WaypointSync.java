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
 * <p>The filter is <em>receiver-driven</em> (decided by the receiver's team),
 * but vanilla's rebuilds only cover the changed player as a <em>sender</em>.
 * The receiver side — what the changed player sees on their own bar — is only
 * re-evaluated when a connection turns {@code isBroken()}, which may never
 * happen for an AFK player. {@link #rebuildForPlayer} closes that gap by
 * rebuilding every player-sent connection in the affected player's dimension,
 * covering both directions.
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
     * Rebuild every player-transmitted connection in every dimension — used
     * by the command paths ({@code /teamglow on|off}, team add/remove, config
     * toggles) where the filter rules themselves changed. Only player
     * transmitters are rebuilt (non-players don't transmit by default:
     * {@code WAYPOINT_TRANSMIT_RANGE} defaults to 0). Dimensions with the
     * locator-bar game rule off are skipped — no receiver can have connections.
     */
    public static void rebuildAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.getGameRules().get(GameRules.LOCATOR_BAR)) {
                continue;
            }
            for (ServerPlayer player : level.players()) {
                if (level.getWaypointManager().transmitters().contains(player)) {
                    level.getWaypointManager().remakeConnections(player);
                }
            }
        }
    }

    /**
     * Mark the affected player's dimension for a receiver-side rebuild at the
     * next tick boundary. Called from {@code ScoreboardMixin} on glow-enabled
     * team changes while {@code locator_bar_teammates_only} is on. Offline or
     * non-player members (mobs joined via their UUID string) are skipped —
     * they have no connections to rebuild.
     */
    public static void rebuildForPlayer(MinecraftServer server, String playerName) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            return;
        }
        ServerLevel level = player.level();
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
     */
    public static void flushPendingRebuilds(MinecraftServer server) {
        while (!pendingRebuilds.isEmpty()) {
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
            for (ServerPlayer other : level.players()) {
                if (level.getWaypointManager().transmitters().contains(other)) {
                    level.getWaypointManager().remakeConnections(other);
                }
            }
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
