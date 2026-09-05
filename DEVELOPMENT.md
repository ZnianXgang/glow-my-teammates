# Glow My Teammates — Development Guide

Server-side Fabric mod for Minecraft 26.1/26.2 (Mojang mappings). It never creates or manages teams — it watches the vanilla `/team` system and customizes the glow bit (`Entity.DATA_SHARED_FLAGS_ID`, bit `0x40`) that the server sends to each client.

## 1. Mental model

1. **Glow is per-viewer, not per-entity.** The server broadcasts a no-glow variant of every entity-data packet, then overlays a glow variant to teammates only. Netty's per-connection FIFO ordering guarantees the overlay arrives last.
2. **Everything is event-driven.** The mod reacts to exactly three kinds of events: entity data going dirty, a new viewer entering tracking range, and team/config changes. In steady state it does nothing.
3. **Caches are invalidated, not guessed.** Three monotonically increasing counters (`version`, `syncEpoch`, disk `configVersion`) tell the mixins when a previously-sent glow state may be stale. Never recompute state that cannot be proven stale.

## 2. Repository map

- `GlowMyTeammates.java` — ModInitializer: server lifecycle hooks, command registration
- `GlowConstants.java` — Glow flag constants (0x40/0xBF); plain class, not a mixin interface (§8.9)
- `WaypointSync.java` — Locator-bar connection rebuilds (§5)
- `config/GlowConfigManager.java` — Singleton: runtime state + per-world JSON persistence + server reference
- `command/GlowCommand.java` — `/teamglow` command tree
- `mixin/ServerEntityMixin.java` — Core glow engine (3 injection points, §3.2)
- `mixin/ScoreboardMixin.java` — Team-membership change detection → syncEpoch + waypoint rebuild
- `mixin/LivingEntityMixin.java` — Locator-bar filter (MixinExtras `@ModifyReturnValue`)
- `mixin/EntityAccessor.java` — `@Accessor` for `Entity.DATA_SHARED_FLAGS_ID`
- `src/main/resources/` — fabric.mod.json (`environment: "*"`), mixins.json, server-side lang files

## 3. The glow pipeline

### 3.1 Packet flow (per entity, per tick)

```
ServerEntity.sendDirtyEntityData()
  ├─ SynchedEntityData.packDirty() ──── null? → smartForcePacket (only forces when state changed)
  │       └─ returns List<DataValue> → vanilla continues to sendToTrackingPlayersAndSelf
  │               └─ @Redirect redirectSendData intercepts the call, per-viewer customization:
  │                    1. no-glow copy  → broadcast to tracking set + self
  │                    2. glow copy     → sendToTrackingPlayersFiltered(teammates only)
  └─ addPairing(player) (first sighting) → onAddPairing sends the correct initial state exactly once
```

`sendDirtyEntityData()` contains two `Synchronizer.sendToTrackingPlayersAndSelf` calls: the entity-data packet (redirected, `ordinal = 0`) and a `ClientboundUpdateAttributesPacket` (ordinal 1, left alone).

### 3.2 The three hooks of `ServerEntityMixin`

| Hook | Fires when | Job |
|---|---|---|
| `smartForcePacket` (`@ModifyVariable` on the `packDirty()` result) | `packDirty()` returned `null` | Force a packet only if the cached counters are stale; otherwise return `null` so vanilla skips the packet |
| `onAddPairing` (`@Inject` TAIL on `addPairing`) | A new viewer starts tracking the entity | Send the correct glow state immediately; seed only `cachedTeamName` — epoch/config counters stay unsettled so a pending cleanup broadcast for existing viewers is not skipped |
| `redirectSendData` (`@Redirect` on `sendToTrackingPlayersAndSelf`, ordinal 0) | Every dirty-data broadcast | Build no-glow + glow copies, broadcast then overlay (§3.1) |

