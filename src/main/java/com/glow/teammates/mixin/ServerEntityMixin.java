package com.glow.teammates.mixin;

import com.glow.teammates.GlowConstants;
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
     * Cached state at last glow sync — stale counters trigger a forced resync.
     * {@code null} team name means not yet synced (forces a packet on first
     * encounter). Counters: config version (config changes, e.g. team add/remove)
     * and sync epoch (viewer-side team changes — a player joining or leaving a
     * team forces a resync of every glowing entity so affected viewers see the
     * correct state immediately).
     */
    @Unique
    private String cachedTeamName;
    @Unique
    private long cachedConfigVersion;
    @Unique
    private long cachedSyncEpoch;

    /**
     * Glow-only overlay packet cached per server-flags value: continuously-dirty
     * glowing entities (frozen, drowning, mob farms) would otherwise allocate a
     * new packet every tick. Immutable (encode is read-only), so reusing it for
     * every teammate viewer is safe.
     */
    @Unique
    private Byte cachedGlowFlags;
    @Unique
    private ClientboundSetEntityDataPacket cachedGlowPacket;

    // ========== Smart force: only when team membership or config changes ==========

    /**
     * When {@code packDirty()} returned {@code null}, force a packet only if the
     * glowing-team membership, config version, or sync epoch changed since the
     * last sync — never flood the network on every quiet tick. First sighting is
     * handled by {@link #onAddPairing}; this catches mid-session changes only.
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
            // Non-player, glow off — never customizable. Sync the caches and,
            // if this entity glowed before the switch was turned off
            // (cachedTeamName set), force one current-flags packet so stale
            // client glow is cleared even when clearNonPlayerGlow wasn't run
            // (e.g. another mod disabled the switch directly). Reading the
            // entity's live flags also keeps a vanilla glow (spectral/effect).
            boolean hadCachedTeam = cachedTeamName != null;
            cachedTeamName = null;
            cachedConfigVersion = config.getVersion();
            cachedSyncEpoch = config.getSyncEpoch();
            return hadCachedTeam ? buildFlagsPacket() : null;
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

        // Never-glow bail: no glowing team now or at last sync — no state
        // change can make it glow, so the whole broadcast is skippable. Sync
        // the caches too, so later bumps don't re-run the lookups forever.
        if (currentTeamName == null && cachedTeamName == null) {
            cachedTeamName = null;
            cachedConfigVersion = currentConfigVersion;
            cachedSyncEpoch = currentSyncEpoch;
            return null;
        }

        // Detect transitions in both directions: joined a glowing team (force
        // glow packet) or left / mod disabled (force cleanup packet).
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
     * First sighting by a new viewer (or after reconnect / dimension change):
     * send the correct glow state exactly once, right after the vanilla spawn
     * packet — glow bit for teammates, nothing for non-teammates.
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

        // Same vanilla-glow rule as redirectSendData: the viewer is already
        // covered (GLOWING effect for living entities, glow-tag bit for the rest).
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
            // Remember the glow team for smartForcePacket's detection, but do
            // NOT settle the epoch/config counters: they mark "all viewers
            // synced", and this pairing only customized the NEW viewer —
            // settling would suppress the pending cleanup broadcast for
            // viewers whose team changed before this pairing. The counters
            // settle on the next smartForcePacket round instead.
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

        // Only cachedTeamName is settled — same reasoning as the non-teammate
        // branch above, so a pending cleanup broadcast for existing viewers is
        // delivered by the next quiet tick instead of being skipped.
        cachedTeamName = glowingTeam.getName();
    }

    // ========== Helpers ==========

    /**
     * The entity's team if it has glow enabled, else {@code null}. Non-players
     * are looked up by scoreboard name (their UUID string). Runs once per dirty
     * packet plus once per viewer; the O(1) probes are accepted as-is — caching
     * the team would need invalidation the global {@code syncEpoch} cannot
     * distinguish (AGENTS.md §10.2).
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
     * Drop a stale mod-glow bit when the entity no longer should glow, and
     * settle the caches. Skips the extra packet when the current broadcast
     * already carries the shared-flags entry (the no-glow value applies on
     * its own). Needed because a continuously-dirty entity never takes
     * {@code smartForcePacket}, so the stale bit would otherwise linger
     * until the data stops changing.
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

        // Fast bails for states that can never be customized. Each one also
        // clears a stale mod glow from a previous state (see clearStaleGlow
        // for why the packet and the caches both matter).
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

        // No team has glow enabled — nothing to customize for any viewer.
        if (!config.hasEnabledTeams()) {
            clearStaleGlow(sync, dataPacket, config);
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        // Vanilla glow (GLOWING effect for living entities, glow-tag bit for
        // the rest) — forward unchanged; the no-glow broadcast below would
        // wrongly extinguish it.
        boolean vanillaGlow = entity instanceof LivingEntity living
                ? living.isCurrentlyGlowing()
                : (entity.getEntityData().get(EntityAccessor.getSharedFlagsId())
                        & GlowConstants.FLAG_GLOWING) != 0;
        if (vanillaGlow) {
            // Settle the counters when stale: the forwarded packet never does,
            // so smartForcePacket would otherwise force a redundant broadcast
            // on every quiet tick — the one-shot-per-bump budget in AGENTS.md
            // §10.1 silently becomes unbounded. Safe: while vanilla glow is
            // active the mod's bit is invisible, and the next flags change
            // clears any stale bit via the split path below.
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

        // One lookup per dirty send drives both the stale-glow cleanup and the
        // per-viewer predicate (not cached on the ServerEntity — AGENTS.md §10.2).
        PlayerTeam entityTeamObj = getGlowingTeam(entity);

        // Previously customized but no longer in a glowing team: drop the stale
        // bit (clearStaleGlow skips a second broadcast when this packet already
        // carries the flags entry).
        if (cachedTeamName != null && entityTeamObj == null) {
            clearStaleGlow(sync, dataPacket, config);
        }

        // Not in a glowing team → no customization needed.
        if (entityTeamObj == null) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        Predicate<ServerPlayer> isTeammate =
                viewer -> entityTeamObj.equals(viewer.getTeam());

        // Viewer-side resync: when the counters moved since last sync, viewers
        // that left the glowing team still carry the mod's 0x40 bit. A
        // continuously-dirty entity never takes smartForcePacket, so the
        // no-glow broadcast below must carry the flags entry itself to clear
        // it. All three caches settle together: the stale counters may stem
        // from the entity's OWN team change, and a counter-only settle would
        // leave cachedTeamName stale for smartForcePacket's fast bail to
        // trust (AGENTS.md §10.1). entityTeamObj is non-null here, so syncing
        // to it is always safe.
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

        // No-glow to everyone (tracking + self) first, glow overlay to
        // teammates after — Netty's per-connection FIFO makes the overlay win.
        // Self is never part of its own tracking set, so
        // sendToTrackingPlayersAndSelf already covers it — deliberate: no
        // self-glow in third-person view.
        sync.sendToTrackingPlayersAndSelf(noGlowPacket);
        sync.sendToTrackingPlayersFiltered(glowPacket, isTeammate);
    }

    // ========== Packet helper ==========

    /**
     * Copy the packet with the glow bit (0x40) set or cleared; return the
     * original unchanged when the flags already match (common no-glow case).
     *
     * <p>When the packet lacks the flags entry, the glow variant must derive
     * the byte from the entity's <em>current</em> flags — a bare {@code 0x40}
     * would replace the whole byte client-side and wipe the other flag bits
     * (FALL_FLYING, SPRINTING, INVISIBLE, ON_FIRE...). {@code forceIncludeFlags}
     * gives the no-glow variant the same treatment (a team-change viewer still
     * carries the stale bit; only a flags entry can clear it): it APPENDS the
     * entry, keeping every dirty entry — the no-glow broadcast is their only
     * delivery.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ClientboundSetEntityDataPacket modifyGlowFlag(
            ClientboundSetEntityDataPacket packet, boolean shouldGlow,
            boolean forceIncludeFlags) {

        int sharedFlagsId = EntityAccessor.getSharedFlagsId().id();

        // Pre-check: when the packet already carries the requested glow state,
        // reuse it without copying — the common case, flags rarely change.
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

        // The glow bit must change — copy the items and modify in place.
        List<SynchedEntityData.DataValue<?>> items =
                new ArrayList<>(packet.packedItems());
        for (int i = 0; i < items.size(); i++) {
            SynchedEntityData.DataValue<?> item = items.get(i);
            if (item.id() == sharedFlagsId && item.value() instanceof Byte current) {
                byte newValue = (byte) (shouldGlow
                        ? (current | GlowConstants.FLAG_GLOWING)
                        : (current & GlowConstants.GLOW_CLEAR_MASK));
                // item.id() == sharedFlagsId was verified above — reuse it
                // instead of hardcoding the flags slot id (0).
                SynchedEntityData.DataValue rawItem =
                        new SynchedEntityData.DataValue(
                                item.id(), item.serializer(), newValue);
                items.set(i, rawItem);
                break;
            }
        }
        if (!foundFlag) {
            // No flags entry — derive the byte from the entity's current flags
            // (a bare 0x40 would wipe the other flag bits). The glow overlay is
            // cached while the server flags are unchanged: the other dirty
            // entries were already delivered by the no-glow broadcast, so a
            // flags-only overlay is sufficient.
            byte current = entity.getEntityData().get(
                    EntityAccessor.getSharedFlagsId());
            if (shouldGlow) {
                if (cachedGlowPacket == null || cachedGlowFlags == null
                        || cachedGlowFlags.byteValue() != current) {
                    cachedGlowFlags = current;
                    cachedGlowPacket = new ClientboundSetEntityDataPacket(packet.id(),
                            List.of(new SynchedEntityData.DataValue<>(
                                    sharedFlagsId, EntityDataSerializers.BYTE,
                                    (byte) (current | GlowConstants.FLAG_GLOWING))));
                }
                return cachedGlowPacket;
            }
            // Forced no-glow — only reachable with forceIncludeFlags (the
            // non-forced case returned the original above). Keep every dirty
            // entry and append the no-glow flags entry: the no-glow broadcast
            // is their only delivery, so a flags-only packet would drop them
            // for all viewers. Built at most once per bump, so it is not cached.
            items.add(new SynchedEntityData.DataValue<>(
                    sharedFlagsId, EntityDataSerializers.BYTE,
                    (byte) (current & GlowConstants.GLOW_CLEAR_MASK)));
        }
        return new ClientboundSetEntityDataPacket(packet.id(), items);
    }
}
