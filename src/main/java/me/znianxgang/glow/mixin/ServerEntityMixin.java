package me.znianxgang.glow.mixin;

import me.znianxgang.glow.GlowConstants;
import me.znianxgang.glow.config.GlowConfigManager;
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
import java.util.Objects;
import java.util.function.Predicate;

@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    @Shadow
    @Final
    private Entity entity;

    /**
     * State at the last glow sync. A {@code null} team name means "not synced
     * yet", which forces a packet on first sighting; stale counters force a
     * resync.
     */
    @Unique
    private String cachedTeamName;
    @Unique
    private long cachedConfigVersion;
    @Unique
    private long cachedSyncEpoch;

    /**
     * Glow-only overlay cached per server-flags value: a continuously-dirty
     * entity (frozen, drowning) would otherwise allocate one every tick. The
     * packet is immutable, so sharing it across viewers is safe.
     */
    @Unique
    private Byte cachedGlowFlags;
    @Unique
    private ClientboundSetEntityDataPacket cachedGlowPacket;

    // ========== Smart force: only when team membership or config changes ==========

    /**
     * {@code packDirty()} returned {@code null}: force a packet only if the
     * glowing team or a counter changed, never on every quiet tick. First
     * sighting is {@link #onAddPairing}'s job.
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
            // Never customizable. If it glowed before the switch went off, send
            // one current-flags packet so the stale client bit is cleared.
            boolean hadCachedTeam = cachedTeamName != null;
            cachedTeamName = null;
            cachedConfigVersion = config.getVersion();
            cachedSyncEpoch = config.getSyncEpoch();
            return hadCachedTeam ? buildFlagsPacket() : null;
        }

        long currentConfigVersion = config.getVersion();
        long currentSyncEpoch = config.getSyncEpoch();

        // Neither counter moved, so the entity's team cannot have changed.
        if (currentConfigVersion == cachedConfigVersion
                && currentSyncEpoch == cachedSyncEpoch) {
            return null;
        }

        PlayerTeam glowingTeam = config.isEnabled()
                ? getGlowingTeam(entity) : null;
        String currentTeamName = glowingTeam != null
                ? glowingTeam.getName() : null;

        // No glowing team now or at the last sync: no state change can make it
        // glow, so sync the caches and skip the broadcast.
        if (currentTeamName == null && cachedTeamName == null) {
            cachedConfigVersion = currentConfigVersion;
            cachedSyncEpoch = currentSyncEpoch;
            return null;
        }

        // Both directions matter: joining a glowing team needs the glow packet,
        // leaving it (or disabling the mod) needs a cleanup packet.
        boolean teamChanged = !Objects.equals(currentTeamName, cachedTeamName);
        boolean configChanged = currentConfigVersion != cachedConfigVersion;
        boolean epochChanged = currentSyncEpoch != cachedSyncEpoch;

        if (teamChanged || configChanged || epochChanged) {
            cachedTeamName = currentTeamName;
            cachedConfigVersion = currentConfigVersion;
            cachedSyncEpoch = currentSyncEpoch;
            return buildFlagsPacket();
        }

        return null;
    }

    @Unique
    private List<SynchedEntityData.DataValue<?>> buildFlagsPacket() {
        EntityDataAccessor<Byte> accessor = EntityAccessor.getSharedFlagsId();
        byte flags = entity.getEntityData().get(accessor);
        return List.of(new SynchedEntityData.DataValue<>(
                accessor.id(), EntityDataSerializers.BYTE, flags));
    }

    // ========== Initial sync: first sighting by a new tracker ==========

    /**
     * First sighting by a viewer (or after reconnect / dimension change): send
     * the correct state once, right after the vanilla spawn packet.
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

        boolean vanillaGlow = entity instanceof LivingEntity living
                ? living.isCurrentlyGlowing()
                : (entity.getEntityData().get(EntityAccessor.getSharedFlagsId())
                        & GlowConstants.FLAG_GLOWING) != 0;
        if (vanillaGlow) {
            return;
        }

        PlayerTeam glowingTeam = getGlowingTeam(entity);
        if (glowingTeam == null) {
            return;
        }

        boolean isTeammate = glowingTeam.equals(viewer.getTeam());

        if (!isTeammate) {
            // Remember the team for smartForcePacket, but do NOT settle the
            // counters: they mean "all viewers synced", and this pairing only
            // customized the new viewer.
            cachedTeamName = glowingTeam.getName();
            return;
        }

        EntityDataAccessor<Byte> flagsAccessor = EntityAccessor.getSharedFlagsId();
        byte flags = entity.getEntityData().get(flagsAccessor);
        byte glowFlags = (byte) (flags | GlowConstants.FLAG_GLOWING);

        List<SynchedEntityData.DataValue<?>> items = List.of(
                new SynchedEntityData.DataValue<>(
                        flagsAccessor.id(), EntityDataSerializers.BYTE, glowFlags));
        if (viewer.connection != null) {
            viewer.connection.send(
                    new ClientboundSetEntityDataPacket(entity.getId(), items));
        }

        cachedTeamName = glowingTeam.getName();
    }

    // ========== Helpers ==========

    /**
     * The entity's team if glow is enabled for it, else {@code null}.
     * Non-players are looked up by scoreboard name (their UUID string). Uncached
     * on purpose: a team change in a non-glow team moves no counter, so there is
     * nothing to invalidate a cached value with.
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
     * Drop a stale glow bit when the entity should no longer glow, and settle
     * the caches. A continuously-dirty entity never takes
     * {@code smartForcePacket}, so without this the bit would linger until its
     * data stops changing.
     */
    @Unique
    private void clearStaleGlow(ServerEntity.Synchronizer sync,
                                ClientboundSetEntityDataPacket dataPacket,
                                GlowConfigManager config) {
        if (cachedTeamName == null) {
            return;
        }
        boolean carriesFlags = false;
        int sharedFlagsId = EntityAccessor.getSharedFlagsId().id();
        for (SynchedEntityData.DataValue<?> item : dataPacket.packedItems()) {
            if (item.id() == sharedFlagsId) {
                carriesFlags = true;
                break;
            }
        }
        if (!carriesFlags) {
            sync.sendToTrackingPlayersAndSelf(new ClientboundSetEntityDataPacket(
                    entity.getId(), buildFlagsPacket()));
        }
        cachedTeamName = null;
        cachedConfigVersion = config.getVersion();
        cachedSyncEpoch = config.getSyncEpoch();
    }

    // ========== Per-client glow customization ==========

    /** Customize the glowing flag per-client on every dirty-data broadcast. */
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

        // States that can never be customized. Each also clears a stale glow
        // left by a previous state.
        if (!(entity instanceof Player) && !config.isNonPlayerGlow()) {
            clearStaleGlow(sync, dataPacket, config);
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }
        if (!config.isEnabled()) {
            clearStaleGlow(sync, dataPacket, config);
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        // No team has glow enabled.
        if (!config.hasEnabledTeams()) {
            clearStaleGlow(sync, dataPacket, config);
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        // Vanilla glow (GLOWING effect, or the glow-tag bit) is forwarded as-is;
        // the no-glow broadcast would otherwise extinguish it.
        boolean vanillaGlow = entity instanceof LivingEntity living
                ? living.isCurrentlyGlowing()
                : (entity.getEntityData().get(EntityAccessor.getSharedFlagsId())
                        & GlowConstants.FLAG_GLOWING) != 0;
        if (vanillaGlow) {
            // Settle the counters: the forwarded packet never does, so
            // smartForcePacket would otherwise force a packet every quiet tick.
            if (cachedSyncEpoch != config.getSyncEpoch()
                    || cachedConfigVersion != config.getVersion()) {
                PlayerTeam team = getGlowingTeam(entity);
                cachedTeamName = team != null ? team.getName() : null;
                cachedSyncEpoch = config.getSyncEpoch();
                cachedConfigVersion = config.getVersion();
            }
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        // One lookup drives both the stale-glow cleanup and the viewer predicate.
        PlayerTeam entityTeamObj = getGlowingTeam(entity);

        // Previously customized but no longer in a glowing team.
        if (cachedTeamName != null && entityTeamObj == null) {
            clearStaleGlow(sync, dataPacket, config);
        }

        if (entityTeamObj == null) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        Predicate<ServerPlayer> isTeammate =
                viewer -> entityTeamObj.equals(viewer.getTeam());

        // When a counter moved, viewers that left the team still carry the bit,
        // so the no-glow broadcast below has to carry the flags entry itself (a
        // dirty entity never takes smartForcePacket). All three caches settle
        // together, or cachedTeamName would stay stale for smartForcePacket.
        boolean viewerSideChanged = cachedSyncEpoch != config.getSyncEpoch()
                || cachedConfigVersion != config.getVersion();

        ClientboundSetEntityDataPacket glowPacket = modifyGlowFlag(
                dataPacket, true, false);
        ClientboundSetEntityDataPacket noGlowPacket = modifyGlowFlag(
                dataPacket, false, viewerSideChanged);
        if (viewerSideChanged) {
            cachedTeamName = entityTeamObj.getName();
            cachedSyncEpoch = config.getSyncEpoch();
            cachedConfigVersion = config.getVersion();
        }

        // No-glow first, glow overlay after: per-connection FIFO makes the
        // overlay win. Self is not in its own tracking set, so players never see
        // their own glow in third person — deliberate.
        sync.sendToTrackingPlayersAndSelf(noGlowPacket);
        sync.sendToTrackingPlayersFiltered(glowPacket, isTeammate);
    }

    // ========== Packet helper ==========

    /**
     * Copy the packet with the glow bit set or cleared, returning the original
     * when it already matches. Without a flags entry the byte is derived from
     * the entity's current flags — a bare {@code 0x40} would wipe the other flag
     * bits client-side. {@code forceIncludeFlags} appends that entry for the
     * no-glow variant, keeping the round's dirty entries.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Unique
    private ClientboundSetEntityDataPacket modifyGlowFlag(
            ClientboundSetEntityDataPacket packet, boolean shouldGlow,
            boolean forceIncludeFlags) {

        int sharedFlagsId = EntityAccessor.getSharedFlagsId().id();

        // Reuse the packet when it already carries the requested state — flags
        // rarely change, so this is the common case.
        boolean foundFlag = false;
        boolean flagsMatch = true;
        for (SynchedEntityData.DataValue<?> item : packet.packedItems()) {
            if (item.id() == sharedFlagsId && item.value() instanceof Byte current) {
                foundFlag = true;
                flagsMatch = ((current & GlowConstants.FLAG_GLOWING) != 0) == shouldGlow;
                break;
            }
        }
        if (foundFlag && flagsMatch) {
            return packet;
        }
        if (!foundFlag && !shouldGlow && !forceIncludeFlags) {
            return packet; // No flags entry to clear — the original is identical.
        }

        List<SynchedEntityData.DataValue<?>> items =
                new ArrayList<>(packet.packedItems());
        for (int i = 0; i < items.size(); i++) {
            SynchedEntityData.DataValue<?> item = items.get(i);
            if (item.id() == sharedFlagsId && item.value() instanceof Byte current) {
                byte newValue = (byte) (shouldGlow
                        ? (current | GlowConstants.FLAG_GLOWING)
                        : (current & GlowConstants.GLOW_CLEAR_MASK));
                // Keep the packet's own slot id instead of hardcoding 0.
                SynchedEntityData.DataValue rawItem =
                        new SynchedEntityData.DataValue(
                                item.id(), item.serializer(), newValue);
                items.set(i, rawItem);
                break;
            }
        }
        if (!foundFlag) {
            // The other dirty entries ride the no-glow broadcast, so a
            // flags-only overlay is enough — and it can be cached while the
            // server flags stay unchanged.
            byte current = entity.getEntityData().get(
                    EntityAccessor.getSharedFlagsId());
            if (shouldGlow) {
                if (cachedGlowPacket == null || cachedGlowFlags == null
                        || cachedGlowFlags != current) {
                    cachedGlowFlags = current;
                    cachedGlowPacket = new ClientboundSetEntityDataPacket(packet.id(),
                            List.of(new SynchedEntityData.DataValue<>(
                                    sharedFlagsId, EntityDataSerializers.BYTE,
                                    (byte) (current | GlowConstants.FLAG_GLOWING))));
                }
                return cachedGlowPacket;
            }
            // Forced no-glow: keep every dirty entry and append the flags entry,
            // because the no-glow broadcast is their only delivery.
            items.add(new SynchedEntityData.DataValue<>(
                    sharedFlagsId, EntityDataSerializers.BYTE,
                    (byte) (current & GlowConstants.GLOW_CLEAR_MASK)));
        }
        return new ClientboundSetEntityDataPacket(packet.id(), items);
    }
}