- Self is excluded from its own tracking set (`ChunkMap.TrackedEntity.updatePlayer`), so `sendToTrackingPlayersAndSelf` already covers self. **Deliberate**: a glowing player does NOT see their own glow in third-person view.
- **Vanilla glow is never touched**: spectral arrows, potions, `/effect glowing`, `setGlowingTag` all flow through unchanged. The mod only adds/clears its own bit on top of the server's current flags — `modifyGlowFlag` always rebuilds the byte from server flags, never from a bare 0x40, so the other shared-flag bits are never wiped.

### 3.3 Viewer-side team changes (`ScoreboardMixin`)

Three `@Inject`s funnel into `onTeamChange(Scoreboard, PlayerTeam, Collection<String>)`: bumps `syncEpoch` only for glow-enabled teams (and only while the mod is enabled), and rebuilds affected players' locator-bar connections when `locator_bar_teammates_only` is on (§5).

- `Scoreboard.addPlayerToTeam(String, PlayerTeam)` (RETURN, checks the return value)
- `Scoreboard.removePlayerFromTeam(String, PlayerTeam)` (two-arg, RETURN) — the single-arg overload is deliberately NOT hooked: on success it internally calls the two-arg version, so hooking both would double-bump
- `Scoreboard.removePlayerTeam(PlayerTeam)` — required: `/team remove` clears `teamsByPlayer` directly, bypassing both hooks above. Passes the whole `team.getPlayers()` list (never emptied by `removePlayerTeam`)

The two-arg `removePlayerFromTeam` **throws `IllegalStateException`** for non-members — the RETURN hook only ever fires for real removals.

## 4. Config & invalidation model (`GlowConfigManager`)

### 4.1 Three counters, three jobs

| Counter | Type | Job | Bumped by |
|---|---|---|---|
| `version` | runtime `long` | Entity-side cache invalidation (`cachedConfigVersion`) | Every state change via the idempotent setters |
| `syncEpoch` | runtime `long` | Viewer-side cache invalidation (`cachedSyncEpoch`) | `bumpSyncEpoch()` from team changes in glow teams |
| `configVersion` | disk `int[]` `[major, minor]` | Disk schema version, *not* a cache counter | Migration only |

`version`/`syncEpoch` are never reset — they only grow across server starts. That's fine: the caches are per-`ServerEntity` instance (re-created as `0` each restart) and re-seeded by `onAddPairing`, so the first comparison always sees a mismatch and forces a full lookup.

### 4.2 Idempotency rule

`setEnabled`, `setLocatorBarTeammatesOnly`, `setNonPlayerGlow`, `removeTeam` are no-ops when the value is unchanged (guarded before the `version++`), otherwise they bump `version` and force a pointless full-server resync. Preserve this in new setters.

`addTeam` is the known exception: NOT idempotent, relies on `GlowCommand.addTeam` rejecting already-enabled teams. Keep that command-side guard.

### 4.3 Load & migration order (in `loadFromWorld`)

Parse → read the `config` sub-object → migrate legacy schemas (missing `configVersion` or the pre-1.1.1 key `locatorBarHideOtherGlowingTeams`) → only then `version++`. The migration write is itself a config change, and the cache-invalidation semantics depend on the counter reflecting it; adding a new switch later = minor bump only, no migration code.

Broken or literal-null config → `resetToDefaultsAndPersist()` — defaults, repair-save, then `version++` (persist-before-bump). Persistence is atomic (tmp file + `ATOMIC_MOVE`, retry fallback for Windows locks); a failed `save()` is reported back so commands roll back their in-memory state.

## 5. Locator-bar filter (`LivingEntityMixin`)

MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)` (returns `Optional<WaypointTransmitter.Connection>`). Returning `Optional.empty()` makes `ServerWaypointManager.createConnection` tear the connection down — no extra cleanup needed.

Semantics are **asymmetric and receiver-driven**:
1. Switch off → `original` (vanilla).
2. Receiver not in a glow-enabled team (teamless or non-glow) → `original` (sees everyone).
3. Receiver in a glow-enabled team → only same-team members stay visible; every other entity is hidden (`!receiverTeam.equals(myTeam)` → `Optional.empty()`). No separate glow check on `myTeam` needed: equals already implies the same glow-enabled team.

Connection re-evaluation (`WaypointSync`, all methods server-thread only):
- **Filter rules changed** (switch toggles, `team add/remove`, `on|off`): `WaypointSync.rebuildAll` — every player-transmitted connection in every dimension.
- **Team membership changed** while the switch is on: `ScoreboardMixin` → `WaypointSync.rebuildForPlayer` per affected player. Required because the filter is **receiver-driven** — vanilla's own rebuilds only cover the changed player as a *sender*. `rebuildForPlayer` only marks the dimension; the rebuild runs once per tick at the boundary (`END_SERVER_TICK` → `flushPendingRebuilds`), which collapses bursts and guarantees the pass sees the *final* team state. Pending set cleared on `SERVER_STOPPING`.

The rebuild call is `ServerLevel.getWaypointManager().remakeConnections(player)` — the same call vanilla's `updateTeamWaypoints` makes. No Stonecutter gates: the interface is identical in 26.1/26.2.

## 6. Commands & permissions (`GlowCommand`)

The command tree and its permission nodes (with fallbacks) are listed in README's command table.

- Permission nodes via `PermissionPredicates.require(node, fallbackLevel)` (Fabric `permission.v1`); `PermissionLevel` is the **Mojang** enum `net.minecraft.server.permissions.PermissionLevel`. LuckPerms needs no adapter.
- The bare `/teamglow` shortcut checks its status permission **inside the executor** — a root `requires()` would AND it into every subcommand (Brigadier semantics).
- Config-switch messages use one generic key `glow.teammates.config.set` — new switches never need a lang-file entry.
- Every mutating command validates the `save()` result and rolls the in-memory state back on failure.
- Team suggestions **must** narrow by the typed prefix (`suggestMatchingTypedPrefix`, case-insensitive `startsWith` on `builder.getRemainingLowerCase()`) — vanilla's own team argument filters as you type, and Brigadier does not filter for you.

## 7. Multi-version build & workflow (Stonecutter)

**26.1+ background**: Minecraft 26.1 (the first 2026-renamed release) removed obfuscation and raised the minimum Java to 25. With obfuscation gone, **Yarn is unavailable from 26.1 on** — 26.1+ mods use **Mojmap** (official names ship in the jar, so `build.gradle` has no `mappings` line).

- **VCS version is 26.2** — the canonical source in `src/`. Always commit from it.
- Per-version deps live in `versions/<mc>/gradle.properties`; server-translations-api differs per MC and is bundled with `implementation include(...)`.
- Version-gated code uses `//? if 26.2 { ... } //?} else { ... }`. Currently **no** source file needs gates.
- `./gradlew build` (all versions); `./gradlew setActiveVersion -Pversion=26.1` (IDE); `./gradlew "Reset active project"` (restore VCS source — **run before every commit**).
- A bare `./gradlew build` is a **dev build** — the version gets a `-dev` suffix (`1.1.2-dev+26.2`), so the jar and its `fabric.mod.json` version can never be mistaken for a release. Pass `-Prelease` for the clean release version (`1.1.2+26.2`). Only distribute `-Prelease` jars.
- Work directly on `main`; conventional commits (`feat:`/`fix:`/`refactor:`/`docs:`/`chore:`), single concern per commit. Before committing: Reset active project, then verify `./gradlew build` passes.
- Version bumps live in root `gradle.properties` (`mod_version`); jar names and `fabric.mod.json` follow automatically. Keep README's config example and jar-name lines in sync with the version.

