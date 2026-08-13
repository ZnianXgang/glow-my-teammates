package com.glow.teammates.mixin;

import com.glow.teammates.WaypointSync;
import com.glow.teammates.config.GlowConfigManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Collections;

/**
 * Hooks into Scoreboard team membership changes so that
 * {@link ServerEntityMixin#smartForcePacket} can detect when a
 * <em>viewer</em> (not the entity itself) changed teams and force
 * a glow-state resync accordingly.
 *
 * <p>When {@code locator_bar_teammates_only} is on, team changes involving
 * a glow-enabled team also rebuild the affected player's locator-bar
 * connections — see {@link WaypointSync#rebuildForPlayer} for why the
 * receiver side needs this (vanilla only rebuilds the sender side).
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {

    /**
     * Bump the sync epoch only for changes involving a team with glow
     * enabled. Membership changes in other teams cannot affect any glow
     * display, so bumping for them would force a full-server resync of
     * every glowing entity for nothing (common with auto-team plugins).
     *
     * <p>When the locator-bar filter is on, the same change also marks the
     * affected players' waypoint connections for a rebuild at the next tick
     * boundary so the filter applies to their own locator bar without
     * waiting for connections to turn {@code isBroken()} (which may never
     * happen while AFK). The rebuild is deferred — see
     * {@link WaypointSync#rebuildForPlayer} for why an inline rebuild would
     * evaluate a team switcher mid-transition as teamless.
     *
     * <p><strong>Client-scoreboard guard:</strong> the mixin also applies to
     * the <em>client</em> scoreboard — in singleplayer/LAN the integrated
     * server shares this JVM and this config singleton, and
     * {@code ClientPacketListener} mutates the client scoreboard from the
     * client thread while processing team packets. Acting on those calls
     * would bump {@code syncEpoch} off the server thread and write to
     * {@code WaypointSync}'s pending-rebuild set while the server thread
     * drains it — a data race that can corrupt the set and crash or hang the
     * tick loop. The server broadcasts team packets only <em>after</em>
     * mutating its own {@code ServerScoreboard} (which fires this hook on
     * the server thread first), so ignoring every scoreboard that is not the
     * server's own on the server thread loses nothing.
     */
    @Unique
    private static void onTeamChange(Scoreboard scoreboard, PlayerTeam team,
                                     Collection<String> affectedPlayers) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        if (!config.isEnabled() || !config.isTeamEnabled(team.getName())) {
            return;
        }
        MinecraftServer server = config.getServer();
        if (server == null || !server.isSameThread()
                || scoreboard != server.getScoreboard()) {
            return;
        }
        config.bumpSyncEpoch();
        if (config.isLocatorBarTeammatesOnly()) {
            for (String player : affectedPlayers) {
                WaypointSync.rebuildForPlayer(server, player);
            }
        }
    }

    @Inject(method = "addPlayerToTeam(Ljava/lang/String;"
            + "Lnet/minecraft/world/scores/PlayerTeam;)Z",
            at = @At("RETURN"))
    private void onAddPlayerToTeam(String playerName, PlayerTeam team,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            onTeamChange((Scoreboard) (Object) this, team,
                    Collections.singletonList(playerName));
        }
    }

    /**
     * {@code removePlayerFromTeam(String)} is deliberately not hooked: when
     * it succeeds it internally calls the two-parameter overload
     * ({@code Scoreboard.removePlayerFromTeam(String, PlayerTeam)}), which is
     * covered by {@link #onRemovePlayerFromTeam(String, PlayerTeam, CallbackInfo)}
     * below — hooking both would double-bump.
     *
     * <p>Vanilla's two-arg overload <em>throws</em> {@code IllegalStateException}
     * (it does not no-op) when the player is not a member of the given team —
     * the exception propagates before the RETURN point, so this hook only ever
     * fires for real removals. Hooked unconditionally because real removals
     * always need the bump; a burst of removals inside one tick still collapses
     * into a single resync round since {@code ServerEntityMixin#smartForcePacket}
     * compares counters with {@code !=} (and the deferred waypoint rebuild
     * drains once per tick at the boundary).
     */
    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;"
            + "Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void onRemovePlayerFromTeam(String playerName, PlayerTeam team,
                                        CallbackInfo ci) {
        onTeamChange((Scoreboard) (Object) this, team,
                Collections.singletonList(playerName));
    }

    /**
     * Covers {@code /team remove <team>}: {@code Scoreboard.removePlayerTeam}
     * clears {@code teamsByPlayer} directly, bypassing both
     * {@code addPlayerToTeam} and {@code removePlayerFromTeam}, so without
     * this hook the syncEpoch would not bump and already-glowing viewers
     * would keep the stale glow bit indefinitely.
     *
     * <p>Vanilla's {@code ServerScoreboard.onTeamRemoved} rebuilds each
     * member's sender-side connections; the receiver side is covered here by
     * passing the whole member list. {@code team.getPlayers()} still holds
     * the members at this point — {@code removePlayerTeam} only clears
     * {@code teamsByPlayer}, it never empties the team's own player set.
     */
    @Inject(method = "removePlayerTeam(Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void onRemovePlayerTeam(PlayerTeam team, CallbackInfo ci) {
        onTeamChange((Scoreboard) (Object) this, team, team.getPlayers());
    }
}
