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
 * Filters the locator bar (asymmetric, receiver-driven): a viewer in a
 * glow-enabled team sees only their own teammates; everyone else (teamless or
 * non-glow teams) sees everyone. Returning {@link Optional#empty()} makes
 * {@code ServerWaypointManager.createConnection} tear the connection down.
 * Harmless on the client — the method is never called there.
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

        // A viewer outside glow-enabled teams (teamless or non-glow) sees
        // everyone — vanilla behavior.
        PlayerTeam receiverTeam = receiver.getTeam();
        if (receiverTeam == null || !config.isTeamEnabled(receiverTeam.getName())) {
            return original;
        }

        // A viewer in a glow-enabled team sees ONLY its own teammates: every
        // other entity is hidden. Since receiverTeam is glow-enabled,
        // equals() implies myTeam is the same glow-enabled team — no further
        // check needed.
        LivingEntity self = (LivingEntity) (Object) this;
        PlayerTeam myTeam = self.getTeam();
        if (!receiverTeam.equals(myTeam)) {
            return Optional.empty();
        }
        return original;
    }
}
