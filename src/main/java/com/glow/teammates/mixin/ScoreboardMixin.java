package com.glow.teammates.mixin;

import com.glow.teammates.config.GlowConfigManager;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
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

    @Inject(method = "addPlayerToTeam",
            at = @At("RETURN"))
    private void onAddPlayerToTeam(String playerName, PlayerTeam team,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            GlowConfigManager.getInstance().bumpSyncEpoch();
        }
    }

    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;)Z",
            at = @At("RETURN"))
    private void onRemovePlayerFromTeam(String playerName,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            GlowConfigManager.getInstance().bumpSyncEpoch();
        }
    }

    /**
     * Covers {@code /team remove <team>} and plugin API calls that use
     * the internal two-parameter overload directly.
     * Double-bumping syncEpoch on {@code /team leave} is harmless —
     * epoch comparison only checks equality, not magnitude.
     */
    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;"
            + "Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = @At("RETURN"))
    private void onRemovePlayerFromTeam(String playerName, PlayerTeam team,
                                        CallbackInfo ci) {
        GlowConfigManager.getInstance().bumpSyncEpoch();
    }
}