**Mixin anchors to re-verify on every MC upgrade** (`defaultRequire: 1` fails loudly if any breaks):
- `ServerEntityMixin#smartForcePacket` — `@ModifyVariable` on `sendDirtyEntityData`'s `packDirty()` result (`INVOKE_ASSIGN`, `ordinal = 0`)
- `ServerEntityMixin#redirectSendData` — `@Redirect` on `ServerEntity$Synchronizer.sendToTrackingPlayersAndSelf(Packet)`, `ordinal = 0`
- `ServerEntityMixin#onAddPairing` — `@Inject` TAIL on `ServerEntity.addPairing(ServerPlayer)`
- `ScoreboardMixin` — three `@Inject`s with explicit descriptors
- `LivingEntityMixin#filterWaypointByTeam` — `@ModifyReturnValue` on `makeWaypointConnectionWith(ServerPlayer)`
- Dependency assumptions: single-arg `removePlayerFromTeam` calls the two-arg overload; the two-arg overload throws on non-members; `TrackedEntity.updatePlayer` excludes self from `seenBy`; server-side `Entity.isCurrentlyGlowing()` reflects `hasGlowingTag` only, `LivingEntity` overrides it to also cover the GLOWING effect.

## 8. Rules that will bite you

1. **Never toggle entity data directly** (`entity.getEntityData().set(...)`) — it corrupts server-side state and desyncs vanilla. Always inject missing packets (`@ModifyVariable`/`@Redirect`/`@Inject`) instead.
2. **Mojang mappings only.** `Identifier` is `net.minecraft.resources.Identifier`, not `ResourceLocation`; `net.minecraft.server.permissions.PermissionLevel`, not a Fabric enum.
3. **Non-glow team changes must not bump `syncEpoch`** — auto-team plugins cause constant membership churn; bumping for non-glow teams would resync the whole server for nothing.
4. **Idempotent setters or pay the resync cost** (§4.2 — and keep the command-side guard on `addTeam`).
5. **`configVersion` migration must precede `version++`** (§4.3).
6. **Mixin target classes load on the client too** (`environment: "*"`). The `ServerEntity`/`LivingEntity` hooks are harmless there, but `ScoreboardMixin` is the exception — `ClientPacketListener` mutates the *client* scoreboard from the client thread in singleplayer/LAN, so its hooks **do** fire off the server thread. `onTeamChange` must ignore anything that is not the server's own scoreboard on the server thread (`server.getScoreboard() == this && server.isSameThread()`, `server` null before `SERVER_STARTED`) before touching the shared counters or `WaypointSync`; the server fires the same hook on its own thread before broadcasting the team packet, so nothing is lost. Never put client-only code in a shared mixin.
7. **Server-Translations keys live in `data/<modid>/lang/`, not `assets/`** — the server reads the former.
8. **Never define fields in `@Mixin` interfaces** — even `static final` constants are injected into the target class and fail validation unless `@Shadow` (`InvalidInterfaceMixinException`). Shared constants live in `GlowConstants`.

## 9. Deliberate non-optimizations

### 9.1 Global `syncEpoch` resync stays global

Any membership change in a glow-enabled team bumps the shared `syncEpoch`; every glowing entity then forces one broadcast to all its tracking viewers. Kept global on purpose: per-team granularity is a correctness liability (a viewer switching teams changes which entities need resyncing). The one-shot-per-bump bill only covers *quiet* entities — a continuously-dirty glowing entity rides the per-packet path: `redirectSendData` detects the stale counters, appends one shared-flags item to that round's no-glow broadcast, and settles all three caches right after.

### 9.2 Per-packet scoreboard lookups are accepted

One `getPlayersTeam` lookup per entity per dirty send plus one per viewer in the `isTeammate` predicate. Each is an O(1) hash probe, dwarfed by the two packet allocations every dirty send already pays; caching the entity's team would need precise invalidation the global `syncEpoch` cannot distinguish.

### 9.3 `clearNonPlayerGlow` is a one-shot command

Iterates every non-player entity once when `non_player_glow` is switched off. It builds a chunk → tracking-players map (one O(players × tracked chunks) pass), so the per-entity work is a hash lookup; at command frequency this is acceptable and not worth optimizing.
