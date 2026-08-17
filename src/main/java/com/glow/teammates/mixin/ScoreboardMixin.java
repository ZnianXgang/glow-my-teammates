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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Hooks into Scoreboard team membership changes so that
 * {@link ServerEntityMixin#smartForcePacket} can detect when a
 * <em>viewer</em> (not the entity itself) changed teams and force a
 * glow-state resync. When {@code locator_bar_teammates_only} is on, the same
 * change rebuilds the affected players' locator-bar connections — see
 * {@link WaypointSync#rebuildForPlayer} for why the receiver side needs
 * this (vanilla only rebuilds the sender side).
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {

    /**
     * Bump the sync epoch only for glow-enabled team changes — membership
     * churn in other teams cannot affect any glow display (auto-team plugins
     * churn constantly). With the locator-bar filter on, the same change
     * marks the affected players' dimension for a deferred waypoint rebuild
     * at the tick boundary (an inline rebuild would evaluate a team switcher
     * mid-transition as teamless).
     *
     * <p><strong>Client-scoreboard guard:</strong> in singleplayer/LAN the
     * client thread mutates the <em>client</em> scoreboard while processing
     * team packets — acting on those calls would bump {@code syncEpoch} and
     * write to {@code WaypointSync}'s pending set off the server thread, a
     * data race that can corrupt the set and crash or hang the tick loop.
     * The server broadcasts team packets only <em>after</em> mutating its
     * own scoreboard (which fires this hook on the server thread first), so
     * ignoring every other scoreboard loses nothing.
     */
    @Unique
    private static void onTeamChange(Scoreboard scoreboard, PlayerTeam team,
                                     Collection<String> affectedPlayers) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        MinecraftServer server = config.getServer();
        if (server == null || !server.isSameThread()
                || scoreboard != server.getScoreboard()) {
            return;
        }
        if (!config.isEnabled() || !config.isTeamEnabled(team.getName())) {
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
     * The single-arg {@code removePlayerFromTeam(String)} is deliberately not
     * hooked: on success it internally calls this two-arg overload — hooking
     * both would double-bump. The two-arg overload <em>throws</em>
     * {@code IllegalStateException} for non-members (it does not no-op), so
     * the RETURN hook only fires for real removals; a burst of removals inside
     * one tick still collapses into one resync round ({@code !=} comparison
     * plus one drain per tick).
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
     * Covers {@code /team remove <team>}: {@code removePlayerTeam} clears
     * {@code teamsByPlayer} directly, bypassing the two hooks above — without
     * this, the syncEpoch would not bump and already-glowing viewers would
     * keep the stale bit indefinitely. Vanilla's {@code onTeamRemoved}
     * rebuilds each member's sender-side connections; the receiver side is
     * covered here via the whole member list.
     *
     * <p><strong>MC-upgrade check:</strong> this assumes {@code removePlayerTeam}
     * leaves {@code team.getPlayers()} intact (it only clears
     * {@code teamsByPlayer}). If a future version empties the member set before
     * returning, this hook silently stops rebuilding receiver-side connections.
     * The list is copied so {@code onTeamChange} never iterates a live view.
     */
    @Inject(method = "removePlayerTeam(Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void onRemovePlayerTeam(PlayerTeam team, CallbackInfo ci) {
        onTeamChange((Scoreboard) (Object) this, team, new ArrayList<>(team.getPlayers()));
    }
}
