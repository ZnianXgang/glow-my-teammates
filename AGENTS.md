# AGENTS.md — Glow My Teammates

## 1. The mental model

This mod makes teammates glow for each other on Minecraft 26.1/26.2 (Fabric, Mojang mappings). It never creates or manages teams — it only *watches* the vanilla `/team` system and *customizes the glow bit* (`Entity.DATA_SHARED_FLAGS_ID`, bit `0x40`) that the server sends to each client.

Three ideas hold the whole design together:

1. **Glow is per-viewer, not per-entity.** The server broadcasts a no-glow variant of every entity-data packet, then overlays a glow variant to teammates only. Netty's per-connection FIFO ordering guarantees the overlay arrives last, so teammates end up with the bit set and everyone else does not.
2. **Everything is event-driven.** No per-tick loops. The mod reacts to exactly three kinds of events: entity data going dirty, a new viewer entering tracking range, and team/config changes. In steady state it does nothing.
3. **Caches must be invalidated, not guessed.** Three monotonically increasing counters (`version`, `syncEpoch`, and the disk `configVersion`) tell the mixins when a previously-sent glow state may be stale. The mixin never recomputes state it cannot prove is stale.

## 2. Repository map

```
settings.gradle                     Stonecutter: versions '26.1', '26.2', vcsVersion = 26.2
build.gradle                        Shared script; per-version deps via ${property(...)}
gradle.properties                   mod_version (1.1.1), maven_group, loom_version
versions/<mc>/gradle.properties     Per-version: minecraft/loader/fabric-api/server-translations versions
src/main/resources/
  fabric.mod.json                   environment: "*" (loads in singleplayer/LAN too)
  glow-my-teammates.mixins.json     Registers the 4 mixins below
  data/glow-my-teammates/lang/      en_us.json + zh_cn.json — translated server-side (Server-Translations API)
src/main/java/com/glow/teammates/
  GlowMyTeammates.java              ModInitializer: hooks SERVER_STARTED/SERVER_STOPPING + command registration
  GlowConstants.java                Shared glow flag constants (0x40 / 0xBF) — plain class, see rule §8.9
  WaypointSync.java                 Locator-bar connection rebuilds (command-wide + per-affected-player, see §5)
  config/GlowConfigManager.java     Singleton holding all runtime state + JSON persistence + server reference
  command/GlowCommand.java          /teamglow tree
  mixin/
    ServerEntityMixin.java          Core glow engine (3 injection points, see §3.2)
    ScoreboardMixin.java            Team-membership change detection → syncEpoch + waypoint rebuild
    LivingEntityMixin.java          Locator-bar filter (MixinExtras @ModifyReturnValue)
    EntityAccessor.java             @Accessor for Entity.DATA_SHARED_FLAGS_ID
```

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

`sendDirtyEntityData()` contains **two** `Synchronizer.sendToTrackingPlayersAndSelf` calls: the entity-data packet (the one redirected, `ordinal = 0`) and a `ClientboundUpdateAttributesPacket` (ordinal 1, left alone — it is not a `ClientboundSetEntityDataPacket` anyway).

### 3.2 The three hooks of `ServerEntityMixin`

| Hook | Fires when | Job |
|---|---|---|
| `smartForcePacket` (`@ModifyVariable` on the `packDirty()` result) | `packDirty()` returned `null` | Force a packet **only** if `cachedTeamName`/`cachedConfigVersion`/`cachedSyncEpoch` are stale; otherwise return `null` so vanilla skips the packet entirely |
| `onAddPairing` (`@Inject` TAIL on `addPairing`) | A new viewer starts tracking the entity | Send the correct glow state immediately, and seed the caches so `smartForcePacket` won't redundantly force later |
| `redirectSendData` (`@Redirect` on `sendToTrackingPlayersAndSelf`, ordinal 0) | Every dirty-data broadcast | Build no-glow + glow copies, broadcast then overlay (see §3.1) |

`smartForcePacket` has three optimizations worth not breaking:
- **Fast bail**: if `version` and `syncEpoch` are both unchanged, the entity's team cannot have changed — skip the scoreboard lookup, return `null`.
- **Never-glow bail**: if the entity is not in a glowing team now *and* wasn't at last sync, update the caches and return `null` — later epoch/config bumps won't re-run lookups for it forever.
- **Non-player early exit**: when `non_player_glow` is off, non-player entities can never be customized — the early-exit branch also syncs `cachedTeamName`/`cachedConfigVersion`/`cachedSyncEpoch`, so a mob that glowed before the switch was turned off never leaves stale cached state behind.

