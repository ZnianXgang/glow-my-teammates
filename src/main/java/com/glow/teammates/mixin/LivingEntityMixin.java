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
 * Filters the locator bar so that a viewer in a glow-enabled team sees only
 * their own teammates, while players outside glow-enabled teams (teamless or
 * in non-glow teams) see everyone.
 *
 * <p>Semantics (asymmetric, receiver-driven): when {@code this} (the entity
 * being displayed) is not on the {@code receiver}'s (the viewer's) team, no
 * waypoint connection is created — the viewer's locator bar hides it. Only
 * same-team members are shown to a viewer in a glow-enabled team; members of
 * other teams (glow-enabled or not) and teamless entities are hidden.
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
        if (!config.isEnabled() || !config.isLocatorBarTeammatesOnly()) {
            return original; // Mod off or feature off — vanilla behavior.
        }

        // A viewer who is not in a glow-enabled team (teamless or in a
        // non-glow team) sees everyone — vanilla behavior.
        PlayerTeam receiverTeam = receiver.getTeam();
        if (receiverTeam == null || !config.isTeamEnabled(receiverTeam.getName())) {
            return original;
        }

        // A viewer in a glow-enabled team sees ONLY its own teammates: every
        // other entity — members of other teams (glow-enabled or not) and
        // teamless entities — is hidden from the locator bar. Since
        // receiverTeam is glow-enabled, equals() implies myTeam is the same
        // (glow-enabled) team, so no further team check is needed.
        LivingEntity self = (LivingEntity) (Object) this;
        PlayerTeam myTeam = self.getTeam();
        if (!receiverTeam.equals(myTeam)) {
            return Optional.empty();
        }
        return original;
    }
}
