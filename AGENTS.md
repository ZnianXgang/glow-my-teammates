# AGENTS.md — Glow My Teammates

## Project Overview

Server-side Fabric mod that makes teammates glow for each other. Uses vanilla `/team` system — no custom team management. Supports **Minecraft 26.1 and 26.2** via Stonecutter multi-version build system.

## Tech Stack

- **Minecraft**: 26.1, 26.2 (Mojang mappings, no Yarn)
- **Fabric Loader**: 0.18.4+ (26.1) / 0.19.3+ (26.2)
- **Fabric API**: 0.155.2+
- **Java**: 25
- **Build**: Gradle 9.5.1 + Fabric Loom 1.17 + Stonecutter 0.9.7

## Project Structure

```
├── settings.gradle               Stonecutter plugin + version definitions
├── build.gradle                  Shared build script (version-aware via sc.current)
├── gradle.properties              Mod-level properties (mod_version, maven_group)
├── versions/
│   ├── 26.1/gradle.properties     MC 26.1 dependency versions
│   └── 26.2/gradle.properties     MC 26.2 dependency versions
└── src/main/java/com/glow/teammates/
    ├── GlowMyTeammates.java        Mod entry (ModInitializer)
    ├── config/GlowConfigManager.java  World-save JSON config
    ├── command/GlowCommand.java     /teamglow command
    └── mixin/
        ├── EntityAccessor.java        @Accessor for Entity.DATA_SHARED_FLAGS_ID
        ├── LivingEntityMixin.java     Locator bar filter (MixinExtras @ModifyReturnValue)
        ├── ScoreboardMixin.java       Detects team membership changes
        └── ServerEntityMixin.java     Core: event-driven per-client glow
```

## How It Works

Four Mixin classes working together:

### 1. `ServerEntityMixin` — Core glow logic

**`@ModifyVariable` on `packDirty()`** — event-driven instead of per-tick:
- `packDirty()` returns `null` when no data changed → vanilla skips the packet
- Only forces a packet when glow state actually changes:
  - Entity joins/leaves a glowing team (`cachedTeamName` mismatch)
  - Config changes (`cachedConfigVersion` mismatch, from `/teamglow` commands)
  - Viewer-side team changes (`cachedSyncEpoch` mismatch, from Scoreboard hooks)
- **Optimization 1**: When both `version` and `syncEpoch` are unchanged, skips the scoreboard lookup entirely — returns `null` immediately
- **Optimization 2**: Entity never in a glowing team (current and cached team both `null`) → updates the caches and returns `null` — no broadcast, and no repeated lookups on later epoch/config bumps
- Returns `null` otherwise → zero overhead in steady state

**`@Inject` on `addPairing(ServerPlayer)`** (TAIL) — initial viewer sync:
- Fires exactly once when a player enters tracking range
- Immediately sends correct glow state (glow for teammates, no glow for others)
- Prevents cache so `@ModifyVariable` won't redundantly force later
- **Defensive**: null-guards `viewer.connection` to prevent NPE on disconnect race

**`@Redirect` on `sendToTrackingPlayersAndSelf()`** — per-client glow customization:
- Intercepts entity data packets from `sendDirtyEntityData()`
- Creates two modified copies: one with glow bit (`FLAG_GLOWING` = 0x40) set, one cleared
- **Fast path**: `hasEnabledTeams()` → no team has glow enabled, forward the packet untouched (skips all per-packet lookups)
- Broadcasts the no-glow variant to everyone (tracking + self), then overlays the glow variant to teammates only via `sendToTrackingPlayersFiltered` — one pass over the tracking set, one scoreboard lookup per viewer (Netty FIFO guarantees teammates end up with the glow bit set)
- Self is never part of its own tracking set (`ChunkMap.TrackedEntity.updatePlayer` excludes self) — `sendToTrackingPlayersAndSelf` covers it, so no explicit self-send is needed
- **Optimization**: Entity-team lookup hoisted out of per-viewer predicate — computed once instead of per-viewer
- Skips when the entity is currently glowing (`LivingEntity.isCurrentlyGlowing()` — covers the GLOWING effect AND `setGlowingTag`)
- Falls back to vanilla path when entity is not in a glowing team
- Shared-flags id comes from `EntityAccessor.getSharedFlagsId().id()` — never hardcoded
- Both 26.1 and 26.2 use the same `sendToTrackingPlayersFiltered` API

