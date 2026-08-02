# AGENTS.md — Glow My Teammates

## 1. The mental model

This mod makes teammates glow for each other on Minecraft 26.1/26.2 (Fabric, Mojang mappings). It never manages teams — it only *watches* the vanilla `/team` system and *customizes the glow bit* (`Entity.DATA_SHARED_FLAGS_ID`, bit `0x40`) that the server sends to each client.

Three ideas hold the whole design together:

1. **Glow is per-viewer, not per-entity.** The server broadcasts a no-glow variant of every entity-data packet, then overlays a glow variant to teammates only. Netty's per-connection FIFO ordering guarantees the overlay arrives last.
2. **Everything is event-driven.** No per-tick loops. The mod reacts to exactly three kinds of events: entity data going dirty, a new viewer entering tracking range, and team/config changes. In steady state it does nothing.
3. **Caches must be invalidated, not guessed.** Three monotonically increasing counters (`version`, `syncEpoch`, and the disk `config_version`) tell the mixins when a previously-sent glow state may be stale.

## 2. Repository map

```
settings.gradle                     Stonecutter: versions '26.1', '26.2', vcsVersion = 26.2
build.gradle                        Shared script; per-version deps via ${property(...)}
gradle.properties                   mod_version (1.1.0), maven_group, loom_version
versions/<mc>/gradle.properties     Per-version: minecraft/loader/fabric-api/server-translations versions
src/main/resources/
  fabric.mod.json                   environment: "*" (loads in singleplayer/LAN too)
  glow-my-teammates.mixins.json     Registers the 4 mixins below
  data/glow-my-teammates/lang/      en_us.json — translated server-side (Server-Translations API)
src/main/java/com/glow/teammates/
  GlowMyTeammates.java              ModInitializer: hooks SERVER_STARTED + command registration
  config/GlowConfigManager.java     Singleton holding all runtime state + JSON persistence
  command/GlowCommand.java          /teamglow tree
  mixin/
    ServerEntityMixin.java          Core glow engine (3 injection points, see §4.1)
    ScoreboardMixin.java            Team-membership change detection → syncEpoch
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

### 3.2 The three hooks of `ServerEntityMixin`

| Hook | Fires when | Job |
|---|---|---|
| `smartForcePacket` (`@ModifyVariable` on the `packDirty()` result) | `packDirty()` returned `null` | Force a packet **only** if `cachedTeamName`/`cachedConfigVersion`/`cachedSyncEpoch` are stale; otherwise return `null` so vanilla skips the packet entirely |
| `onAddPairing` (`@Inject` TAIL on `addPairing`) | A new viewer starts tracking the entity | Send the correct glow state immediately, and seed the caches so `smartForcePacket` won't redundantly force later |
| `redirectSendData` (`@Redirect` on `sendToTrackingPlayersAndSelf`) | Every dirty-data broadcast | Build no-glow + glow copies, broadcast then overlay (see §3.1) |

`smartForcePacket` has two optimizations worth not breaking:
- **Fast bail**: if `version` and `syncEpoch` are both unchanged, the entity's team cannot have changed — skip the scoreboard lookup, return `null`.
- **Never-glow bail**: if the entity is not in a glowing team now *and* wasn't at last sync, update the caches and return `null` — later epoch/config bumps won't re-run lookups for it forever.

`redirectSendData` fast paths (in order): not a `ClientboundSetEntityDataPacket` → forward; non-player **and** `non_player_glow` off → forward; mod disabled → forward; `hasEnabledTeams()` false → forward; entity currently glowing (`isCurrentlyGlowing()` covers the GLOWING effect *and* `setGlowingTag`) → forward. The entity-team lookup is hoisted out of the per-viewer predicate — computed once.

The old explicit self-send was deleted: `ChunkMap.TrackedEntity.updatePlayer` excludes the entity from its own tracking set, so `sendToTrackingPlayersAndSelf` already covers self.

**Deliberate behavior**: a glowing player does NOT see their own glow in third-person view — self always receives the no-glow variant and the tracking set excludes self, so only teammates see the glow. Kept this way on purpose.

### 3.3 Viewer-side team changes (`ScoreboardMixin`)

Three `@Inject`s funnel into one `onTeamChange(PlayerTeam)` which bumps `syncEpoch` **only for glow-enabled teams**:
- `Scoreboard.addPlayerToTeam(String, PlayerTeam)` (RETURN, checks return value)
- `Scoreboard.removePlayerFromTeam(String, PlayerTeam)` (the single-arg overload is deliberately NOT hooked — on success it calls the two-arg version, hooking both would double-bump)
- `Scoreboard.removePlayerTeam(PlayerTeam)` — required: `/team remove` clears `teamsByPlayer` directly, bypassing both hooks above. Without it, glow would linger forever on viewers.

All three carry explicit method descriptors so future overload additions can't silently break them.

## 4. Config & invalidation model (`GlowConfigManager`)

### 4.1 Three counters, three jobs

| Counter | Type | Job | Bumped by |
|---|---|---|---|
| `version` | runtime `long` | Entity-side cache invalidation (`cachedConfigVersion`) | Every state change via idempotent setters |
| `syncEpoch` | runtime `long` | Viewer-side cache invalidation (`cachedSyncEpoch`) | `bumpSyncEpoch()` from team changes in glow teams |
| `config_version` | disk `int[]` `[major, minor]` | Disk schema version, *not* a cache counter | Migration only |

`version` and `syncEpoch` live entirely in RAM and reset every server start — that is fine, caches are per-`ServerEntity` instance and are re-seeded by `onAddPairing`.

### 4.2 Idempotency rule

Every setter (`setEnabled`, `addTeam`, `removeTeam`, `setLocatorBarTeammatesOnly`, `setNonPlayerGlow`) must be a no-op when the value is unchanged, otherwise it bumps `version` and forces a pointless full-server resync. This is a standing requirement — preserve it in new setters.

### 4.3 Load & migration order (in `loadFromWorld`)

1. Parse JSON into `ConfigData` (fields `configVersion`/`config` default to `null` = legacy marker).
2. Read the `config` sub-object into the singleton fields.
3. If `configVersion` is missing (legacy), call `save()` to rewrite the file with `[1, 0]`.
4. Only then `version++`.

Step 3 must run **before** step 4: the migration write is itself a config change, and the cache-invalidation semantics depend on the counter reflecting it. Adding a new switch later = minor bump only, no migration code, Gson fills the default.

## 5. Locator-bar filter (`LivingEntityMixin`)

MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)` (returns `Optional<WaypointTransmitter.Connection>`). Returning `Optional.empty()` makes `ServerWaypointManager.createConnection`'s `ifPresentOrElse` tear the connection down — no extra cleanup needed.

