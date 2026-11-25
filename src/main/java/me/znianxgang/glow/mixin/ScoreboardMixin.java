package me.znianxgang.glow.mixin;

import me.znianxgang.glow.WaypointSync;
import me.znianxgang.glow.config.GlowConfigManager;
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
 * Team-membership hooks: bump the viewer-side resync counter for glow-enabled
 * teams, and rebuild the affected members' locator-bar connections while the
 * filter is on.
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {

    /**
     * Membership churn in non-glow teams cannot change any glow display, so it
     * is ignored — auto-team plugins would otherwise resync the whole server
     * constantly. A team change is deferred to the tick boundary because
     * {@code addPlayerToTeam} removes-then-adds within one tick.
     *
     * <p>In singleplayer/LAN the client thread mutates the <em>client</em>
     * scoreboard while handling team packets; ignoring anything that is not the
     * running server's own scoreboard keeps the shared counters and
     * {@link WaypointSync}'s pending set on the server thread. The server
     * mutates its own scoreboard (firing this hook) before broadcasting, so
     * nothing is lost.
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
            for (String member : affectedPlayers) {
                WaypointSync.rebuildForMember(server, member);
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
     * hooked: it calls this overload internally, so hooking both would
     * double-bump. This overload throws for non-members, so RETURN only fires
     * for real removals.
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
     * Covers {@code /team remove}: it clears {@code teamsByPlayer} directly and
     * bypasses the hooks above. Assumes the team's member list is still intact
     * here (only the reverse index is cleared) — re-check on an MC upgrade, or
     * this silently stops rebuilding receiver-side connections.
     */
    @Inject(method = "removePlayerTeam(Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void onRemovePlayerTeam(PlayerTeam team, CallbackInfo ci) {
        onTeamChange((Scoreboard) (Object) this, team, new ArrayList<>(team.getPlayers()));
    }
}