### 2. `ScoreboardMixin` — Viewer-side team change detection

- `@Inject` on `Scoreboard.addPlayerToTeam(String, PlayerTeam)`
- `@Inject` on `Scoreboard.removePlayerFromTeam(String, PlayerTeam)` (the single-arg overload is NOT hooked — it internally calls the two-arg version on success)
- `@Inject` on `Scoreboard.removePlayerTeam(PlayerTeam)` — required because `/team remove` clears `teamsByPlayer` directly, bypassing both hooks above (without this, glow would linger indefinitely on viewers)
- All hooks funnel through `onTeamChange(PlayerTeam)`: bumps `syncEpoch` **only for teams with glow enabled** — membership changes in other teams cannot affect any glow display
- All three hooks carry explicit method descriptors (survives future overload additions)

### 3. `EntityAccessor` — Shared flags accessor

- `@Accessor` for `Entity.DATA_SHARED_FLAGS_ID` — static accessor for the shared flags `EntityDataAccessor`

### 4. `LivingEntityMixin` — Locator bar filter

- MixinExtras `@ModifyReturnValue` on `LivingEntity.makeWaypointConnectionWith(ServerPlayer)` (returns `Optional<Connection>`; an empty result makes `ServerWaypointManager.createConnection` tear the connection down)
- Gated by the `locator_bar_teammates_only` config switch (default off → vanilla behavior)
- **Asymmetric, receiver-driven semantics**: a viewer in a glow-enabled team hides members of OTHER glow-enabled teams (same-team, non-glow and teamless entities stay visible); viewers outside glow-enabled teams see everyone
- **No Stonecutter version gates** — the interface signature is identical in 26.1 and 26.2 (the `LocatorBarRenderer` vs `LocatorBar` client difference only matters for the client-side filtering route, which was not taken)
- MixinExtras is compile-time only (`io.github.llamalad7:mixinextras-fabric:0.5.4`); the runtime is bundled with fabric-loader (0.18.4 ships 0.5.0, 0.19.3 ships 0.5.4)
- Toggling the switch rebuilds all waypoint connections via `ServerLevel.getWaypointManager().remakeConnections(player)` (mirrors vanilla `ServerScoreboard.updateTeamWaypoints`)

### Config layer (`GlowConfigManager`)

- `loadFromWorld(server)`: resets to defaults **and bumps `version`** on missing OR corrupt config — no cross-world leakage, and entities that already cached the old state always notice the fallback
- `setEnabled(boolean)`: idempotent — no-op if already in the requested state (avoids spurious version bumps + full-server resyncs)
- `save()`: returns `boolean`; temp file + `ATOMIC_MOVE` with non-atomic fallback (`AtomicMoveNotSupportedException`); commands surface save failures to the admin
- `hasEnabledTeams()`: zero-allocation fast path (unlike `getEnabledTeams()`, which builds an unmodifiable view)
- Feature switches (`locatorBarTeammatesOnly`, `nonPlayerGlow`) with **idempotent setters** that bump `version` only on actual change
- Disk schema versioning: `config_version` `[major, minor]` array; legacy configs (field absent → `null`) are migrated to `[1, 0]` on load, **before** `version++`

## Stonecutter — Version Management