Semantics are **asymmetric and receiver-driven**:
1. Switch off → return `original` (vanilla).
2. Receiver not in a glow-enabled team (teamless or non-glow team) → return `original` (sees everyone).
3. Receiver in a glow-enabled team → hide members of *other* glow-enabled teams (`myTeam` glow-enabled and `!myTeam.equals(receiverTeam)` → `Optional.empty()`); same-team, non-glow and teamless stay visible.

Toggling the switch rebuilds connections immediately from `GlowCommand`: `ServerLevel.getWaypointManager().remakeConnections(player)` for every player in every dimension — the same call vanilla `ServerScoreboard.updateTeamWaypoints` makes on team changes.

No Stonecutter version gates needed: the interface signature is identical in 26.1/26.2 (the `LocatorBarRenderer` vs `LocatorBar` client-class difference only matters for the client-side filtering route, which was intentionally not taken). MixinExtras is compile-time only (`compileOnly io.github.llamalad7:mixinextras-fabric:0.5.4`); the runtime is bundled with fabric-loader (0.18.4 ships 0.5.0, 0.19.3 ships 0.5.4).

## 6. Commands & permissions (`GlowCommand`)

```
/teamglow
├── on | off                    command/on | command/off          (fallback OP 2)
├── status                      command/status                   (fallback: all)
├── team
│   ├── add <team>              command/team/add                 (fallback OP 2)
│   ├── remove <team>           command/team/remove              (fallback OP 2)
│   └── list                    command/team/list                (fallback: all)
└── config                       command/config                   (fallback OP 2)
    ├── (no argument → list)     same node
    ├── locator_bar_hide_other_glowing_teams <bool>   same node
    └── non_player_glow <bool>              same node
```

