package me.znianxgang.glow;

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
 * Rebuilds locator-bar connections so the {@code locator_bar_teammates_only}
 * filter is re-evaluated.
 *
 * <p>The filter is decided by the <em>receiver</em>, but vanilla only rebuilds
 * the changed player as a <em>sender</em>. Rebuilding every connection
 * transmitted in the affected dimension covers both directions, players and
 * non-player transmitters alike — and a connection that never breaks (AFK
 * viewer, stationary mob) would otherwise keep the old rules forever.
 *
 * <p>Server thread only.
 */
public final class WaypointSync {
    private WaypointSync() {}

    /**
     * Dimensions awaiting a rebuild, drained at the next tick boundary. A mark
     * per dimension collapses a burst of membership changes and guarantees the
     * pass sees the final team state.
     */
    private static final Set<ServerLevel> pendingRebuilds =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Rounds one drain may run before deferring the rest to the next tick.
     * Bounds the work even if a rebuild keeps re-marking its own dimension, so
     * a pathological reentrancy cannot wedge the tick loop.
     */
    private static final int MAX_FLUSH_ROUNDS = 32;

    /**
     * Rebuild every connection in every dimension whose locator bar is on —
     * used when the filter rules themselves changed.
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
     * Iterates the waypoint manager's transmitter set, not the player list: a
     * non-player entity with a raised {@code WAYPOINT_TRANSMIT_RANGE} transmits
     * too, and vanilla never rebuilds those. The set is copied because
     * {@code remakeConnections} runs connection callbacks that may mutate it.
     */
    private static void rebuildLevel(ServerLevel level) {
        for (WaypointTransmitter transmitter
                : List.copyOf(level.getWaypointManager().transmitters())) {
            level.getWaypointManager().remakeConnections(transmitter);
        }
    }

    /**
     * Mark the affected member's dimension for the next drain. Non-player
     * members are resolved by UUID (an entity's scoreboard name <em>is</em> its
     * UUID), so a mob joining or leaving a team rebuilds like a player does.
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

    /** There is nothing to rebuild where no receiver can have connections. */
    private static void markLevel(ServerLevel level) {
        if (!level.getGameRules().get(GameRules.LOCATOR_BAR)) {
            return;
        }
        pendingRebuilds.add(level);
    }

    /**
     * Drain one dimension at a time rather than iterating-then-clearing: a
     * reentrant team change during a rebuild re-adds its level, and that mark
     * must survive the drain. Bounded by {@link #MAX_FLUSH_ROUNDS}; leftover
     * marks are deferred, never dropped (a rebuild is idempotent).
     */
    public static void flushPendingRebuilds(MinecraftServer server) {
        for (int round = 0; round < MAX_FLUSH_ROUNDS && !pendingRebuilds.isEmpty(); round++) {
            ServerLevel level = pendingRebuilds.iterator().next();
            pendingRebuilds.remove(level);
            // A server that died without SERVER_STOPPING leaves its dimensions
            // here; the integrated server may start again on the same JVM.
            if (!isPartOf(server, level)) {
                continue;
            }
            if (!level.getGameRules().get(GameRules.LOCATOR_BAR)) {
                continue; // The rule was turned off after the mark.
            }
            rebuildLevel(level);
        }
        // Not rate-limited on purpose: a stuck queue re-fills every tick, and
        // one warning per tick is exactly what "still happening" looks like.
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
     * {@code getAllLevels()} is an {@code Iterable}, so identity is checked
     * explicitly. Levels of a previous, crashed server are dropped this way.
     */
    private static boolean isPartOf(MinecraftServer server, ServerLevel level) {
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate == level) {
                return true;
            }
        }
        return false;
    }

    /** Drop the pending set so unloaded dimensions do not linger across restarts. */
    public static void clear() {
        pendingRebuilds.clear();
    }
}
