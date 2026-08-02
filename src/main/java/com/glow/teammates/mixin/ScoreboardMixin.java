package com.glow.teammates.mixin;

import com.glow.teammates.config.GlowConfigManager;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks into Scoreboard team membership changes so that
 * {@link ServerEntityMixin#smartForcePacket} can detect when a
 * <em>viewer</em> (not the entity itself) changed teams and force
 * a glow-state resync accordingly.
 */
@Mixin(Scoreboard.class)
public abstract class ScoreboardMixin {

    /**
     * Bump the sync epoch only for changes involving a team with glow
     * enabled. Membership changes in other teams cannot affect any glow
     * display, so bumping for them would force a full-server resync of
     * every glowing entity for nothing (common with auto-team plugins).
     */
    @Unique
    private static void onTeamChange(PlayerTeam team) {
        GlowConfigManager config = GlowConfigManager.getInstance();
        if (config.isEnabled() && config.isTeamEnabled(team.getName())) {
            config.bumpSyncEpoch();
        }
    }

    @Inject(method = "addPlayerToTeam(Ljava/lang/String;"
            + "Lnet/minecraft/world/scores/PlayerTeam;)Z",
            at = @At("RETURN"))
    private void onAddPlayerToTeam(String playerName, PlayerTeam team,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            onTeamChange(team);
        }
    }

    /**
     * {@code removePlayerFromTeam(String)} is deliberately not hooked: when
     * it succeeds it internally calls the two-parameter overload
     * ({@code Scoreboard.removePlayerFromTeam(String, PlayerTeam)}), which is
     * covered by {@link #onRemovePlayerFromTeam(String, PlayerTeam, CallbackInfo)}
     * below — hooking both would double-bump.
     */
    /**
     * Deliberately unconditional: vanilla's two-arg
     * {@code removePlayerFromTeam(String, PlayerTeam)} is a silent no-op when
     * the player is not a member of the given team, and this hook still bumps
     * {@code syncEpoch} for it. Accepted on purpose — repeated no-op removals
     * inside one tick collapse into a single resync round because
     * {@code ServerEntityMixin#smartForcePacket} compares counters with
     * {@code !=}, and vanilla's own single-arg path never reaches here for
     * non-members. Do not "fix" without a real churn scenario.
     */
    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;"
            + "Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void onRemovePlayerFromTeam(String playerName, PlayerTeam team,
                                        CallbackInfo ci) {
        onTeamChange(team);
    }

    /**
     * Covers {@code /team remove <team>}: {@code Scoreboard.removePlayerTeam}
     * clears {@code teamsByPlayer} directly, bypassing both
     * {@code addPlayerToTeam} and {@code removePlayerFromTeam}, so without
     * this hook the syncEpoch would not bump and already-glowing viewers
     * would keep the stale glow bit indefinitely.
     */
    @Inject(method = "removePlayerTeam(Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void onRemovePlayerTeam(PlayerTeam team, CallbackInfo ci) {
        onTeamChange(team);
    }
}
