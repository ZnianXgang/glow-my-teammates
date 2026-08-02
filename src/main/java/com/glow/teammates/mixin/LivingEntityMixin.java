package com.glow.teammates.mixin;

import com.glow.teammates.config.GlowConfigManager;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Filters the locator bar so that members of glow-enabled teams hide each
 * other (except their own team), while players outside glow-enabled teams
 * (teamless or in non-glow teams) see everyone.
 *
 * <p>Semantics (asymmetric, receiver-driven): when {@code this} (the entity
 * being displayed) belongs to a <em>different</em> glow-enabled team than the
 * {@code receiver} (the viewer), no waypoint connection is created — the
 * viewer's locator bar hides it. Same-team glow members, non-glow teams and
 * teamless players are always shown.
 *
 * <p>The locator bar connects are only created server-side by
 * {@code ServerWaypointManager.createConnection}, which invokes this method
 * and treats an {@link Optional#empty()} result as "no connection", so this
 * mixin is harmless on the client (the method is never called there).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @ModifyReturnValue(method = "makeWaypointConnectionWith", at = @At("RETURN"))
    private Optional<WaypointTransmitter.Connection> filterWaypointByTeam(
            Optional<WaypointTransmitter.Connection> original, ServerPlayer receiver) {

        GlowConfigManager config = GlowConfigManager.getInstance();
        if (!config.isEnabled() || !config.isLocatorBarHideOtherGlowingTeams()) {
            return original; // Mod off or feature off — vanilla behavior.
        }

        // A viewer who is not in a glow-enabled team (teamless or in a
        // non-glow team) sees everyone — vanilla behavior.
        PlayerTeam receiverTeam = receiver.getTeam();
        if (receiverTeam == null || !config.isTeamEnabled(receiverTeam.getName())) {
            return original;
        }

        // A viewer in a glow-enabled team hides members of OTHER glow-enabled
        // teams (treated as competitors); same-team, non-glow and teamless
        // entities stay visible.
        LivingEntity self = (LivingEntity) (Object) this;
        PlayerTeam myTeam = self.getTeam();
        if (myTeam != null && config.isTeamEnabled(myTeam.getName())
                && !myTeam.equals(receiverTeam)) {
            return Optional.empty();
        }
        return original;
    }
}
