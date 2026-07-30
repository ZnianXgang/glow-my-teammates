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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    /**
     * Cached team name at last glow sync — used to detect team membership changes
     * without polling the scoreboard every tick. {@code null} means not yet synced
     * (forces a packet on first encounter).
     */
    @Unique
    private String cachedTeamName;

    /**
     * Cached {@link GlowConfigManager#getVersion()} at last glow sync.
     * Detects config changes (e.g. {@code /teamglow team add/remove}).
     */
    @Unique
    private long cachedConfigVersion;

    /**
     * Cached {@link GlowConfigManager#getSyncEpoch()} at last glow sync.
     * Detects viewer-side team membership changes — when a player joins
     * or leaves a scoreboard team, all glowing entities force a resync
     * so that affected viewers see the correct glow state immediately.
     */
    @Unique
    private long cachedSyncEpoch;

    // ========== Smart force: only when team membership or config changes ==========

    /**
     * {@link SynchedEntityData#packDirty()} returns {@code null} when no entity
     * data has changed. Instead of <em>always</em> forcing a packet for Player
     * entities (which floods the network every tick), this method only forces a
     * packet when the entity's glowing-team membership or the config version has
     * changed since the last sync.
     *
     * <p>Initial tracking (first sighting) is handled by {@link #onAddPairing},
     * which fires exactly once when a viewer starts tracking this entity.
     * This method only catches mid-session state changes: team join/leave,
     * {@code /teamglow team add/remove}, or glow toggling on/off.
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
    private List<SynchedEntityData.DataValue<?>> smartForcePacket(
            List<SynchedEntityData.DataValue<?>> original) {

        if (original != null) {
            return original; // Natural dirty data → @Redirect handles it.
        }
        if (!(entity instanceof Player entityPlayer)) {
            return null;
        }

        GlowConfigManager config = GlowConfigManager.getInstance();

        // Determine current glow state for this entity.
        PlayerTeam glowingTeam = config.isEnabled()
                ? getGlowingTeam(entityPlayer) : null;
        String currentTeamName = glowingTeam != null
                ? glowingTeam.getName() : null;
        long currentConfigVersion = config.getVersion();
        long currentSyncEpoch = config.getSyncEpoch();

        // Detect transitions in BOTH directions:
        // null → "red" = player joined a glowing team (force glow packet)
        // "red" → null = player left or mod disabled (force cleanup packet)
        boolean teamChanged = (currentTeamName == null)
                ? (cachedTeamName != null)
                : !currentTeamName.equals(cachedTeamName);
        boolean configChanged = currentConfigVersion != cachedConfigVersion;
        boolean epochChanged = currentSyncEpoch != cachedSyncEpoch;

        if (teamChanged || configChanged || epochChanged) {
            cachedTeamName = currentTeamName;
            cachedConfigVersion = currentConfigVersion;
            cachedSyncEpoch = currentSyncEpoch;
            return buildFlagsPacket();
        }

        // No relevant state change — let vanilla skip the packet.
        return null;
    }

    private List<SynchedEntityData.DataValue<?>> buildFlagsPacket() {
        EntityDataAccessor<Byte> accessor = EntityAccessor.getSharedFlagsId();
        byte flags = entity.getEntityData().get(accessor);
        return List.of(new SynchedEntityData.DataValue<>(
                0, EntityDataSerializers.BYTE, flags));
    }

    // ========== Initial sync: first sighting by a new tracker ==========

    /**
     * When a player enters this entity's tracking range for the first time
     * (or after reconnecting / dimension change), immediately send the
     * correct glow state — glow for teammates, no glow for others.
     *
     * <p>This fires exactly once per viewer, right after the vanilla spawn
     * packet. For teammates: sends 1 extra {@link ClientboundSetEntityDataPacket}
     * with bit {@code 0x40} set. For non-teammates: no action needed.
     */
    @Inject(method = "addPairing", at = @At("TAIL"))
    private void onAddPairing(ServerPlayer viewer, CallbackInfo ci) {
        if (!(entity instanceof Player entityPlayer)) {
            return;
        }
        if (!GlowConfigManager.getInstance().isEnabled()) {
            return;
        }

        PlayerTeam glowingTeam = getGlowingTeam(entityPlayer);
        if (glowingTeam == null) {
            return;
        }

        Scoreboard scoreboard = viewer.level().getScoreboard();
        PlayerTeam viewerTeam = scoreboard.getPlayersTeam(
                viewer.getScoreboardName());
        boolean isTeammate = glowingTeam.equals(viewerTeam);

        if (!isTeammate) {
            // Prevent smartForcePacket from redundantly forcing later.
            cachedTeamName = glowingTeam.getName();
            cachedConfigVersion = GlowConfigManager.getInstance().getVersion();
            cachedSyncEpoch = GlowConfigManager.getInstance().getSyncEpoch();
            return;
        }

        byte flags = entity.getEntityData().get(
                EntityAccessor.getSharedFlagsId());
        byte glowFlags = (byte) (flags | 0x40);

        List<SynchedEntityData.DataValue<?>> items = List.of(
                new SynchedEntityData.DataValue<>(
                        0, EntityDataSerializers.BYTE, glowFlags));
        viewer.connection.send(
                new ClientboundSetEntityDataPacket(entity.getId(), items));

        cachedTeamName = glowingTeam.getName();
        cachedConfigVersion = GlowConfigManager.getInstance().getVersion();
        cachedSyncEpoch = GlowConfigManager.getInstance().getSyncEpoch();
    }

    // ========== Helpers ==========

    /**
     * Returns the {@link PlayerTeam} the given player belongs to, if that team
     * has glow enabled. Returns {@code null} otherwise.
     */
    @Unique
    private static PlayerTeam getGlowingTeam(Player player) {
        Scoreboard scoreboard = player.level().getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(
                player.getScoreboardName());
        if (team == null) {
            return null;
        }
        if (!GlowConfigManager.getInstance().isTeamEnabled(team.getName())) {
            return null;
        }
        return team;
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

        sync.sendToTrackingPlayersFiltered(glowPacket, isTeammate);
        sync.sendToTrackingPlayersFiltered(
                noGlowPacket, v -> !isTeammate.test(v));

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
