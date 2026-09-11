# Glow My Teammates — Development Guide

Server-side Fabric mod for Minecraft 26.1/26.2/26.3 (Mojang mappings). It never creates or manages teams — it watches the vanilla `/team` system and customizes the glow bit (`Entity.DATA_SHARED_FLAGS_ID`, bit `0x40`) that the server sends to each client.

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
- **Vanilla glow is never touched**: the glowing effect and an entity's `Glowing` tag flow through unchanged. The mod only adds/clears its own bit on top of the server's current flags — `modifyGlowFlag` always rebuilds the byte from server flags, never from a bare 0x40, so the other shared-flag bits are never wiped.

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

`version++` happens **once, up front**: the opening `resetToDefaults()` in `loadFromWorld` bumps it for the whole load, before anything is parsed. So there is no ordering constraint left between migration and the counter — the migration write is a config change, but the bump that covers it already happened, and adding a new switch later is still a minor schema bump only, no migration code.

The rest of the sequence is: parse → read the `config` sub-object → migrate legacy schemas (missing `configVersion`, the pre-1.1.1 key `locatorBarHideOtherGlowingTeams`, or a literal-null `config`) → persist the repair when any of those applied.

Broken or literal-null config → `resetToDefaultsAndPersist()` — defaults, repair-save (the `version` bump already happened in `resetToDefaults()`, so the repair deliberately does not add its own). The read step is deliberately split from the parse step: an `IOException` means the file could not be *read* (lock, permissions, failing disk), which is indistinguishable from corruption, so the file is left alone, defaults are used in memory, and `configReadFailed` blocks every later `save()` for the session so the first admin command cannot overwrite it with defaults; a `RuntimeException` from parsing is genuine corruption and *is* repaired on the spot. Persistence is atomic (tmp file + `ATOMIC_MOVE`, retry fallback for Windows locks); a failed `save()` is reported back so commands roll back their in-memory state.

`save()` returns `false` both when the write fails and when `configReadFailed` is set — commands only need to know "not persisted" to roll back and report, so no extra branch is required.

## 5. Locator-bar filter (`LivingEntityMixin`)

MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)` (returns `Optional<WaypointTransmitter.Connection>`). Returning `Optional.empty()` makes `ServerWaypointManager.createConnection` tear the connection down — no extra cleanup needed.

Semantics are **asymmetric and receiver-driven**:
1. Switch off → `original` (vanilla).
2. Receiver not in a glow-enabled team (teamless or non-glow) → `original` (sees everyone).
3. Receiver in a glow-enabled team → only same-team members stay visible; every other entity is hidden (`!receiverTeam.equals(myTeam)` → `Optional.empty()`). No separate glow check on `myTeam` needed: equals already implies the same glow-enabled team.

Connection re-evaluation (`WaypointSync`, all methods server-thread only):
- **Filter rules changed** (switch toggles, `team add/remove`, `toggle`): `WaypointSync.rebuildAll` — every player-transmitted connection in every dimension.
- **Team membership changed** while the switch is on: `ScoreboardMixin` → `WaypointSync.rebuildForPlayer` per affected player. Required because the filter is **receiver-driven** — vanilla's own rebuilds only cover the changed player as a *sender*. `rebuildForPlayer` only marks the dimension; the rebuild runs once per tick at the boundary (`END_SERVER_TICK` → `flushPendingRebuilds`), which collapses bursts and guarantees the pass sees the *final* team state. Pending set cleared on `SERVER_STOPPING`.

The rebuild call is `ServerLevel.getWaypointManager().remakeConnections(player)` — the same call vanilla's `updateTeamWaypoints` makes. No Stonecutter gates: the interface is identical in 26.1/26.2/26.3.

## 6. Commands & permissions (`GlowCommand`)

The command tree and its permission nodes (with fallbacks) are listed in README's command table. Four nodes, no deeper:

- `command.status` (all) — `/teamglow`, `status`
- `command.toggle` (OP 2) — `toggle`
- `command.team` (OP 2) — the whole `team` group
- `command.config` (OP 2) — the whole `config` group

- Permission nodes via `PermissionPredicates.require(node, fallbackLevel)` (Fabric `permission.v1`); `PermissionLevel` is the **Mojang** enum `net.minecraft.server.permissions.PermissionLevel`. LuckPerms needs no adapter.
- A group gate sits on the group literal (`team` / `config`) only — Brigadier ANDs a parent's `requires()` into every child, so per-subcommand gates would be redundant. Consequence: a group's read commands inherit its write permission (`team list` / `config list` / `config get` are OP 2).
- `status` reports **only** the global on/off state. Team and switch details stay behind the OP 2 group commands, so the public node never exposes them.
- The bare `/teamglow` shortcut checks its status permission **inside the executor** — a root `requires()` would AND it into every subcommand (Brigadier semantics).
- Feature switches are defined once in the `FeatureSwitch` enum (id, default, reader, writer, `SwitchEffect`); the command tree, `config list` and `config reset` all derive from it. Adding a switch = one enum constant, no command-tree or lang changes.
- Feature-switch messages use three generic keys (`glow.teammates.config.entry` / `.set` / `.reset`) plus `.unknown` for a bad name — new switches never need a lang-file entry.
- Switch defaults live in `GlowConfigManager.DEFAULT_*`, the single source of truth for the field initializers, the legacy/no-file load paths and `resetToDefaultsAndPersist`.
- Every mutating command validates the `save()` result and rolls the in-memory state back on failure.
- Team suggestions **must** narrow by the typed prefix (`suggestMatchingTypedPrefix`, case-insensitive `startsWith` on `builder.getRemainingLowerCase()`) — vanilla's own team argument filters as you type, and Brigadier does not filter for you.

## 7. Multi-version build & workflow (Stonecutter)

**26.1+ background**: Minecraft 26.1 (the first 2026-renamed release) removed obfuscation and raised the minimum Java to 25. With obfuscation gone, **Yarn is unavailable from 26.1 on** — 26.1+ mods use **Mojmap** (official names ship in the jar, so `build.gradle` has no `mappings` line).

- **VCS version is 26.3** — the canonical source in `src/`. Always commit from it.
- Per-version deps live in `versions/<mc>/gradle.properties`, except `loader_version` which is global (root file). Inside `processResources`, a bare `property(...)` resolves against the **task**, so per-version values must be read via `project.property(...)`. Server-translations-api differs per MC and is bundled with `implementation include(...)`.
- The `minecraft` entry of `fabric.mod.json` is the one exception: it is expanded from **`sc.current.version`** (the version directory name, e.g. `26.3`), not from `minecraft_version`. The template appends a trailing `-`, producing a series-wide predicate (`~26.3-`) that matches every pre-release and the final release of that series. Fabric Loader matches dependencies against its *normalized* game version, which differs from the raw version id, so pinning a concrete pre-release id would need its normalized spelling. The loom coordinate (`minecraft "com.mojang:minecraft:..."`) still uses the exact `minecraft_version`. **Do not swap the two.**
- Version-gated code uses `//? if 26.3 { ... } //?} else { ... }`. Currently **no** source file needs gates.
- `./gradlew build` (all versions); `./gradlew setActiveVersion -Pversion=26.1` (IDE); `./gradlew "Reset active project"` (restore VCS source — **run before every commit**).
- A bare `./gradlew build` is a **dev build** — the version gets a `-dev` suffix (`1.2.0-dev+26.3`), so the jar and its `fabric.mod.json` version can never be mistaken for a release. Pass `-Prelease` for the clean release version (`1.2.0+26.3`). Only distribute `-Prelease` jars.
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
5. **Keep the load sequence's `version++` in exactly one place** — the opening `resetToDefaults()`; repair/migration paths must not add their own bump (§4.3). Reading and parsing must also stay in separate `try` blocks, or a file that merely could not be *read* gets treated as corrupt and overwritten.
6. **Mixin target classes load on the client too** (`environment: "*"`). The `ServerEntity`/`LivingEntity` hooks are harmless there, but `ScoreboardMixin` is the exception — `ClientPacketListener` mutates the *client* scoreboard from the client thread in singleplayer/LAN, so its hooks **do** fire off the server thread. `onTeamChange` must ignore anything that is not the server's own scoreboard on the server thread (`server.getScoreboard() == this && server.isSameThread()`, `server` null before `SERVER_STARTED`) before touching the shared counters or `WaypointSync`; the server fires the same hook on its own thread before broadcasting the team packet, so nothing is lost. Never put client-only code in a shared mixin.
7. **Server-Translations keys live in `data/<modid>/lang/`, not `assets/`** — the server reads the former.
8. **Never define fields in `@Mixin` interfaces** — even `static final` constants are injected into the target class and fail validation unless `@Shadow` (`InvalidInterfaceMixinException`). Shared constants live in `GlowConstants`.

