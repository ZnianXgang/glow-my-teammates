# Glow My Teammates — Development Guide

Server-side Fabric mod for Minecraft 26.1.x/26.2/26.3/26.4. It never creates or manages teams — it watches the vanilla `/team` system and customizes the glow bit (`Entity.DATA_SHARED_FLAGS_ID`, bit `0x40`) that the server sends to each client.

Two statements define the whole design:

1. **Glow is per viewer, not per entity.** The server sends a no-glow variant of every entity-data packet, then overlays a glow variant to teammates only. Netty's per-connection FIFO makes the overlay arrive last.
2. **Everything is event-driven.** The mod reacts to exactly three things: entity data going dirty, a new viewer entering tracking range, and team/config changes. In steady state it does nothing.

Everything below is the reasoning behind the code. Where the two disagree, the code is right and this file is stale.

## Contents

- [Where things live](#where-things-live)
- [The glow pipeline](#the-glow-pipeline)
- [Caches and invalidation](#caches-and-invalidation)
- [Locator bar](#locator-bar)
- [Commands, permissions, suggestions](#commands-permissions-suggestions)
- [Building several Minecraft versions](#building-several-minecraft-versions)
- [Upgrading to a new Minecraft version](#upgrading-to-a-new-minecraft-version)
- [Rules that will bite you](#rules-that-will-bite-you)

## Where things live

| File | Job |
|---|---|
| `GlowMyTeammates.java` | `ModInitializer`: lifecycle hooks, command registration |
| `GlowConstants.java` | Glow-flag bits. A plain class on purpose: constants in a mixin interface fail validation. |
| `WaypointSync.java` | Locator-bar connection rebuilds |
| `config/GlowConfigManager.java` | Singleton: runtime state + per-world JSON persistence |
| `command/GlowCommand.java` | The `/teamglow` tree, its suggestions, and its side effects |
| `mixin/ServerEntityMixin.java` | The glow engine: three injection points |
| `mixin/ScoreboardMixin.java` | Team-membership changes → resync counter + waypoint rebuild |
| `mixin/LivingEntityMixin.java` | The locator-bar filter |
| `mixin/EntityAccessor.java` | `@Accessor` for `DATA_SHARED_FLAGS_ID` |
| `src/main/resources/` | `fabric.mod.json` (`environment: "*"`), mixins config, server-side lang |

## The glow pipeline

### Packet flow, per entity per tick

```
ServerEntity.sendDirtyEntityData()
  ├─ SynchedEntityData.packDirty() ──── null? → smartForcePacket (forces only on a state change)
  │       └─ returns List<DataValue> → vanilla continues to sendToTrackingPlayersAndSelf
  │               └─ @Redirect redirectSendData intercepts, per-viewer customization:
  │                    1. no-glow copy → tracking set + self
  │                    2. glow copy     → teammates only
  └─ addPairing(player) (first sighting) → onAddPairing sends the correct state once
```

Two `sendToTrackingPlayersAndSelf` calls exist in `sendDirtyEntityData`: the entity-data packet (`ordinal = 0`, redirected) and a `ClientboundUpdateAttributesPacket` (`ordinal = 1`, untouched).

### The three hooks

| Hook | Fires when | Job |
|---|---|---|
| `smartForcePacket` (`@ModifyVariable` on the `packDirty()` result) | `packDirty()` returned `null` | Force a packet only if a cached counter is stale; otherwise let vanilla skip it |
| `onAddPairing` (`@Inject` TAIL on `addPairing`) | A viewer starts tracking the entity | Send the correct state once; seed only `cachedTeamName` |
| `redirectSendData` (`@Redirect` on `sendToTrackingPlayersAndSelf`, ordinal 0) | Every dirty broadcast | Build no-glow + glow copies, broadcast then overlay |

Three behaviors are deliberate:

- **No self-glow in third person.** An entity never tracks itself, so `sendToTrackingPlayersAndSelf` (the no-glow variant) is all it receives — the glow overlay goes to tracking players only.
- **Vanilla glow is never touched.** The GLOWING effect and an entity's `Glowing` tag flow through unchanged; the mod only adds or clears its own bit on top of the server's current flags. `modifyGlowFlag` always rebuilds the byte from those flags, never from a bare `0x40`, so the other flag bits survive.
- **`onAddPairing` settles only `cachedTeamName`, never the counters.** The counters mean "every viewer is synced"; a fresh pairing only customized the new viewer, so settling them would suppress a cleanup broadcast that other viewers still need.

### Viewer-side team changes

`ScoreboardMixin` funnels three injections into `onTeamChange`:

| Hook | Note |
|---|---|
| `addPlayerToTeam(String, PlayerTeam)` (RETURN, checks the return value) | |
| `removePlayerFromTeam(String, PlayerTeam)` (RETURN) | The single-arg overload is **not** hooked: it calls this one, so hooking both would double-bump. This overload throws for non-members, so RETURN only fires for real removals. |
| `removePlayerTeam(PlayerTeam)` | Required: `/team remove` clears `teamsByPlayer` directly. Assumes `team.getPlayers()` is still intact — re-check on upgrade. |

`onTeamChange` ignores anything that is not the running server's own scoreboard on the server thread. That is not paranoia: in singleplayer/LAN the client thread mutates the *client* scoreboard while handling team packets, and touching the shared counters or `WaypointSync`'s pending set off-thread is a data race. The server mutates its own scoreboard (firing the hook) before broadcasting, so nothing is lost.

## Caches and invalidation

Three counters, three jobs:

| Counter | Type | Job | Bumped by |
|---|---|---|---|
| `version` | runtime `long` | Entity-side invalidation (`cachedConfigVersion`) | Every state change through the idempotent setters |
| `syncEpoch` | runtime `long` | Viewer-side invalidation (`cachedSyncEpoch`) | `bumpSyncEpoch()` from membership changes in glow teams |
| `configVersion` | disk `int[]` | Disk schema version, *not* a cache counter | Migration only |

They never reset and only grow across server starts. That is fine: the caches are per-`ServerEntity` (created as `0`) and seeded by `onAddPairing`, so the first comparison always mismatches and forces a full lookup.

**Idempotency is load-bearing.** `setEnabled`, `setLocatorBarTeammatesOnly`, `setNonPlayerGlow` and `removeTeam` are no-ops when the value is unchanged, checked *before* `version++`. Skipping that check turns a redundant command into a full-server resync. `addTeam` is the one exception — the command layer rejects an already-enabled team, which is the only thing keeping it idempotent in practice.

**What may be cached, and what may not.** The *viewer's* team can be cached: it only matters when glow-enabled, and entering or leaving a glow team is exactly what bumps `syncEpoch`. The *entity's* team cannot: a move between two non-glow teams moves no counter at all, so nothing could invalidate it.

### Loading, repair and saving

Order inside `loadFromWorld`:

1. `resetToDefaults()` — the single `version++` for the whole load. Every later step, including migration writes, is already covered by it.
2. Remove an orphaned `.tmp` file (only a previous run that died mid-write can have left one).
3. Read → parse → migrate. **Read and parse are separate `try` blocks on purpose:** a file that could not be *read* has contents worth protecting, and is left alone; a file that could not be *parsed* is genuinely corrupt and is repaired in place.

A read failure sets `configReadFailed`, which makes every later `save()` refuse. The session runs on defaults while a valid file may still be on disk, and the alternative — the first admin command overwriting it — is data loss. Commands surface a refused save as a failure and roll their in-memory change back.

Legacy schemas are migrated on load: a missing `configVersion`, the pre-1.1.1 key `locatorBarHideOtherGlowingTeams`, or a literal-`null` `config`. A file declaring a *newer* major is not migration material — it is left alone with a warning.

Saving is atomic (temp file + `ATOMIC_MOVE`, falling back to a plain replace with a short retry for Windows file locks). `save()` returns `false` for both a failed write and a blocked session, because callers only need "not persisted".

## Locator bar

`LivingEntityMixin` is a MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)`. Returning `Optional.empty()` makes `ServerWaypointManager.createConnection` tear the connection down; no extra cleanup is needed.

The semantics are **asymmetric and receiver-driven**:

| Receiver | Sees |
|---|---|
| Switch off | vanilla |
| Not in a glow team (teamless or non-glow) | vanilla — everyone |
| In a glow team | only that team; every other entity is hidden |

Non-player entities take the same path. Any `LivingEntity` with a raised `waypoint_transmit_range` attribute is a transmitter (vanilla only raises it for players, so a command, datapack or modifier must have done it), and membership is keyed by the entity's scoreboard name, which *is* its UUID string. Note that `non_player_glow` is unrelated: it controls the glow outline, not the locator bar.

### Re-evaluation

Vanilla only rebuilds connections for the changed player as a *sender*. Because the filter is receiver-driven, the receiver side needs its own rebuild, and a connection that never breaks (AFK viewer, stationary mob) would otherwise keep the old rules forever.

| Trigger | Call |
|---|---|
| Filter rules changed (toggle, team add/remove, switch) | `WaypointSync.rebuildAll` — every connection, every dimension |
| Membership changed while the switch is on | `ScoreboardMixin` → `WaypointSync.rebuildForMember` per affected member |

`rebuildForMember` only *marks* the dimension; the rebuild runs once per tick at `END_SERVER_TICK` (`flushPendingRebuilds`). Deferring collapses a burst of membership changes and guarantees the pass sees the final team state — during `addPlayerToTeam` a team switcher is briefly teamless. The pending set is cleared on `SERVER_STOPPING`.

Both paths iterate the waypoint manager's transmitter set, not `level.players()`: a non-player transmitter's connections are never rebuilt by vanilla, because `updateTeamWaypoints` resolves members through `PlayerList.getPlayerByName`, which returns `null` for an entity UUID. The set is copied before iterating, since `remakeConnections` runs connection callbacks that may mutate it.

The drain is bounded at `MAX_FLUSH_ROUNDS` per tick and drains one dimension at a time, so a reentrant re-mark survives the drain and a rebuild that keeps re-marking cannot wedge the tick. Leftovers are deferred, never dropped — a rebuild is idempotent, so re-running one is wasted work but never wrong.

## Commands, permissions, suggestions

### The tree

| Command | Permission node under `glow-my-teammates.` (fallback) |
|---|---|
| `/teamglow`, `status` | `command.status` (all) |
| `toggle` | `command.toggle` (OP 2) |
| `team …` | `command.team` (OP 2) |
| `config …` | `command.config` (OP 2) |

- Nodes come from `PermissionPredicates.require(node, fallbackLevel)` (Fabric `permission.v1`); the level is the vanilla enum. LuckPerms needs no adapter.
- A gate sits on the `team` / `config` *literal*, not on each child: Brigadier ANDs a parent's `requires()` into every child, so per-subcommand gates would be redundant — and it means `team list` / `config list` / `config get` are OP 2 too.
- The bare `/teamglow` shortcut checks its own gate inside the executor, because a gate on the root node would be ANDed into every subcommand.
- `status` reports only the global on/off switch. Team and switch details stay behind the OP 2 commands, so the public node never exposes them.
- Feature switches live once in the `FeatureSwitch` enum (id, default, reader, writer, side effect); the tree, `config list` and `config reset` all derive from it. Adding a switch = one constant, no command or lang changes.
- Switch messages use three generic keys plus `.unknown`, so new switches never need a lang entry. Defaults live in `GlowConfigManager.DEFAULT_*`.
- Every mutating command checks `save()` and rolls the in-memory state back on failure.
- Team and switch suggestions narrow by the typed prefix, the way vanilla's own team argument does. Brigadier will not filter for you.

### 26.4 command serializability

26.4 replaced the command system with the "command box" family (`CommandBox`, `SuggestionBox`, `ParseBox`, `ParseContext`). `Commands`' constructor converts the **whole** dispatcher through `SuggestionBox.fromFullTree`, and that conversion only accepts serializable nodes:

- an argument type must be a `SerializableArgument.BrigadierWrapper`, and
- a suggestion provider must be a `CustomSuggestion.BrigadierWrapper`.

A raw Brigadier type therefore throws `UnsupportedOperationException: Invalid argument type: string()` — `string()` is `StringArgumentType.word().toString()` — from inside `Commands.createVanilla`, which kills `ReloadableServerResources`. The dedicated server dies on startup; singleplayer dies **while creating a world**, and the crash report is a *client* crash whose real stack is `Commands.<init>` → `SuggestionBox.fromFullTree`. When a 26.4 command tree stops the game, search the log for `Invalid argument type` first.

The migration, all of it in `GlowCommand`:

| 26.1–26.3 | 26.4 |
|---|---|
| `StringArgumentType.word()` | `StringArgument.word()` |
| `BoolArgumentType.bool()` | `BooleanArgument.bool()` |
| `StringArgumentType.getString` / `BoolArgumentType.getBool` | `ctx.getArgument(name, Type.class)` — works on every version, so it is not gated |
| `.suggests(...)` over runtime candidates | `ServerOnlyCustomSuggestion` lambda → `.toBrigadier()`; `serializable()` defaults to `AskServer.INSTANCE`, so the client asks the server instead of evaluating it locally |
| `.suggests(...)` over a static list | `SuggestLiteralString.suggestLiteral(...)` — serialized into the tree, narrowed by the client |

Five helpers hold the gates (`wordArgument`, `boolArgument`, `teamAddSuggestions`, `teamRemoveSuggestions`, `switchSuggestions`), with shared, ungated candidate helpers underneath so the two versions cannot drift.

Two things must **not** be "fixed" here:

- **Requirements predicates stay as they are.** `PermissionPredicates.require` returns a `FabricPermissionCommandCheck`, not a vanilla `PermissionProviderCheck`, but `fabric-permission-api`'s `CommandBoxMixin` converts it in `canNodeRun` and `convertNodePermissionCheck`. Do not swap in vanilla `Commands.hasPermission`, and do not hand-roll lambdas.
- **Do not drop `.suggests(...)` to make it boot.** The serializable shape is the API design; losing completions is a silent downgrade, not a fix.

## Building several Minecraft versions

**Source and versions.** Every target compiles for Java 25. `src/` is the canonical source, projected for the VCS version — currently **26.4**. Stonecutter generates one source set per version in `versions/<v>/build/generated/`, and every build compiles all of them.

**Per-version properties** live in `versions/<mc>/gradle.properties`: `minecraft_version`, `minecraft_version_depends`, `fabric_api_version`, `server_translations_version`. `loader_version` is global (root file). Inside `processResources` a bare `property(...)` resolves against the *task*, so per-version values must be read through `project.property(...)`. `server-translations-api` is MC-specific and bundled with `implementation include(...)`.

**Which version id goes where.** The `minecraft` dependency of `fabric.mod.json` is expanded from `minecraft_version_depends`, whose value is the complete predicate body *including* the trailing `-` (`26.4-` → `~26.4-`), so it covers every pre-release and the final release of that series. The loom coordinate and the `+<mc>` jar suffix use the exact `minecraft_version` / `sc.current.version` (`+26.1.2`). Keeping the two separate is what lets a version directory target a concrete hotfix or pre-release while declaring the whole series. Fabric Loader matches the *normalized* game version (`26.4-pre-1` normalizes to `26.4-pre.1`), so a concrete id would need that spelling. Do not swap the properties.

**Version gates.** Syntax is `//? if 26.4 { ... //?} else { ... //?}`. The branch that is not active in `src/` stays commented out (`/* ... *///?}`), and the markers survive into the generated sources as comments. `GlowCommand` is currently the only gated file; everything else is shared verbatim.

The loop is: `./gradlew setActiveVersion -Pversion=<v>` to change which projection `src/` holds → edit **both** branches → `./gradlew "Reset active project"` before committing. Never trust a single-version build: `./gradlew build` compiles all four generated projections, and that is the only proof both branches are valid.

**Dev vs release.** A bare `./gradlew build` is a dev build: the version gets a `-SNAPSHOT` suffix (`1.2.1-SNAPSHOT+26.4`), so neither the jar nor its `fabric.mod.json` can be mistaken for a release. Pass `-Prelease` for `1.2.1+26.4`. Only distribute `-Prelease` jars.

**Workflow.** Work directly on `main`; conventional commits (`feat:`/`fix:`/`refactor:`/`docs:`/`chore:`), one concern each. Before committing: Reset active project, then confirm `./gradlew build` passes. `mod_version` lives in the root `gradle.properties`; jar names and `fabric.mod.json` follow it. Keep README's jar-name and config examples in sync.

**Pre-release targets** are normal: a version directory may point at `26.4-pre-1` while `minecraft_version_depends` declares the whole series. Repoint it at the final release with a `chore: <mc> release` commit touching only `versions/<mc>/gradle.properties`, the way `26.3-rc-2` became `26.3`.

## Upgrading to a new Minecraft version

Compiling is the *start* of an upgrade, not the end. In order:

1. Add `versions/<new>/gradle.properties`, register the version in `settings.gradle`, and build all versions.
2. **Boot a world**, dedicated or in singleplayer. Several regressions only appear once `Commands` is constructed or an entity is tracked, never at compile time.
3. Re-verify every injection anchor (`defaultRequire: 1` fails loudly):
   - `ServerEntityMixin#smartForcePacket` — `INVOKE_ASSIGN` on `packDirty()`, `ordinal = 0`
   - `ServerEntityMixin#redirectSendData` — `@Redirect` on `ServerEntity$Synchronizer.sendToTrackingPlayersAndSelf(Packet)`, `ordinal = 0`
   - `ServerEntityMixin#onAddPairing` — `@Inject` TAIL on `addPairing(ServerPlayer)`
   - `ScoreboardMixin` — three `@Inject`s with explicit descriptors
   - `LivingEntityMixin#filterWaypointByTeam` — `@ModifyReturnValue` on `makeWaypointConnectionWith(ServerPlayer)`
4. Re-check the command layer for serializability (26.4+): every argument type and suggestion provider must stay serializable, or the whole server dies.
5. Re-check the dependency assumptions: single-arg `removePlayerFromTeam` calls the two-arg overload; the two-arg overload throws on non-members; `removePlayerTeam` leaves the member list intact; `TrackedEntity.updatePlayer` excludes self from `seenBy`; server-side `Entity.isCurrentlyGlowing()` reflects `hasGlowingTag` only, with `LivingEntity` overriding it to also cover the GLOWING effect.
6. Check the bundled `server-translations-api` build against the new version — it is the only third-party code inside the jar — and bump it when Nucleoid publishes a build for the new series.
7. Refresh README's requirement table, CHANGELOG and this file.

## Rules that will bite you

1. **Never toggle entity data directly** (`entity.getEntityData().set(...)`) — it corrupts server-side state and desyncs vanilla. Inject missing packets instead.
2. **Non-glow team changes must not bump `syncEpoch`.** Auto-team plugins churn constantly; bumping for them would resync the whole server for nothing.
3. **Keep the setters idempotent**, and keep the command-side guard on `addTeam`.
4. **Keep the load sequence's `version++` in exactly one place** — `resetToDefaults()`. Migration and repair paths must not add their own. Reading and parsing must stay in separate `try` blocks, or an unreadable file gets treated as corrupt and overwritten.
5. **Mixin targets also load on the client** (`environment: "*"`). The `ServerEntity` and `LivingEntity` hooks are harmless there, but `ScoreboardMixin` is the exception (see Viewer-side team changes). Never put client-only code in a shared mixin.
6. **Server-Translations keys live in `data/<modid>/lang/`**, not `assets/` — the server reads the former.
7. **Never define fields in `@Mixin` interfaces.** Even `static final` constants are injected into the target class and fail validation unless `@Shadow`. Shared constants live in `GlowConstants`.
8. **Never register a raw Brigadier argument type or a bare `SuggestionProvider` lambda on a 26.4+ target.** Use `StringArgument` / `BooleanArgument` and a `CustomSuggestion`, behind a gate.
