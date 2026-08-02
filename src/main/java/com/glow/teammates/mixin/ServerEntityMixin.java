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

    /**
     * Bit in the entity shared flags ({@code Entity.DATA_SHARED_FLAGS_ID})
     * that controls glowing: {@code 1 << Entity.FLAG_GLOWING}.
     */
    @Unique
    private static final byte FLAG_GLOWING = 0x40;

    // ========== Smart force: only when team membership or config changes ==========

    /**
     * {@link SynchedEntityData#packDirty()} returns {@code null} when no entity
     * data has changed. Instead of <em>always</em> forcing a packet for tracked
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
        GlowConfigManager config = GlowConfigManager.getInstance();
        if (!(entity instanceof Player) && !config.isNonPlayerGlow()) {
            return null;
        }

        long currentConfigVersion = config.getVersion();
        long currentSyncEpoch = config.getSyncEpoch();

        // If neither config nor team membership changed, entity team can't have changed
        if (currentConfigVersion == cachedConfigVersion
                && currentSyncEpoch == cachedSyncEpoch) {
            return null;
        }

        // Determine current glow state for this entity.
        PlayerTeam glowingTeam = config.isEnabled()
                ? getGlowingTeam(entity) : null;
        String currentTeamName = glowingTeam != null
                ? glowingTeam.getName() : null;

        // Optimization: entity was never in a glowing team (neither now nor
        // at last sync) — no viewer-side team change or config change can
        // make it glow, so the whole broadcast is skippable. If it later
        // joins a glowing team, addPlayerToTeam bumps the syncEpoch and the
        // normal path below re-engages, so nothing is missed.
        if (currentTeamName == null && cachedTeamName == null) {
            // Catch the caches up too — otherwise cachedConfigVersion /
            // cachedSyncEpoch stay forever behind and every later epoch or
            // config bump would re-run the scoreboard lookups above every
            // tick, permanently.
            cachedTeamName = null;
            cachedConfigVersion = currentConfigVersion;
            cachedSyncEpoch = currentSyncEpoch;
            return null;
        }

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
                accessor.id(), EntityDataSerializers.BYTE, flags));
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
        GlowConfigManager config = GlowConfigManager.getInstance();
        if (!(entity instanceof Player) && !config.isNonPlayerGlow()) {
            return;
        }
        if (!config.isEnabled()) {
            return;
        }
        if (!config.hasEnabledTeams()) {
            return;
        }

        // Vanilla glow (spectral arrows, potions, setGlowingTag) already covers
        // the viewer — same rule as redirectSendData, so both paths judge
        // uniformly and the OR-ing here stays a no-op.
        if (entity instanceof LivingEntity living && living.isCurrentlyGlowing()) {
            return;
        }

        PlayerTeam glowingTeam = getGlowingTeam(entity);
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

        EntityDataAccessor<Byte> flagsAccessor = EntityAccessor.getSharedFlagsId();
        byte flags = entity.getEntityData().get(flagsAccessor);
        byte glowFlags = (byte) (flags | FLAG_GLOWING);

        List<SynchedEntityData.DataValue<?>> items = List.of(
                new SynchedEntityData.DataValue<>(
                        flagsAccessor.id(), EntityDataSerializers.BYTE, glowFlags));
        if (viewer.connection != null) {
            viewer.connection.send(
                    new ClientboundSetEntityDataPacket(entity.getId(), items));
        }

        cachedTeamName = glowingTeam.getName();
        cachedConfigVersion = GlowConfigManager.getInstance().getVersion();
        cachedSyncEpoch = GlowConfigManager.getInstance().getSyncEpoch();
    }

    // ========== Helpers ==========

    /**
     * Returns the {@link PlayerTeam} the given entity belongs to, if that team
     * has glow enabled. Returns {@code null} otherwise. Non-player entities
     * are looked up by their scoreboard name ({@link Entity#getScoreboardName()},
     * which is the UUID string for non-players).
     */
    @Unique
    private static PlayerTeam getGlowingTeam(Entity entity) {
        Scoreboard scoreboard = entity.level().getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(
                entity.getScoreboardName());
        if (team == null) {
            return null;
        }
        if (!GlowConfigManager.getInstance().isTeamEnabled(team.getName())) {
            return null;
        }
        return team;
    }

    /**
     * Whether the entity should currently be glowing through this mod: the mod
     * is on, some team has glow enabled, the entity is eligible (player, or
     * non-player with the {@code non_player_glow} switch on), and it belongs
     * to a glow-enabled team.
     */
    @Unique
    private boolean shouldEntityGlow(GlowConfigManager config) {
        if (!config.isEnabled() || !config.hasEnabledTeams()) {
            return false;
        }
        if (!(entity instanceof Player) && !config.isNonPlayerGlow()) {
            return false;
        }
        return getGlowingTeam(entity) != null;
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
                            + "sendToTrackingPlayersAndSelf(Lnet/minecraft/network/protocol/Packet;)V",
                    ordinal = 0
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

        GlowConfigManager config = GlowConfigManager.getInstance();

        // If this entity was previously customized by the mod but should no
        // longer glow (mod off, team removed from the config, non_player_glow
        // off, or the entity left the glow team), push the server's actual
        // flags right away. A continuously-dirty entity (a drowning player's
        // AIR_SUPPLY, a frozen entity's ticksFrozen) never lets packDirty()
        // return null, so smartForcePacket never fires and the stale glow bit
        // would otherwise linger on clients until the data stops changing.
        if (cachedTeamName != null && !shouldEntityGlow(config)) {
            sync.sendToTrackingPlayersAndSelf(new ClientboundSetEntityDataPacket(
                    entity.getId(), buildFlagsPacket()));
            cachedTeamName = null;
            cachedConfigVersion = config.getVersion();
            cachedSyncEpoch = config.getSyncEpoch();
        }

        if (!(entity instanceof Player) && !config.isNonPlayerGlow()) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }
        if (!config.isEnabled()) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        // No team has glow enabled — nothing to customize for any viewer,
        // so skip the per-packet scoreboard/effect lookups entirely.
        if (!config.hasEnabledTeams()) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        // Skip when vanilla glowing is active: for LivingEntity,
        // isCurrentlyGlowing() covers the GLOWING effect AND setGlowingTag,
        // which is exactly what the shared-flags 0x40 bit reflects. Non-living
        // entities (item frames, boats...) have no effect — only the glow tag
        // can be set, so check the same bit directly; without this, the
        // no-glow broadcast below would wrongly extinguish their vanilla glow.
        boolean vanillaGlow = entity instanceof LivingEntity living
                ? living.isCurrentlyGlowing()
                : (entity.getEntityData().get(EntityAccessor.getSharedFlagsId())
                        & FLAG_GLOWING) != 0;
        if (vanillaGlow) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        PlayerTeam entityTeamObj = getGlowingTeam(entity);

        // Entity not in a glowing team → no glow customization needed
        if (entityTeamObj == null) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        Predicate<ServerPlayer> isTeammate = viewer -> {
            PlayerTeam viewerTeam = viewer.level().getScoreboard()
                    .getPlayersTeam(viewer.getScoreboardName());
            return entityTeamObj.equals(viewerTeam);
        };

        ClientboundSetEntityDataPacket glowPacket = modifyGlowFlag(
                dataPacket, true);
        ClientboundSetEntityDataPacket noGlowPacket = modifyGlowFlag(
                dataPacket, false);

        // Broadcast the no-glow variant to everyone (tracking + self) first,
        // then overlay the glow variant for teammates only. Netty guarantees
        // per-connection FIFO, so teammates end up with the glow bit set.
        // This replaces two filtered passes (one scoreboard lookup per viewer
        // in each) with a single plain broadcast + one filtered pass — and
        // the entity itself, which is never part of its own tracking set
        // (ChunkMap.TrackedEntity.updatePlayer excludes self), is covered by
        // sendToTrackingPlayersAndSelf, so the former explicit self-send is
        // unnecessary.
        //
        // Deliberate consequence: a glowing player does NOT see their own glow
        // in third-person view — self gets the no-glow variant and the tracking
        // set excludes self — only teammates see it.
        sync.sendToTrackingPlayersAndSelf(noGlowPacket);
        sync.sendToTrackingPlayersFiltered(glowPacket, isTeammate);
    }

    // ========== Packet helper ==========

    /**
     * Create a copy of the packet with the glowing flag (bit 0x40) modified,
     * or return the original packet unchanged when the flag already matches
     * the requested state (avoids allocating and re-serializing an identical
     * packet for the common no-glow broadcast).
     *
     * <p>When the packet does not contain the shared-flags entry (i.e. the
     * flags did not change since the last send), the glow variant must add
     * the entry based on the entity's <em>current</em> flags — a bare
     * {@code 0x40} would replace the whole byte on the client and wipe
     * the other flag bits (FALL_FLYING, SPRINTING, INVISIBLE, ON_FIRE...).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ClientboundSetEntityDataPacket modifyGlowFlag(
            ClientboundSetEntityDataPacket packet, boolean shouldGlow) {

        List<SynchedEntityData.DataValue<?>> items =
                new ArrayList<>(packet.packedItems());
        int sharedFlagsId = EntityAccessor.getSharedFlagsId().id();
        boolean foundFlag = false;
        boolean changed = false;

        for (int i = 0; i < items.size(); i++) {
            SynchedEntityData.DataValue<?> item = items.get(i);
            if (item.id() == sharedFlagsId && item.value() instanceof Byte current) {
                byte newValue = (byte) (shouldGlow
                        ? (current | FLAG_GLOWING) : (current & 0xBF));
                if (newValue != current) {
                    // item.id() == sharedFlagsId was verified above — reuse
                    // it instead of hardcoding the flags slot id (0).
                    SynchedEntityData.DataValue rawItem =
                            new SynchedEntityData.DataValue(
                                    item.id(), item.serializer(), newValue);
                    items.set(i, rawItem);
                    changed = true;
                }
                foundFlag = true;
                break;
            }
        }

        if (!foundFlag) {
            if (!shouldGlow) {
                // No flags entry to clear — the original packet is identical.
                return packet;
            }
            byte current = entity.getEntityData().get(
                    EntityAccessor.getSharedFlagsId());
            items.add(new SynchedEntityData.DataValue<>(
                    sharedFlagsId, EntityDataSerializers.BYTE,
                    (byte) (current | FLAG_GLOWING)));
            changed = true;
        }

        if (!changed) {
            // Flags already in the desired state — reuse the original packet.
            return packet;
        }
        return new ClientboundSetEntityDataPacket(packet.id(), items);
    }
}