`redirectSendData` fast paths (in order): not a `ClientboundSetEntityDataPacket` → forward; non-player **and** `non_player_glow` off → forward; mod disabled → forward; `hasEnabledTeams()` false → forward; entity currently glowing (`isCurrentlyGlowing()` — LivingEntity overrides it to cover the GLOWING effect *and* `setGlowingTag`, non-living entities are checked via the same shared-flags bit) → forward. The entity-team lookup is hoisted out of the per-viewer predicate — computed once. The glow-only overlay packet is cached per server-flags value (`cachedGlowPacket`/`cachedGlowFlags`) so continuously-dirty glowing entities don't allocate a new packet every tick.

The old explicit self-send was deleted: `ChunkMap.TrackedEntity.updatePlayer` excludes the entity from its own tracking set, so `sendToTrackingPlayersAndSelf` already covers self.

**Deliberate behavior**: a glowing player does NOT see their own glow in third-person view — self always receives the no-glow variant and the tracking set excludes self, so only teammates see the glow. Kept this way on purpose.

**Vanilla glow is never touched**: spectral arrows, potions, `/effect glowing` and `setGlowingTag` all flow through unchanged (the `vanillaGlow` fast path forwards the original packet). The mod only ever adds or clears its *own* bit on top of the server's current flags — `modifyGlowFlag` always rebuilds the byte from the server flags, never from a bare `0x40`, so the other shared-flag bits (FALL_FLYING, SPRINTING, INVISIBLE, ON_FIRE…) are never wiped.

### 3.3 Viewer-side team changes (`ScoreboardMixin`)

Three `@Inject`s funnel into one `onTeamChange(PlayerTeam, Collection<String>)` which bumps `syncEpoch` **only for glow-enabled teams** (and only while the mod is enabled), and additionally rebuilds the affected players' locator-bar connections when `locator_bar_teammates_only` is on (see §5):
- `Scoreboard.addPlayerToTeam(String, PlayerTeam)` (RETURN, checks the return value)
- `Scoreboard.removePlayerFromTeam(String, PlayerTeam)` (two-arg, RETURN) — the single-arg overload is deliberately NOT hooked: on success it internally calls the two-arg version, so hooking both would double-bump
- `Scoreboard.removePlayerTeam(PlayerTeam)` — required: `/team remove` clears `teamsByPlayer` directly, bypassing both hooks above. Without it, glow would linger forever on viewers. This one passes the whole `team.getPlayers()` list — `removePlayerTeam` never empties the team's own player set (vanilla's own `ServerScoreboard.onTeamRemoved` relies on the same fact), so every member gets their waypoint connections rebuilt.

All three carry explicit method descriptors so future overload additions can't silently break them.

The two-arg `removePlayerFromTeam` **throws `IllegalStateException`** when the player is not a member of the given team (it does *not* no-op) — so the RETURN hook only ever fires for real removals. Do not "simplify" the hook based on an assumption of silent no-ops.

The waypoint rebuild needs the running server; `GlowConfigManager` holds a `MinecraftServer` reference set in `loadFromWorld` and cleared on `SERVER_STOPPING` (`Entity.getServer()` is gone in 26.1+, rule §8.1).

## 4. Config & invalidation model (`GlowConfigManager`)

### 4.1 Three counters, three jobs

| Counter | Type | Job | Bumped by |
|---|---|---|---|
| `version` | runtime `long` | Entity-side cache invalidation (`cachedConfigVersion`) | Every state change via the idempotent setters |
| `syncEpoch` | runtime `long` | Viewer-side cache invalidation (`cachedSyncEpoch`) | `bumpSyncEpoch()` from team changes in glow teams |
| `configVersion` | disk `int[]` `[major, minor]` | Disk schema version, *not* a cache counter | Migration only |

`version` and `syncEpoch` live entirely in RAM and reset every server start — that is fine, caches are per-`ServerEntity` instance and are re-seeded by `onAddPairing`.

### 4.2 Idempotency rule

`setEnabled`, `setLocatorBarTeammatesOnly`, `setNonPlayerGlow` and `removeTeam` are no-ops when the value is unchanged (guarded before the `version++`), otherwise they bump `version` and force a pointless full-server resync. This is a standing requirement — preserve it in new setters.

`addTeam` is the known exception: the setter itself is **not** idempotent, and relies on `GlowCommand.addTeam` rejecting teams that are already enabled. Keep that command-side guard; do not add a second path that calls `addTeam` without checking first.

### 4.3 Load & migration order (in `loadFromWorld`)

