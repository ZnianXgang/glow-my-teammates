package com.glow.teammates.mixin;

import com.glow.teammates.config.GlowConfigManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    /** Tracks whether syncing was active last tick — used to detect ON→OFF transition. */
    @Unique
    private boolean wasSyncing;

    // ========== Force packet when nothing else is dirty ==========

    /**
     * {@code packDirty()} returns null when no entity data has changed.
     * For Player entities, inject the current shared flags when needed:
     * <ul>
     *   <li>Mod ON and no vanilla glow: always inject, so {@code @Redirect}
     *       can customize the glow flag per-client.</li>
     *   <li>Mod just turned OFF: inject once (cleanup packet to clear stale
     *       team glow from clients), then stop.</li>
     * </ul>
     */
    @ModifyVariable(
            method = "sendDirtyEntityData",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lnet/minecraft/network/syncher/SynchedEntityData;"
                            + "packDirty()Ljava/util/List;"
            ),
            ordinal = 0
    )
    private List<SynchedEntityData.DataValue<?>> ensurePacketForPlayers(
            List<SynchedEntityData.DataValue<?>> original) {

        if (original != null) {
            // Has dirty data — let normal flow handle it.
            this.wasSyncing = GlowConfigManager.getInstance().isEnabled();
            return original;
        }
        if (!(entity instanceof Player)) return null;

        boolean enabled = GlowConfigManager.getInstance().isEnabled();

        if (!enabled) {
            if (this.wasSyncing) {
                // ON → OFF transition: send one cleanup packet to clear team glow.
                this.wasSyncing = false;
                return buildFlagsPacket();
            }
            return null;
        }

        this.wasSyncing = true;

        // Don't interfere with vanilla glowing.
        if (entity instanceof LivingEntity living
                && living.hasEffect(MobEffects.GLOWING)) {
            return null;
        }

        return buildFlagsPacket();
    }

    private List<SynchedEntityData.DataValue<?>> buildFlagsPacket() {
        EntityDataAccessor<Byte> accessor = EntityAccessor.getSharedFlagsId();
        byte flags = entity.getEntityData().get(accessor);
        return List.of(new SynchedEntityData.DataValue<>(
                0, EntityDataSerializers.BYTE, flags));
    }

    // ========== Per-client glow customization ==========

    /**
     * Intercept the normal {@code sendToTrackingPlayersAndSelf} call inside
     * {@code sendDirtyEntityData()} to customize the glowing flag per-client.
     */
    @SuppressWarnings("unchecked")
    @Redirect(
            method = "sendDirtyEntityData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerEntity$Synchronizer;"
                            + "sendToTrackingPlayersAndSelf(Lnet/minecraft/network/protocol/Packet;)V"
            )
    )
    private void redirectSendData(
            ServerEntity.Synchronizer sync, Packet<?> rawPacket) {

        Packet<? super ClientGamePacketListener> packet =
                (Packet<? super ClientGamePacketListener>) rawPacket;

        if (!(packet instanceof ClientboundSetEntityDataPacket dataPacket)) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        if (!(entity instanceof Player entityPlayer)) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        if (!GlowConfigManager.getInstance().isEnabled()) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        if (entityPlayer instanceof LivingEntity living
                && living.hasEffect(MobEffects.GLOWING)) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        String entityName = entityPlayer.getScoreboardName();

        Predicate<ServerPlayer> isTeammate = viewer -> {
            Scoreboard scoreboard = viewer.level().getScoreboard();
            PlayerTeam entityTeamObj = scoreboard.getPlayersTeam(entityName);
            if (entityTeamObj == null) return false;
            if (!GlowConfigManager.getInstance().isTeamEnabled(
                    entityTeamObj.getName())) {
                return false;
            }
            PlayerTeam viewerTeam = scoreboard.getPlayersTeam(
                    viewer.getScoreboardName());
            return entityTeamObj.equals(viewerTeam);
        };

        ClientboundSetEntityDataPacket glowPacket = modifyGlowFlag(
                dataPacket, true);
        ClientboundSetEntityDataPacket noGlowPacket = modifyGlowFlag(
                dataPacket, false);

        //? if 26.2 {
        sync.sendToTrackingPlayersFiltered(glowPacket, isTeammate);
        sync.sendToTrackingPlayersFiltered(
                noGlowPacket, v -> !isTeammate.test(v));
//?} else {
        /*// 26.1: send no-glow to all, then manually override teammates
        sync.sendToTrackingPlayersAndSelf(noGlowPacket);
        for (var player : entityPlayer.level().players()) {
            if (player instanceof ServerPlayer sp
                    && sp != entityPlayer
                    && isTeammate.test(sp)) {
                sp.connection.send(glowPacket);
            }
        }
*///?}

        if (entityPlayer instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(noGlowPacket);
        }
    }

    // ========== Packet helper ==========

    /**
     * Create a copy of the packet with the glowing flag (bit 0x40) modified.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ClientboundSetEntityDataPacket modifyGlowFlag(
            ClientboundSetEntityDataPacket packet, boolean shouldGlow) {

        List<SynchedEntityData.DataValue<?>> items =
                new ArrayList<>(packet.packedItems());
        boolean foundFlag = false;

        for (int i = 0; i < items.size(); i++) {
            SynchedEntityData.DataValue<?> item = items.get(i);
            if (item.id() == 0 && item.value() instanceof Byte current) {
                byte newValue = (byte) (shouldGlow
                        ? (current | 0x40) : (current & ~0x40));
                if (newValue != current) {
                    SynchedEntityData.DataValue rawItem =
                            new SynchedEntityData.DataValue(
                                    0, item.serializer(), newValue);
                    items.set(i, rawItem);
                }
                foundFlag = true;
                break;
            }
        }

        if (!foundFlag && shouldGlow) {
            items.add(new SynchedEntityData.DataValue<>(
                    0, EntityDataSerializers.BYTE, (byte) 0x40));
        }

        return new ClientboundSetEntityDataPacket(packet.id(), items);
    }
}