This project uses [Stonecutter](https://stonecutter.kikugie.dev/) to maintain a single codebase targeting multiple Minecraft versions.

### Key Concepts

- **VCS version** (26.2): The canonical source in the repository. Commit from this version.
- **Active version**: The version currently open in the IDE. Switch with the Gradle task.
- **Versioned comments**: `//? if 26.2 { ... } //?} else { ... }` gates code per version.

### Common Commands

```bash
# Build all versions
./gradlew build

# Switch active version in IDE
./gradlew setActiveVersion -Pversion=26.1

# Reset source to VCS version (run before committing!)
./gradlew "Reset active project"
```

### Adding a New Version

1. Add to `settings.gradle`: `versions '26.1', '26.2', '26.3'`
2. Create `versions/26.3/gradle.properties` with the correct dependencies
3. Add version-gated code blocks as needed: `//? if >=26.3 { ... }`

### Current Version Differences

| API | 26.1 | 26.2 |
|---|---|---|
| Permission check | `PermissionPredicates.require(...)` (Fabric `permission.v1`) | Same |
| Scoreboard access | `entity.level().getScoreboard()` | Same |
| Identifier factory | `Identifier.fromNamespaceAndPath()` | Same |

## Building

```bash
./gradlew build
```

Output JARs:
- `versions/26.2/build/libs/glow-my-teammates-1.1.0+26.2.jar`
- `versions/26.1/build/libs/glow-my-teammates-1.1.0+26.1.jar`

## Config

Per-world JSON at `<world>/glow-my-teammates.json`:

```json
{
  "enabled": true,
  "teams": ["red", "blue"],
  "config": {
    "locator_bar_teammates_only": false,
    "non_player_glow": false
  },
  "config_version": [1, 0]
}
```

## Commands

```
/teamglow on|off|status
/teamglow team add|remove|list <team>
/teamglow config list
/teamglow config locator_bar_teammates_only <true|false>
/teamglow config non_player_glow <true|false>
```

All commands are backed by `glow-my-teammates:command/*` permission nodes (Fabric API `permission.v1`, LuckPerms-compatible); without a permission mod they fall back to vanilla OP checks (OP 2 for on/off/team add/remove/config, none for status/list).

## Roadmap — all implemented (v1.1.0)

All six planned features have been implemented on the **`future-plan`** branch in the order 3 → 5 → 6 → 4 → 2 → 1 (feasibility analysis in the project docs):

1. **Permission nodes** — Fabric API `permission.v1` (`PermissionPredicates.require`); `PermissionLevel` is the **Mojang** enum `net.minecraft.server.permissions.PermissionLevel`, not a Fabric class.
2. **Remove `§` codes** → `Component.translatable().withStyle()`.
3. **Unified config sub-command + `config_version`** — `[major, minor]` schema array; coexists with the runtime `version` cache counter (migration runs after parse, before `version++`).
4. **Server-side translations** — NucleoidMC/Server-Translations; lang files in `data/<modid>/lang/`; the `server-translations-api` version is per-MC-version in `versions/*/gradle.properties` (`3.0.3+26.1` / `3.1.0+26.2`) and bundled via `implementation include(...)`.
5. **Non-player entity glow** — the `instanceof Player` guards in `ServerEntityMixin` became `!(entity instanceof Player) && !isNonPlayerGlow()`; `getGlowingTeam(Entity)`; gated by the `non_player_glow` switch.
6. **Locator bar shows teammates only** — `LivingEntityMixin` with MixinExtras `@ModifyReturnValue` on `WaypointTransmitter.makeWaypointConnectionWith`. Note: turned out to need **no** Stonecutter version gates — the interface is identical in 26.1/26.2.

Future work happens on the `future-plan` branch, committing per feature and merging back to `main` when complete.

## Rules for Contributors

- **Do NOT use `Entity.getServer()`** — removed in 26.1+. Use `entity.level().getScoreboard()`.
- **Do NOT toggle entity data directly** — corrupts state. Inject missing packets via `@ModifyVariable`.
- The `Synchronizer` inner interface is importable as `ServerEntity.Synchronizer`.
- **No Yarn mappings.** All class/method names are Mojang (official).
- **Always commit from VCS version (26.2).** Run `"Reset active project"` before committing to avoid Stonecutter preprocessor noise in Git history.
- **Future development happens on the `future-plan` branch** — commit per feature, merge back to `main` when a feature is complete.
- **New version-gated code**: Use `//? if <version> { ... }` syntax. Avoid raw `/* */` comments for versioning.