- Permission nodes are plain `Identifier` strings via `PermissionPredicates.require(node, fallbackLevel)` (Fabric `permission.v1`). `CommandSourceStack` gets the `PermissionContextOwner` interface through a **ClassTweaker** `transitive-inject-interface` in the fabric-permission-api module — this is why `.requires(...)` type-checks at compile time.
- `PermissionLevel` is the **Mojang** enum `net.minecraft.server.permissions.PermissionLevel` (ALL..OWNERS), *not* a Fabric class.
- LuckPerms works with no adapter: unset nodes return DEFAULT and fall back to the OP check; explicit `true`/`false` overrides it.
- Config-switch messages use one generic key `glow.teammates.config.set` (`%1$s set to %2$s.`) so new switches never need a lang-file entry.

## 7. Multi-version build (Stonecutter)

- **VCS version is 26.2** — the canonical source in `src/`. Always commit from it.
- `versions/<mc>/gradle.properties` hold per-version dependency versions; `build.gradle` reads them via `${property(...)}`. The `server-translations-api` version differs per MC (3.0.3+26.1 / 3.1.0+26.2) and is bundled with `implementation include(...)`.
- Version-gated code uses `//? if 26.2 { ... } //?} else { ... }`. Currently **no** source file needs gates — the only cross-version API differences documented (locator-bar client classes) were avoided by the server-side design.
- Commands: `./gradlew build` (all versions); `./gradlew setActiveVersion -Pversion=26.1` (IDE); `./gradlew "Reset active project"` (restore VCS source — **run before every commit**).
- **Mixin anchors to re-verify on every MC upgrade** (`defaultRequire: 1` fails loudly if any breaks):
  - `ServerEntityMixin#smartForcePacket` — `@ModifyVariable` on `sendDirtyEntityData`'s `SynchedEntityData.packDirty()` result (`INVOKE_ASSIGN`, `ordinal = 0`)
  - `ServerEntityMixin#redirectSendData` — `@Redirect` on `ServerEntity$Synchronizer.sendToTrackingPlayersAndSelf(Packet)`
  - `ServerEntityMixin#onAddPairing` — `@Inject` TAIL on `ServerEntity.addPairing(ServerPlayer)`
  - `ScoreboardMixin` — three `@Inject`s with explicit descriptors on `addPlayerToTeam(String, PlayerTeam)`, `removePlayerFromTeam(String, PlayerTeam)`, `removePlayerTeam(PlayerTeam)`
  - `LivingEntityMixin#filterWaypointByTeam` — MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)`
  - Dependency assumption: `Scoreboard.removePlayerFromTeam(String)` (single-arg) calls the two-arg overload internally — if vanilla stops doing that, hook the single-arg method too.

## 8. Rules that will bite you

1. **Never use `Entity.getServer()`** — removed in 26.1+. Use `entity.level().getScoreboard()` etc.
2. **Never toggle entity data directly** (`entity.getEntityData().set(...)`) — it corrupts server-side state and desyncs vanilla. Always inject missing packets (`@ModifyVariable`/`@Redirect`/`@Inject`) instead.
3. **Mojang mappings only.** All class/method names in code and mixin descriptors are official. `Identifier` is `net.minecraft.resources.Identifier`, not `ResourceLocation`; `net.minecraft.server.permissions.PermissionLevel`, not a Fabric enum.
4. **Non-glow team changes must not bump `syncEpoch`** — auto-team plugins cause constant membership churn; bumping for non-glow teams would resync the whole server for nothing.
5. **Idempotent setters or pay the resync cost** (§4.2).
6. **`config_version` migration must precede `version++`** (§4.3).
7. **Mixin target classes load on the client too** (`environment: "*"`). Keep this safe: client-side *application* is harmless because the hooked server methods never run there — but never put client-only code in a shared mixin.
8. **Server-Translations keys live in `data/<modid>/lang/`, not `assets/`** — the server reads the former.

## 9. Workflow

- Feature work happens on `future-plan`; commit per feature, merge to `main` when complete.
- Commit style: conventional commits (`feat:`/`fix:`/`refactor:`/`docs:`/`chore:`), single concern per commit.
- Before committing: `./gradlew "Reset active project"`, then verify `./gradlew build` (both versions) passes.
- Future version bumps live in root `gradle.properties` (`mod_version`) — jar names and `fabric.mod.json` follow automatically.