## 9. Deliberate non-optimizations

### 9.1 Global `syncEpoch` resync stays global

Any membership change in a glow-enabled team bumps the shared `syncEpoch`; every glowing entity then re-syncs against its tracking viewers. Kept global on purpose: per-team granularity is a correctness liability, because the `isTeammate` predicate is evaluated against the *viewer's* team (`ServerEntityMixin:363-364`), so one viewer switching teams changes which entities have to be re-sent to that viewer.

The cost has two components, and only the first is a one-off.

1. **Settled entities** pay one forced broadcast per bump (`smartForcePacket`, `ServerEntityMixin:104-142`).
2. **Continuously-dirty entities** ride the per-packet path instead, and pay something on *every* dirty send, not just on a bump: the round's no-glow broadcast plus a flags-only glow packet per teammate (`ServerEntityMixin:393-394`). That second packet is the mod's steady-state network cost and what README's "Network footprint" measures — §9.1 is not a claim that glowing entities cost vanilla-level bandwidth, only that *membership changes* add just one round to settled entities.

Inside the dirty path, the stale-counter repair is narrower than it looks: `modifyGlowFlag(dataPacket, false, viewerSideChanged)` appends a shared-flags item **only when the round's packet does not already carry one** (`ServerEntityMixin:456-483`); when it does, the byte is rewritten in place (`:441-455`), and all three caches settle **only when `viewerSideChanged`** (`:382-386`).

### 9.2 Per-packet scoreboard lookups are accepted

Two lookups: one `getPlayersTeam` per entity per dirty send (`getGlowingTeam`, `ServerEntityMixin:229-240`), plus one `viewer.getTeam()` **per viewer** inside the `isTeammate` predicate (`:363-364`, run by `sendToTrackingPlayersFiltered` once per entry in the tracking set).

The second one scales with the tracking set, so it is not "dwarfed by the two packet allocations": for a player in a crowd the predicate is dozens of scoreboard probes per dirty send, the same order as the per-viewer packet work it rides along with. It stays acceptable because it is O(1) per viewer and is not the dominant term, not because it is a negligible constant.

Do **not** use the caching argument for both teams — it only holds for one of them:

- **The entity's team cannot be cached.** Its team can change without any counter moving (a change in a *non*-glow team never bumps `syncEpoch`, by design — §8.3), so there is no counter that would invalidate a cached entity team. `syncEpoch` genuinely cannot distinguish this case.
- **A viewer's team can be cached.** That team only affects the outcome if it is glow-enabled, and entering or leaving a glow-enabled team is exactly what `ScoreboardMixin` bumps `syncEpoch` for (`ScoreboardMixin:57-60`), so `syncEpoch` *is* a sound invalidation key for a `viewer → team` lookup. The one case that moves no counter — a viewer joining a **non**-glow team — is harmless: the entity-side check has already pinned the comparison to a glow-enabled team, so both the stale cached value and a freshly read one yield the same `false`.

The lookups are accepted because they are unmeasured, not because they are proven cheap: keep them until a profile shows per-viewer work dominating a dirty send.

### 9.3 `clearNonPlayerGlow` is a one-shot command

Iterates every non-player entity once when `non_player_glow` is switched off — the switch only fires this on the true→false edge (`GlowCommand.applySwitch`), so it is one command-triggered pass, not a recurring one.

The dominant term is **building the chunk → tracking-players map**, not the per-entity loop. That map is `players × |ChunkTrackingView|` `computeIfAbsent` calls (`GlowCommand:543-550`). The count is exact, not approximate: `ChunkTrackingView.Positioned.forEach` scans the box `minX..maxX` = `center ± (viewDistance + 1)` and keeps what `isWithinDistance(..., includeNeighbors = true)` accepts, so a view distance of 10 yields **473** chunks per player (from 23 × 23 = 529 candidates; the buffer radius of 2 clips the corners). At 20 players online that is ~9,500 insertions plus 20 `forEach` lambda passes, doubling at 40. Only after that is each entity's viewer lookup a hash `get` (`:570`) — cheap, but it is the smaller half.

