package me.znianxgang.glow.mixin;

import me.znianxgang.glow.config.GlowConfigManager;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Locator-bar filter, evaluated per receiver: a viewer in a glow-enabled team
 * sees only that team. Returning {@code Optional.empty()} makes vanilla tear
 * the connection down. Never called on the client.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @ModifyReturnValue(method = "makeWaypointConnectionWith", at = @At("RETURN"))
    private Optional<WaypointTransmitter.Connection> filterWaypointByTeam(
            Optional<WaypointTransmitter.Connection> original, ServerPlayer receiver) {

        GlowConfigManager config = GlowConfigManager.getInstance();
        if (!config.isEnabled() || !config.isLocatorBarTeammatesOnly()) {
            return original;
        }

        // Viewers outside glow-enabled teams (teamless or non-glow) see
        // everyone, like vanilla.
        PlayerTeam receiverTeam = receiver.getTeam();
        if (receiverTeam == null || !config.isTeamEnabled(receiverTeam.getName())) {
            return original;
        }

        // receiverTeam is glow-enabled, so equality already implies myTeam is
        // the same glow-enabled team — no second check needed.
        LivingEntity self = (LivingEntity) (Object) this;
        PlayerTeam myTeam = self.getTeam();
        if (!receiverTeam.equals(myTeam)) {
            return Optional.empty();
        }
        return original;
    }
}