1. Parse JSON into `ConfigData` (fields `configVersion`/`config` default to `null` = legacy marker).
2. Read the `config` sub-object into the singleton fields.
3. If `configVersion` is missing (legacy) or the pre-1.1.1 key `locatorBarHideOtherGlowingTeams` is present, call `save()` to rewrite the file with the current schema `[1, 1]`.
4. Only then `version++`.

Step 3 must run **before** step 4: the migration write is itself a config change, and the cache-invalidation semantics depend on the counter reflecting it. Adding a new switch later = minor bump only, no migration code, Gson fills the default.

**Broken or literal-null config**: a JSON parse exception, or a file containing the literal `null` (Gson returns `null` without throwing), both go through `resetToDefaultsAndPersist()` — reset to defaults, call `save()` to repair the file, then `version++` (the same persist-before-bump rule as the migration path).

Persistence is atomic: write to `<name>.tmp`, then `Files.move` with `ATOMIC_MOVE`, falling back to `REPLACE_EXISTING` with a few retries on Windows/antivirus transient locks. A failed `save()` is reported back to the command so the caller can roll back the in-memory state.

## 5. Locator-bar filter (`LivingEntityMixin`)

MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)` (returns `Optional<WaypointTransmitter.Connection>`). Returning `Optional.empty()` makes `ServerWaypointManager.createConnection`'s `ifPresentOrElse` tear the connection down — no extra cleanup needed.

Semantics are **asymmetric and receiver-driven**:
1. Switch off → return `original` (vanilla).
2. Receiver not in a glow-enabled team (teamless or non-glow team) → return `original` (sees everyone).
3. Receiver in a glow-enabled team → only same-team members stay visible; every other entity — members of other teams (glow-enabled or not) and teamless entities — is hidden (`!receiverTeam.equals(myTeam)` → `Optional.empty()`). No separate glow check on `myTeam` is needed: `equals(receiverTeam)` already implies the same glow-enabled team.

Connection re-evaluation (`WaypointSync`, all methods server-thread only):

- **Filter rules changed** (switch toggles, `team add/remove`, `on|off`): `WaypointSync.rebuildAll` rebuilds every player-transmitted connection in every dimension. Command path in `GlowCommand`.
- **Team membership changed** while the switch is on: `ScoreboardMixin` calls `WaypointSync.rebuildForPlayer` for each affected player, rebuilding every player-sent connection in that player's dimension. This is required because the filter is **receiver-driven** — vanilla's own team-change rebuilds (`ServerScoreboard.updatePlayerWaypoint` / `updateTeamWaypoints`) only cover the changed player as a *sender*; without the mod's receiver-side pass, the changed player's own bar would keep showing non-teammates until a connection happens to turn `isBroken()` (distance/chunk changes), which may never happen for an AFK player.
- `rebuildForPlayer` only marks the affected player's dimension; the rebuild itself runs once per tick at the boundary (`ServerTickEvents.END_SERVER_TICK` → `WaypointSync.flushPendingRebuilds`). A burst of membership changes in one tick collapses into one pass, and — because `Scoreboard.addPlayerToTeam` removes-then-adds mid-tick — deferring guarantees the pass sees the *final* team state: an inline rebuild on the remove hook would evaluate a switcher between two glow-enabled teams as teamless and leave their own bar showing everyone. The pending set is cleared on `SERVER_STOPPING` so unloaded dimension instances don't linger across integrated-server restarts.

The rebuild call itself is `ServerLevel.getWaypointManager().remakeConnections(player)` — the same call vanilla `ServerScoreboard.updateTeamWaypoints` makes on team changes. Only player transmitters are rebuilt; non-player entities do not transmit waypoints by default (`WAYPOINT_TRANSMIT_RANGE` defaults to 0).

No Stonecutter version gates needed: the interface signature is identical in 26.1/26.2 (the `LocatorBarRenderer` vs `LocatorBar` client-class difference only matters for the client-side filtering route, which was intentionally not taken). MixinExtras is compile-time only (`compileOnly io.github.llamalad7:mixinextras-fabric:0.5.4`); the runtime is bundled with fabric-loader.

## 6. Commands & permissions (`GlowCommand`)

```
/teamglow
├── on | off                    glow-my-teammates.command.on | .off  (fallback OP 2)
├── status                      glow-my-teammates.command.status   (fallback: all)
├── team
│   ├── add <team>              glow-my-teammates.command.team.add    (fallback OP 2)
│   ├── remove <team>           glow-my-teammates.command.team.remove (fallback OP 2)
│   └── list                    glow-my-teammates.command.team.list   (fallback: all)
└── config                       glow-my-teammates.command.config     (fallback OP 2)
    ├── (no argument → list)     same node
    ├── locator_bar_teammates_only <bool>        same node
    └── non_player_glow <bool>              same node
