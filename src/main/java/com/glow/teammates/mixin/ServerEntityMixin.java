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
     * Cached glow-only packet and the server flags value it was built from.
     * Continuously-dirty glowing entities (frozen, drowning, mob farms with
     * {@code non_player_glow} on) would otherwise allocate a new packet every
     * tick; the cache is rebuilt whenever the server flags change. The cached
     * packet is immutable (encode is read-only), so reusing it for every
     * teammate viewer is safe.
     */
    @Unique
    private Byte cachedGlowFlags;
    @Unique
    private ClientboundSetEntityDataPacket cachedGlowPacket;

    /**
     * Bit in the entity shared flags ({@code Entity.DATA_SHARED_FLAGS_ID})
     * that controls glowing — see {@link EntityAccessor#FLAG_GLOWING}.
     */

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
            // Non-player glow is off and this is not a player — this entity can
            // never be customized by the mod. Sync the caches anyway: a mob
            // that glowed before the switch was turned off (cachedTeamName
            // set) and never produces dirty data again would otherwise keep
            // stale cached state forever. If it was previously customized, force
            // one packet so stale client-side glow is cleared even when the
            // command-path clearNonPlayerGlow wasn't run (e.g. another mod called
            // setNonPlayerGlow(false) directly).
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
        // uniformly and the OR-ing here stays a no-op. Non-living entities
        // (item frames, boats...) have no effect — only the glow tag can be
        // set, so check the same shared-flags bit directly.
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
            // Remember the entity's glow team so smartForcePacket's team-change
            // detection stays accurate — but do NOT settle cachedSyncEpoch /
            // cachedConfigVersion. Those counters are the "all viewers are
            // synced" marker, and this pairing only customized the NEW viewer:
            // settling them here would suppress the pending forced broadcast
            // that clears a stale glow bit from viewers whose team changed
            // before this pairing, leaving that bit stuck until the next bump.
            // The counters settle on the next smartForcePacket round instead —
            // one forced broadcast at most.
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

        // Same reasoning as the non-teammate branch above: only cachedTeamName
        // is settled, so a pending cleanup broadcast for existing viewers is
        // delivered by the next quiet tick instead of being skipped.
        cachedTeamName = glowingTeam.getName();
    }

    // ========== Helpers ==========

    /**
     * Returns the {@link PlayerTeam} the given entity belongs to, if that team
     * has glow enabled. Returns {@code null} otherwise. Non-player entities
     * are looked up by their scoreboard name ({@link Entity#getScoreboardName()},
     * which is the UUID string for non-players).
     *
     * <p>Runs once per dirty packet, plus once per viewer inside the
     * {@code isTeammate} predicate in {@link #redirectSendData}. The O(1) hash
     * probes are accepted as-is — caching the team on the {@code ServerEntity}
     * would need precise invalidation that the global {@code syncEpoch} cannot
     * distinguish (entity switched teams vs viewer switched teams). See
     * AGENTS.md §10.2.
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
     * If this entity was previously customized by the mod but should no
     * longer glow, drop the stale 0x40 bit and settle the caches. When the
     * current packet already carries the shared-flags entry, the broadcast
     * that follows applies the no-glow flags anyway — sending a second
     * flags-only packet here would be redundant. A continuously-dirty entity
     * (a drowning player's AIR_SUPPLY, a frozen entity's ticksFrozen) never
     * lets {@code packDirty()} return null, so {@code smartForcePacket} never
     * fires and the stale glow bit would otherwise linger on clients until
     * the data stops changing.
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

        // Fast bails for states that can never be customized. Each one also
        // drops a stale mod glow left over from a previous state — see
        // clearStaleGlow for why the packet and the caches both matter.
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

        // No team has glow enabled — nothing to customize for any viewer,
        // so skip the per-packet scoreboard/effect lookups entirely.
        if (!config.hasEnabledTeams()) {
            clearStaleGlow(sync, dataPacket, config);
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
                        & GlowConstants.FLAG_GLOWING) != 0;
        if (vanillaGlow) {
            // Settle the counters when stale. Without this, a vanilla-glowing
            // entity in a glow-enabled team keeps tripping smartForcePacket's
            // mismatch check on every quiet tick (the forwarded packet never
            // settles the counters), forcing one redundant broadcast per tick
            // forever — the one-shot-per-bump budget in AGENTS.md §10.1
            // silently becomes unbounded. Safe to settle: while vanilla glow
            // is active the mod's bit is invisible to every viewer, and the
            // next flags change flows through the split path below, which
            // clears any stale bit. The scoreboard lookup is skipped entirely
            // when nothing is stale.
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

        // Single scoreboard lookup per dirty send — it drives both the stale-
        // glow cleanup below and the per-viewer teammate predicate. See
        // AGENTS.md §10.2 for why the team is not cached on the ServerEntity.
        PlayerTeam entityTeamObj = getGlowingTeam(entity);

        // Previously customized by the mod but no longer in a glowing team:
        // drop the stale glow bit. If the current packet already carries the
        // flags entry, the plain forward below applies the no-glow value and
        // clearStaleGlow just settles the caches without a second broadcast.
        if (cachedTeamName != null && entityTeamObj == null) {
            clearStaleGlow(sync, dataPacket, config);
        }

        // Entity not in a glowing team → no glow customization needed.
        if (entityTeamObj == null) {
            sync.sendToTrackingPlayersAndSelf(packet);
            return;
        }

        Predicate<ServerPlayer> isTeammate =
                viewer -> entityTeamObj.equals(viewer.getTeam());

        // Viewer-side resync: when the sync epoch or config version moved
        // since this entity's last sync, viewers that left the glowing team
        // still carry the mod's 0x40 bit client-side. A continuously-dirty
        // entity (drowning AIR_SUPPLY, ticksFrozen, a mob farm) never lets
        // packDirty() return null, so smartForcePacket never forces a flags
        // packet for it — the no-glow broadcast below must carry the
        // shared-flags entry itself to clear the stale bit. Each bump then
        // costs one extra flags item per dirty packet for exactly one resync
        // round, after which the standing per-packet cost resumes.
        //
        // The three caches settle together. The stale counters may stem from
        // this entity's OWN team change rather than a viewer's — settling
        // only cachedSyncEpoch/cachedConfigVersion would leave cachedTeamName
        // stale (e.g. null after a dirty join), and the next quiet tick's
        // fast bail in smartForcePacket would then trust it. When the entity
        // later leaves the glowing team, the never-glow bail would see
        // cachedTeamName == null and skip the forced clear, leaving
        // ex-teammates with the stale 0x40 bit indefinitely. entityTeamObj
        // is non-null here, so syncing to it is always safe.
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
     * {@code forceIncludeFlags} extends the same treatment to the no-glow
     * variant: a viewer whose team membership just changed still carries the
     * stale 0x40 bit client-side, and only a flags entry in the broadcast
     * can clear it. Unlike the glow overlay, the forced no-glow variant
     * keeps every dirty entry and APPENDS the flags entry — the no-glow
     * broadcast is the only delivery of those entries, and replacing the
     * packet with a flags-only entry would drop them for all viewers.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ClientboundSetEntityDataPacket modifyGlowFlag(
            ClientboundSetEntityDataPacket packet, boolean shouldGlow,
            boolean forceIncludeFlags) {

        int sharedFlagsId = EntityAccessor.getSharedFlagsId().id();

        // Read-only pre-check: when the packet already carries the requested
        // glow state, reuse the original packet without allocating a copied
        // item list — this is the common case, since flags rarely change.
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
                // item.id() == sharedFlagsId was verified above — reuse
                // it instead of hardcoding the flags slot id (0).
                SynchedEntityData.DataValue rawItem =
                        new SynchedEntityData.DataValue(
                                item.id(), item.serializer(), newValue);
                items.set(i, rawItem);
                break;
            }
        }
        if (!foundFlag) {
            // No flags entry in the packet — the shared-flags value must be
            // derived from the entity's current flags (a bare 0x40 would
            // replace the whole byte on the client and wipe the other flag
            // bits). The glow variant is cached while the server flags are
            // unchanged: a continuously-dirty glowing entity would otherwise
            // allocate a new packet every tick, and the other dirty entries
            // were already delivered by the no-glow broadcast, so a
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
            // Forced no-glow variant — only reachable with forceIncludeFlags
            // (the non-forced case returned the original packet above). Keep
            // every dirty entry and append the no-glow flags entry: the
            // no-glow broadcast is the only delivery of the dirty entries
            // this round, so a flags-only packet would drop them for all
            // viewers. Built at most once per epoch bump, so it is not
            // cached.
            items.add(new SynchedEntityData.DataValue<>(
                    sharedFlagsId, EntityDataSerializers.BYTE,
                    (byte) (current & GlowConstants.GLOW_CLEAR_MASK)));
        }
        return new ClientboundSetEntityDataPacket(packet.id(), items);
    }
}