That pass grows with online player count, which is the number to check before judging it: negligible on a small server, a visible main-thread hitch on a busy one. Accepted because it needs an admin to toggle the switch, not because the constant is small.

The cost of the switch being **on** is the other side of this and is not covered here: every glowing non-player entity then sends a glow packet to each of its tracking viewers on every dirty send (README's `non_player_glow` row). That, not this cleanup, is what mob-dense farms pay.

## 10. Settled questions from the code review

Everything below was verified against the 26.2 sources and deliberately left alone. It exists so the next review does not re-derive it — and so the withdrawn claims are not re-reported as defects and "fixed" into a regression.

### 10.1 Decided: not changing

- **A stale glow bit can outlive a non-command switch change.** Clear paths depend on a counter moving. Disabling `non_player_glow` through *another* mod's `setNonPlayerGlow(false)` therefore clears on the next dirty send or quiet tick rather than instantly. That is intended, not unsolved: the quiet path is documented at `ServerEntityMixin:91-101` ("even when `clearNonPlayerGlow` wasn't run (e.g. another mod disabled the switch directly)"), and the dirty path is `clearStaleGlow` (`:251-272`). The command paths (`GlowCommand.clearNonPlayerGlow`) broadcast a clear synchronously and have no such window.
  - **Two clear paths are complementary, never redundant.** `clearStaleGlow` covers an entity that *stopped* being customized; `forceIncludeFlags` covers a viewer-side change while the entity *still is*. Removing either silently loses the stale-bit clear. Do not "simplify" one away.
- **The `non_player_glow` toggle pass scales with online players.** Accepted; see §9.3.
- **The locator-bar drain budget warning is not rate-limited.** A stuck queue re-fills every tick, so one warning per tick is precisely what "this is still happening" looks like; a first-warn-only flag would suppress that, and its reset condition would never be reached in the pathological case anyway. See the comment above the warning in `WaypointSync.flushPendingRebuilds`.

### 10.2 Verified as *not* problems

Reported during review and disproved; listed so they are not re-opened. (Fixes that came out of the same review are release notes, not architecture — see `CHANGELOG.md`.)

- **"The mod sends a redundant no-glow packet to every tracker."** No. The `sendToTrackingPlayersAndSelf(noGlowPacket)` at `ServerEntityMixin:393` *is* the vanilla call the `@Redirect` replaced, not an addition, and non-teammates receive the original packet object unchanged. Extra traffic is one small packet **per teammate**.
- **"The `vanillaGlow` branch is dead code."** No. `packDirty()` returns only *changed* entries, so a round can legitimately carry no shared-flags entry — the branch settles the caches, which is what keeps a vanilla-glowing entity from forcing a redundant broadcast every tick.
- **"`clearStaleGlow`'s no-flags-entry path is unreachable."** Unproven, and the reasoning was wrong: the client's `0x40` is a per-client overlay absent from the server's `entityData`, so "the server byte never changed" does not imply "no client carries a stale bit". It is a correct fallback; keep it.
- **"A new tracker can inherit a stale glow bit."** No. `onAddPairing` returns early while `non_player_glow` is off (`ServerEntityMixin:163-165`), and a fresh pairing only ever sees vanilla's `trackedDataValues`, which the mod never writes. There is nothing for a new client to inherit.
- **`cachedGlowFlags != current` is not a reference-comparison bug.** The `Byte` operand unboxes to `byte`, so this is a numeric comparison. The cache stays coherent because the byte it was built from is only reused while the entity's server-side data is unchanged.
- **Language keys, NPE surface, and `moveIntoPlace` error handling** were checked point by point and found complete/bounded.

### 10.3 Platform facts worth not re-deriving

- Glow rendering is decided **client-side by the shared-flag bit** (`Minecraft.shouldEntityAppearGlowing` → `Entity.isCurrentlyGlowing()`); the mod never has to sync the GLOWING effect, and never writes server-side `entityData` (§8.1).
- The outline color comes from `Entity.getTeamColor()`, so it follows the vanilla team color.
- The bundled `server-translations-api` self-registers its `ModInitializer` and resolves `Component.translatable` per receiver at packet-encode time; the mod needs no translation code of its own. Keys belong in `data/<modid>/lang/` (§8.7).
- `addPairing`'s `@Inject TAIL` is safe because vanilla sends the pairing bundle before `startSeenByPlayer`, so the glow overlay always follows the spawn packet on the same connection.