```

- Permission nodes are plain `Identifier` strings via `PermissionPredicates.require(node, fallbackLevel)` (Fabric `permission.v1`). `CommandSourceStack` gets the `PermissionContextOwner` interface through a **ClassTweaker** `transitive-inject-interface` in the fabric-permission-api module — this is why `.requires(...)` type-checks at compile time.
- `PermissionLevel` is the **Mojang** enum `net.minecraft.server.permissions.PermissionLevel` (ALL..OWNERS), *not* a Fabric class.
- LuckPerms works with no adapter: unset nodes return DEFAULT and fall back to the OP check; explicit `true`/`false` overrides it.
- The bare `/teamglow` shortcut checks its own status permission **inside the executor** — putting a `requires()` on the root node would AND it into every subcommand (Brigadier semantics) and wrongly gate `on`/`off`/`team`/`config`.
- Config-switch messages use one generic key `glow.teammates.config.set` (`%1$s set to %2$s.`) so new switches never need a lang-file entry.
- Every mutating command validates the `save()` result and rolls the in-memory state back on failure, so a write error is surfaced in chat instead of silently half-applying.

## 7. Multi-version build (Stonecutter)

- **VCS version is 26.2** — the canonical source in `src/`. Always commit from it.
- `versions/<mc>/gradle.properties` hold per-version dependency versions; `build.gradle` reads them via `${property(...)}`. The `server-translations-api` version differs per MC (3.0.3+26.1 / 3.1.0+26.2) and is bundled with `implementation include(...)`.
- Version-gated code uses `//? if 26.2 { ... } //?} else { ... }`. Currently **no** source file needs gates — the only cross-version API differences documented (locator-bar client classes) were avoided by the server-side design.
- Commands: `./gradlew build` (all versions); `./gradlew setActiveVersion -Pversion=26.1` (IDE); `./gradlew "Reset active project"` (restore VCS source — **run before every commit**).
- **Mixin anchors to re-verify on every MC upgrade** (`defaultRequire: 1` fails loudly if any breaks):
  - `ServerEntityMixin#smartForcePacket` — `@ModifyVariable` on `sendDirtyEntityData`'s `SynchedEntityData.packDirty()` result (`INVOKE_ASSIGN`, `ordinal = 0`)
  - `ServerEntityMixin#redirectSendData` — `@Redirect` on `ServerEntity$Synchronizer.sendToTrackingPlayersAndSelf(Packet)`, `ordinal = 0` (first call only — the attributes packet in the same method is intentionally not redirected)
  - `ServerEntityMixin#onAddPairing` — `@Inject` TAIL on `ServerEntity.addPairing(ServerPlayer)`
  - `ScoreboardMixin` — three `@Inject`s with explicit descriptors on `addPlayerToTeam(String, PlayerTeam)`, `removePlayerFromTeam(String, PlayerTeam)`, `removePlayerTeam(PlayerTeam)`
  - `LivingEntityMixin#filterWaypointByTeam` — MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)`
  - Dependency assumptions to check: `Scoreboard.removePlayerFromTeam(String)` (single-arg) calls the two-arg overload internally, and the two-arg overload throws on non-members; `ChunkMap.TrackedEntity.updatePlayer` excludes self from `seenBy`; `Entity.isCurrentlyGlowing()` on the server only reflects `hasGlowingTag`, with `LivingEntity` overriding it to also cover the GLOWING effect.

## 8. Rules that will bite you

1. **Never use `Entity.getServer()`** — removed in 26.1+. Use `entity.level().getScoreboard()` etc.
2. **Never toggle entity data directly** (`entity.getEntityData().set(...)`) — it corrupts server-side state and desyncs vanilla. Always inject missing packets (`@ModifyVariable`/`@Redirect`/`@Inject`) instead.
3. **Mojang mappings only.** All class/method names in code and mixin descriptors are official. `Identifier` is `net.minecraft.resources.Identifier`, not `ResourceLocation`; `net.minecraft.server.permissions.PermissionLevel`, not a Fabric enum.
4. **Non-glow team changes must not bump `syncEpoch`** — auto-team plugins cause constant membership churn; bumping for non-glow teams would resync the whole server for nothing.
5. **Idempotent setters or pay the resync cost** (§4.2 — and keep the command-side guard on `addTeam`).
6. **`configVersion` migration must precede `version++`** (§4.3).
7. **Mixin target classes load on the client too** (`environment: "*"`). Keep this safe: client-side *application* is harmless because the hooked server methods never run there — but never put client-only code in a shared mixin.
8. **Server-Translations keys live in `data/<modid>/lang/`, not `assets/`** — the server reads the former.
9. **Never define fields in `@Mixin` interfaces** — even `static final` constants are injected into the target class and fail validation unless `@Shadow` (`InvalidInterfaceMixinException`, crashes at startup, compiles fine). Shared constants live in `GlowConstants` (a plain class).

## 9. Workflow

- All work happens directly on `main`; commit per feature.
- Commit style: conventional commits (`feat:`/`fix:`/`refactor:`/`docs:`/`chore:`), single concern per commit.
- Before committing: `./gradlew "Reset active project"`, then verify `./gradlew build` (both versions) passes.
- Future version bumps live in root `gradle.properties` (`mod_version`) — jar names and `fabric.mod.json` follow automatically. Keep README's config-file example and jar-name lines in sync with the version.

## 10. Deliberate non-optimizations

Three "could this be faster?" items were analyzed and deliberately left alone. Revisit them only if the deployment profile changes, not as routine cleanup.

### 10.1 Global `syncEpoch` resync stays global

A membership change in any glow-enabled team bumps the shared `syncEpoch`, and every glowing entity forces one broadcast to all its tracking viewers on the next tick — O(glowing entities × viewers). This is kept global on purpose:

- **Fabric servers are small.** Large deployments run Paper/Folia; a Fabric server rarely exceeds ~100 players. A resync is a few hundred packets at most (dozens of glowing entities × dozens of viewers), one-shot per bump.
- **One round per bump, not per event.** `smartForcePacket` compares counters with `!=`, so a batch of membership changes inside one tick collapses into one forced broadcast per entity; caches re-align and nothing repeats.
- **Per-team granularity is a correctness liability.** Splitting `syncEpoch` per team would force tracking viewer-side changes too (a viewer switching teams changes which entities need resyncing) — the global epoch handles that for free.
- **The real guard is the default.** `non_player_glow` defaults off and the README warns against mob-dense farms; the one configuration that could make this cost visible is disabled by default. Keep it that way.

The "one-shot per bump" bill only covers *quiet* entities. `smartForcePacket` only fires when `packDirty()` returned `null`; a continuously-dirty glowing entity (AIR_SUPPLY, ON_FIRE, frozen ticks, a mob farm) never takes that path, so its resync rides on the per-packet path instead: `redirectSendData` detects the stale `cachedSyncEpoch`/`cachedConfigVersion`, appends one shared-flags item to that round's no-glow broadcast (keeping the dirty entries — that broadcast is their only delivery, so a flags-only replacement would drop them), and settles all three caches right after, `cachedTeamName` included: the stale counters may stem from the entity's *own* team change, and a counter-only settle would leave a stale or null `cachedTeamName` for `smartForcePacket`'s fast bail to trust, skipping the forced clear when the entity later leaves the team. The appended item is a correctness requirement, not vanity: the client applies only the listed items, so a viewer who left the glowing team would otherwise keep the stale 0x40 bit until the entity's flags happened to change. And entities that are not in a glow team broadcast nothing on a bump: the never-glow bail syncs their caches and returns `null`.

### 10.2 Per-packet scoreboard lookups are accepted

Once any team has glow enabled, every dirty entity-data send performs one `getPlayersTeam` lookup per entity (a single `getGlowingTeam` call, hoisted out of the per-viewer predicate) plus one per viewer in the `isTeammate` predicate. Accepted because:

- Each lookup is an O(1) hash probe, sub-microsecond, dwarfed by the two packet allocations and network writes every dirty send already pays.
- Caching the entity's team on the `ServerEntity` would need precise invalidation, but the global `syncEpoch` cannot distinguish "the entity switched teams" from "a viewer switched teams" — the cache would go stale or be discarded on every bump, i.e. useless.

Two clarifications from the 26.1/26.2 source audit:

- The per-viewer lookups only happen for entities that *are* in a glow team (`entityTeamObj != null`). An entity outside all glow teams costs exactly one `getPlayersTeam` probe and then forwards — zero per-viewer work.
- The viewer lookup is written as `viewer.getTeam()`, which is `Entity.getTeam()` = `level().getScoreboard().getPlayersTeam(getScoreboardName())` — identical to the inline version, no extra indirection.

### 10.3 `clearNonPlayerGlow` is a one-shot command

It iterates every non-player entity once when `non_player_glow` is switched off. It already builds a chunk → tracking-players map (one O(players × tracked chunks) pass), so the per-entity work is a hash lookup; at command frequency this is acceptable and not worth optimizing.
